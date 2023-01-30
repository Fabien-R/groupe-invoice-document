import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.headBucket
import aws.sdk.kotlin.services.s3.model.BucketLocationConstraint
import aws.sdk.kotlin.services.s3.model.CopyObjectRequest
import aws.sdk.kotlin.services.s3.model.CreateBucketConfiguration
import aws.sdk.kotlin.services.s3.model.CreateBucketRequest
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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


class S3Service(private val _region: String, private val _key: String, private val _secret: String, private val _dryRyn: Boolean) {

    private suspend fun execute(command: suspend () -> Unit) = if (!_dryRyn) command() else delay(100)

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

    fun copyInvoiceFileToClientBucket(fromBucket: String, invoices: List<Invoice>, envSuffix: String) {
        val firstInvoice = invoices.getOrNull(0)
        if (firstInvoice == null) {
            println("No invoices")
            return
        }
        val size = invoices.size
        val toBucketName = "agapio-client-${firstInvoice.clientName.lowercase()}-$envSuffix"
        runBlocking {
            val createBucket = launch {
                s3Client.createBucket(toBucketName)
            }
            createBucket.join()
        }

        val dateFolderFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
        val dateFileFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        invoices.forEachIndexed { index, invoice ->
            runBlocking {
                execute {
                    val copyFile = launch {
                        s3Client.copyS3Object(
                            fromBucket,
                            invoice.documentId.toString(),
                            toBucketName,
                            invoice.toS3Key(dateFolderFormatter, dateFileFormatter),
                        )

                    }
                    copyFile.join() // sequential copy -- s3 has probably some constraint about the number of // command execution
                }
                println("${round(index.toFloat() * 10000 / size) / 100}%")
            }

        }
    }


}