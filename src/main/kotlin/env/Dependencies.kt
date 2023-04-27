package env

import S3Service
import database
import hikari
import repository.InvoicePersistence
import repository.invoicePersistence

class Dependencies(
    val s3Service: S3Service,
    val invoicePersistence: InvoicePersistence
)

fun dependencies(env: Env): Dependencies {
    val hikari = hikari(env.postgres)
    val database = database(hikari)
    return Dependencies(
        S3Service(env.aws.region, env.aws.key, env.aws.secret, env.aws.dryRun),
        invoicePersistence(database.invoicesQueries)
    )
}