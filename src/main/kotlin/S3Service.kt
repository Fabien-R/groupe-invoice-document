import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.headBucket
import aws.sdk.kotlin.services.s3.model.BucketLocationConstraint
import aws.sdk.kotlin.services.s3.model.CopyObjectRequest
import aws.sdk.kotlin.services.s3.model.CreateBucketConfiguration
import aws.sdk.kotlin.services.s3.model.CreateBucketRequest
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.lang.Integer.max
import java.time.format.DateTimeFormatter
import kotlin.math.round

class S3CredentialProviderLight(key: String, secret: String) : CredentialsProvider {
    private val credentials = Credentials(accessKeyId = key, secretAccessKey = secret)
    override suspend fun getCredentials(): Credentials = credentials

}

private fun Invoice.toS3Key(
    dateFolderFormatter: DateTimeFormatter?,
    dateFileFormatter: DateTimeFormatter?
) =
    "${this.restaurantName}/${this.date?.format(dateFolderFormatter) ?: "empty"}/${this.date?.format(dateFileFormatter) ?: ""} - ${this.supplierName} - ${this.documentId.hashCode()} - EUR - ${
        this.totalPriceIncl.toString().replace(".", "_")
    }.${this.originalFileName.substringAfterLast(".", "unknown")}"

@OptIn(FlowPreview::class)
private fun <T, R> Flow<T>.concurrentMap(dispatcher: CoroutineDispatcher, concurrencyLevel: Int, transform: suspend (T) -> R): Flow<R> {
    return flatMapMerge(concurrencyLevel) { value ->
        flow { emit(transform(value)) }
    }.flowOn(dispatcher)
}


class S3Service(private val _region: String, private val _key: String, private val _secret: String, private val _dryRyn: Boolean) {

    private suspend fun execute(command: suspend () -> Unit) = if (!_dryRyn) command() else delay(100)
    private val dateFolderFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    private val dateFileFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private fun copyInvoiceDocumentCommand(fromBucket: String, toBucket: String, invoice: Invoice): suspend () -> Unit = {
        s3Client.copyS3Object(
            fromBucket,
            invoice.documentId.toString(),
            toBucket,
            invoice.toS3Key(dateFolderFormatter, dateFileFormatter),
        )
    }

    private val s3Client = S3Client {
        region = _region
        useArnRegion = true
        credentialsProvider = S3CredentialProviderLight(key = _key, secret = _secret)
    }

    private suspend fun S3Client.bucketExists(s3bucket: String) =
        try {
            headBucket { bucket = s3bucket }
            true
        } catch (e: Exception) { // Checking Service Exception coming in future release
            false
        }

    fun ensureBucketExists(bucketName: String) {
        runBlocking {
            require(s3Client.bucketExists(bucketName)) { "Bucket $bucketName does not exist" }
        }
    }

    private suspend fun S3Client.createBucket(bucketName: String) {
        execute {
            if (!this.bucketExists(bucketName)) {
                this.createBucket(CreateBucketRequest {
                    bucket = bucketName
                    createBucketConfiguration = CreateBucketConfiguration {
                        locationConstraint = BucketLocationConstraint.EuWest1
                    }
                })
            }
        }
    }

    private suspend fun S3Client.copyS3Object(fromBucket: String, fromKey: String, toBucket: String, toKey: String) {
        this.copyObject(
            CopyObjectRequest {
                copySource = "$fromBucket/$fromKey"
                bucket = toBucket
                key = toKey
            })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun copyInvoiceFileToClientBucket(fromBucket: String, invoices: List<Invoice>, envSuffix: String) {
        val firstInvoice = invoices.getOrNull(0)
        if (firstInvoice == null) {
            println("No invoices")
            return
        }

        val concurrency = 7
        val dispatcher = Dispatchers.Default.limitedParallelism(concurrency)
        val toBucketName = "agapio-client-${firstInvoice.clientName.lowercase()}-$envSuffix"
        runBlocking {
            val createBucket = launch(dispatcher)  {
                s3Client.createBucket(toBucketName)
            }
            createBucket.join()
        }

        val size = invoices.size
        copyInvoices3(invoices, fromBucket, toBucketName, dispatcher, size, concurrency)
//      copyInvoices2(invoices, fromBucket, toBucketName, dispatcher, size)
//      copyInvoiceBase(invoices, fromBucket, toBucketName, dispatcher, size)

    }

    @OptIn(FlowPreview::class)
    private suspend fun copyInvoices3(
        invoices: List<Invoice>,
        fromBucket: String,
        toBucketName: String,
        dispatcher: CoroutineDispatcher,
        size: Int,
        concurrency: Int,
    ) {
        val chunkSize = max(round(size / concurrency.toFloat()).toInt(), 1)
        println("chunksize: $chunkSize")
        invoices
            .map { invoice -> copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }
            .chunked(chunkSize).asFlow()
            .onStart { println("Start copy") }
            .flatMapMerge(concurrency) { copyCommands ->
                copyCommands.asFlow().onEach { execute(it) }
            }
            .flowOn(dispatcher)
            .scan(0) { acc, _ -> acc + 1 }
            .onCompletion { println("Copy done ") }
            .filter { it % 100 == 0 }
            .collect {
                println("${round(it.toFloat() * 10000 / size) / 100}%")
            }
    }

    private suspend fun copyInvoices2(
        invoices: List<Invoice>,
        fromBucket: String,
        toBucketName: String,
        dispatcher: CoroutineDispatcher,
        size: Int,
    ) =
        invoices.asFlow()
            .map { invoice -> copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }
            .concurrentMap(dispatcher, 400000) {
                execute { it.invoke() }
            }
            .scan(0) { acc, _ -> acc + 1 }
            .collect {
                if (it % 20 == 0)
                    println("${round(it.toFloat() * 10000 / size) / 100}%")
            }


    private suspend fun copyInvoiceBase(
        invoices: List<Invoice>,
        fromBucket: String,
        toBucketName: String,
        dispatcher: CoroutineDispatcher,
        size: Int
    ) {
        coroutineScope {
            invoices.map { invoice -> copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }.map {
                this.launch(dispatcher) {
                    execute { it.invoke() }
                }
            }.forEachIndexed { index, it ->
                it.join()
                if (index % 20 == 0)
                    println("${round(index.toFloat() * 10000 / size) / 100}%")
            }
        }
    }
}