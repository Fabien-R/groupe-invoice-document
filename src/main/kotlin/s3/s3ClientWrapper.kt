package s3

import Invoice
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.headBucket
import aws.sdk.kotlin.services.s3.model.*
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter

private const val DATE_FOLDER_FORMAT = "yyyy-MM"
private const val DATE_FILE_FORMAT = "yyyy-MM-dd"

interface S3ClientWrapper {
    suspend fun <T> execute(dumb: T, command: suspend () -> T): T
    fun copyInvoiceDocumentCommand(fromBucket: String, toBucket: String, invoice: Invoice): suspend () -> Unit
    fun bucketExistCommand(bucketName: String): suspend () -> Boolean
    fun createBucketCommand(bucketName: String): suspend () -> Unit

}

fun s3ClientWrapper(s3Client: S3Client, dryRyn: Boolean) = object : S3ClientWrapper {
    private val dateFolderFormatter = DateTimeFormatter.ofPattern(DATE_FOLDER_FORMAT)
    private val dateFileFormatter = DateTimeFormatter.ofPattern(DATE_FILE_FORMAT)

    override suspend fun <T> execute(dumb: T, command: suspend () -> T): T {
        return if (!dryRyn)
            command()
        else {
            delay(100)
            dumb
        }

    }

    override fun copyInvoiceDocumentCommand(fromBucket: String, toBucket: String, invoice: Invoice): suspend () -> Unit = {
        s3Client.copyS3Object(
            fromBucket,
            invoice.documentId.toString(),
            toBucket,
            invoice.toS3Key(dateFolderFormatter, dateFileFormatter),
        )
    }

    override fun bucketExistCommand(bucketName: String): suspend () -> Boolean = {
        try {
            s3Client.headBucket { bucket = bucketName }
            true
        } catch (e: Exception) { // Checking Service Exception coming in future release
            false
        }
    }

    override fun createBucketCommand(bucketName: String): suspend () -> Unit = {
        if (!bucketExistCommand(bucketName).invoke()) {
            s3Client.createBucket(CreateBucketRequest {
                bucket = bucketName
                createBucketConfiguration = CreateBucketConfiguration {
                    locationConstraint = BucketLocationConstraint.EuWest1
                }
                acl = BucketCannedAcl.fromValue("private")
            })
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
}

private fun Invoice.toS3Key(
    dateFolderFormatter: DateTimeFormatter,
    dateFileFormatter: DateTimeFormatter
) =
    "${this.restaurantName}/${this.date?.format(dateFolderFormatter) ?: "empty"}/${this.date?.format(dateFileFormatter) ?: ""} - ${this.supplierName} - ${this.documentId.hashCode()} - EUR - ${
        this.totalPriceIncl.toString().replace(".", "_")
    }.${this.originalFileName.substringAfterLast(".", "unknown")}"