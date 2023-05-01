package env

import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import database
import hikari
import repository.InvoicePersistence
import repository.invoicePersistence
import s3.S3Service
import s3.s3ClientWrapper
import s3.s3Service

class Dependencies(
    val s3Service: S3Service,
    val invoicePersistence: InvoicePersistence
)

private class S3CredentialProviderLight(key: String, secret: String) : CredentialsProvider {
    private val credentials = Credentials(accessKeyId = key, secretAccessKey = secret)
    override suspend fun getCredentials(): Credentials = credentials

}

fun dependencies(env: Env): Dependencies {
    val hikari = hikari(env.postgres)
    val database = database(hikari)
    val s3Client = S3Client {
        region = env.aws.region
        useArnRegion = true
        credentialsProvider = S3CredentialProviderLight(key = env.aws.key, secret = env.aws.secret)
    }
    return Dependencies(
        s3Service(s3ClientWrapper(s3Client, env.aws.dryRun)),
        invoicePersistence(database.invoicesQueries)
    )
}