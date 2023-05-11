package s3

import BucketAccessForbidden
import BucketAlreadyExist
import BucketError
import BucketNotFound
import BucketNotValid
import BucketOtherException
import CopyFailure
import Invoice
import arrow.core.Either
import arrow.core.right
import aws.sdk.kotlin.runtime.AwsServiceException
import aws.sdk.kotlin.runtime.auth.credentials.internal.sso.model.UnauthorizedException
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.deleteObjects
import aws.sdk.kotlin.services.s3.headBucket
import aws.sdk.kotlin.services.s3.model.*
import aws.sdk.kotlin.services.s3.paginators.listObjectsV2Paginated
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import java.time.format.DateTimeFormatter

private const val DATE_FOLDER_FORMAT = "yyyy-MM"
private const val DATE_FILE_FORMAT = "yyyy-MM-dd"

interface S3ClientWrapper {
    suspend fun execute(command: suspend () -> Either<BucketError, Unit>): Either<BucketError, Unit>
    fun copyInvoiceDocumentCommand(fromBucket: String, toBucket: String, invoice: Invoice): suspend () -> Either<BucketError, Unit>
    fun bucketExistCommand(bucketName: String): suspend () -> Either<BucketError, Unit>
    fun createBucketCommand(bucketName: String): suspend () -> Either<BucketError, Unit>

    /**
     * Cleaning utility for automating performance measurement
     */
    fun emptyAndDeleteBucketCommand(bucketName: String): suspend () -> Either<BucketError, Unit>

}

fun s3ClientWrapper(s3Client: S3Client, dryRyn: Boolean) = object : S3ClientWrapper {
    private val dateFolderFormatter = DateTimeFormatter.ofPattern(DATE_FOLDER_FORMAT)
    private val dateFileFormatter = DateTimeFormatter.ofPattern(DATE_FILE_FORMAT)

    override suspend fun execute(command: suspend () -> Either<BucketError, Unit>): Either<BucketError, Unit> {
        return if (!dryRyn)
            command()
        else {
            delay(100).right()
        }

    }

    override fun copyInvoiceDocumentCommand(fromBucket: String, toBucket: String, invoice: Invoice): suspend () -> Either<BucketError, Unit> = {
        s3Client.copyS3Object(
            fromBucket,
            invoice.documentId.toString(),
            toBucket,
            invoice.toS3Key(dateFolderFormatter, dateFileFormatter),
        )
    }

    override fun bucketExistCommand(bucketName: String): suspend () -> Either<BucketError, Unit> = {
        Either.catchOrThrow<AwsServiceException, Unit> {
            s3Client.headBucket { bucket = bucketName }
        }.mapLeft { s3Exception ->
            when (s3Exception) {
                is NotFound -> BucketNotFound(bucketName)
                is UnauthorizedException -> BucketAccessForbidden(bucketName)
                else -> BucketOtherException(bucketName, s3Exception::class.simpleName)
            }
        }
    }

    override fun createBucketCommand(bucketName: String): suspend () -> Either<BucketError, Unit> = {
        Either.catchOrThrow<AwsServiceException, Unit> {
            s3Client.createBucket(CreateBucketRequest {
                bucket = bucketName
                createBucketConfiguration = CreateBucketConfiguration {
                    locationConstraint = BucketLocationConstraint.EuWest1
                }
                acl = BucketCannedAcl.fromValue("private")
            })
        }.mapLeft { s3Exception ->
            when {
                s3Exception is NotFound -> BucketNotFound(bucketName)
                s3Exception is UnauthorizedException -> BucketAccessForbidden(bucketName)
                s3Exception is BucketAlreadyExists -> BucketAlreadyExist(bucketName)
                s3Exception is BucketAlreadyOwnedByYou -> BucketAlreadyExist(bucketName)
                s3Exception is S3Exception && (s3Exception.message?.contains("is not valid") ?: false) -> BucketNotValid(bucketName)
                else -> BucketOtherException(bucketName, s3Exception::class.simpleName)
            }
        }
    }


    private suspend fun S3Client.copyS3Object(fromBucket: String, fromKey: String, toBucket: String, toKey: String): Either<BucketError, Unit> =
        Either.catchOrThrow<AwsServiceException, Unit> {
            this.copyObject(
                CopyObjectRequest {
                    copySource = "$fromBucket/$fromKey"
                    bucket = toBucket
                    key = toKey
                })
        }.mapLeft { s3Exception ->
            when (s3Exception) {
                is NotFound -> BucketNotFound(toBucket)
                is UnauthorizedException -> BucketAccessForbidden(toBucket)
                else -> CopyFailure(toBucket, fromKey, s3Exception.message ?: "")
            }
        }


    // implementation can be enhanced to separate the different commands
    override fun emptyAndDeleteBucketCommand(bucketName: String): suspend () -> Either<BucketError, Unit> = {
        Either.catchOrThrow<AwsServiceException, Unit> {
            s3Client.listObjectsV2Paginated(ListObjectsV2Request {
                bucket = bucketName
            })
                .map { it.contents ?: emptyList() }
                .map { keys -> keys.map { ObjectIdentifier { key = it.key } } }
                .collect { keys ->
                    s3Client.deleteObjects {
                        bucket = bucketName
                        delete {
                            objects = keys
                        }
                    }
                }

            s3Client.deleteBucket(DeleteBucketRequest {
                bucket = bucketName
            })
        }.mapLeft { s3Exception ->
            when {
                s3Exception is NotFound -> BucketNotFound(bucketName)
                s3Exception is UnauthorizedException -> BucketAccessForbidden(bucketName)
                s3Exception is S3Exception && (s3Exception.message?.contains("is not valid") ?: false) -> BucketNotValid(bucketName)
                else -> BucketOtherException(bucketName, s3Exception::class.simpleName)
            }
        }
    }
}

private fun Invoice.toS3Key(
    dateFolderFormatter: DateTimeFormatter,
    dateFileFormatter: DateTimeFormatter
) =
    "${this.restaurantName}/${this.date?.format(dateFolderFormatter) ?: "empty"}/${this.date?.format(dateFileFormatter) ?: ""} - ${this.supplierName} - ${this.documentId.hashCode()} - EUR - ${
        this.totalPriceIncl.toString().replace(".", "_")
    }.${this.originalFileName.substringAfterLast(".", "unknown")}"