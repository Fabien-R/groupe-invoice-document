import arrow.core.raise.Raise

typealias DomainErrors = Raise<BucketError>

sealed interface DomainError {
    override fun toString(): String
}


sealed class BucketError(val bucketName: String): DomainError

class BucketNotFound(bucketName: String): BucketError(bucketName) {
    override fun toString(): String = "Bucket ${this.bucketName} not found"
}

class BucketAlreadyExist(bucketName: String): BucketError(bucketName) {
    override fun toString(): String = "Bucket ${this.bucketName} already exists"
}

class BucketAccessForbidden(bucketName: String): BucketError(bucketName) {
    override fun toString(): String = "Not authorized to access bucket ${this.bucketName}"
}
class BucketNotValid(bucketName: String): BucketError(bucketName) {
    override fun toString(): String = "Bucket ${this.bucketName} name is not valid"
}
class CopyFailure(bucketName: String, val fileName: String, val error: String?): BucketError(bucketName) {
    override fun toString(): String = "Failed to copy ${this.fileName} into ${this.bucketName} because ${this.error}"
}
class CopiesFailures(bucketName: String): BucketError(bucketName) {
    private val _failures = mutableListOf<BucketError>()

    val failures
        get() = _failures.toList()

    fun append(error: BucketError): CopiesFailures {
        _failures.add(error)
        return this
    }

    fun appendAll(errors: List<BucketError>): CopiesFailures {
        _failures.addAll(errors)
        return this
    }

    override fun toString(): String = "Failed to copy ${failures.size} files into $bucketName"
}
class BucketOtherException(bucketName: String, val error: String?): BucketError(bucketName) {
    override fun toString(): String = "Exception ${this.error} about bucket ${this.bucketName}"
}


sealed class InvoiceError: DomainError

object NoInvoice: InvoiceError() {
    override fun toString(): String = "No invoice"
}



