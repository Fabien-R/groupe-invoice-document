import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.headBucket
import aws.sdk.kotlin.services.s3.model.*
import kotlinx.coroutines.*
import java.time.format.DateTimeFormatter
import kotlin.math.round
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

private const val DATE_FOLDER_FORMAT = "yyyy-MM"
private const val DATE_FILE_FORMAT = "yyyy-MM-dd"

interface S3Service {
    fun ensureBucketExists(bucketName: String)
    suspend fun copyInvoiceFileToClientBucket(fromBucket: String, toBucket: String, invoices: List<Invoice>)
    // TODO refactor implementationPerFTest to not publish these internal
    fun copyInvoiceDocumentCommand(fromBucket: String, toBucket: String, invoice: Invoice): suspend () -> Unit
    suspend fun execute(command: suspend () -> Unit)
}

internal fun percentageRateWith2digits(number: Int, total: Int) = round(number.toFloat() * 10000 / total) / 100

private fun Invoice.toS3Key(
    dateFolderFormatter: DateTimeFormatter,
    dateFileFormatter: DateTimeFormatter
) =
    "${this.restaurantName}/${this.date?.format(dateFolderFormatter) ?: "empty"}/${this.date?.format(dateFileFormatter) ?: ""} - ${this.supplierName} - ${this.documentId.hashCode()} - EUR - ${
        this.totalPriceIncl.toString().replace(".", "_")
    }.${this.originalFileName.substringAfterLast(".", "unknown")}"


fun s3Service(s3Client: S3Client, dryRyn: Boolean) = object : S3Service {
    val dateFolderFormatter = DateTimeFormatter.ofPattern(DATE_FOLDER_FORMAT)
    private val dateFileFormatter = DateTimeFormatter.ofPattern(DATE_FILE_FORMAT)

    override suspend fun execute(command: suspend () -> Unit) = if (!dryRyn) command() else delay(100)
    override fun copyInvoiceDocumentCommand(fromBucket: String, toBucket: String, invoice: Invoice): suspend () -> Unit = {
        s3Client.copyS3Object(
            fromBucket,
            invoice.documentId.toString(),
            toBucket,
            invoice.toS3Key(dateFolderFormatter, dateFileFormatter),
        )
    }

    private suspend fun S3Client.bucketExists(bucketName: String) =
        try {
            headBucket { bucket = bucketName }
            true
        } catch (e: Exception) { // Checking Service Exception coming in future release
            false
        }

    override fun ensureBucketExists(bucketName: String) {
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
                    acl = BucketCannedAcl.fromValue("private")
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

    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
    override suspend fun copyInvoiceFileToClientBucket(fromBucket: String, toBucket: String, invoices: List<Invoice>) {
        s3Client.createBucket(toBucket)

        val size = invoices.size
        val concurrency = 1000
        val dispatcher = Dispatchers.Default.limitedParallelism(concurrency)
        val duration = measureTime {
            copyInvoiceBase(invoices, fromBucket, toBucket, dispatcher, size)
        }
        println("Copy duration: $duration")
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
                if (index % 100 == 0)
                    println("${percentageRateWith2digits(index, size)}%")
            }
        }
    }
}