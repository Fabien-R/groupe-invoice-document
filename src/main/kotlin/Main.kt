import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import env.Dependencies
import env.dependencies
import env.getConfiguration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.ofPattern
import java.util.*
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime


const val DATE_FORMAT = "yyyy-MM-dd"
val PARAMS_DATE_FORMATTER: DateTimeFormatter = ofPattern(DATE_FORMAT)

// Should be in InvoiceService if any InvoiceService was created
data class Invoice(
    val clientName: String,
    val restaurantName: String,
    val date: LocalDate?,
    val supplierName: String,
    val reference: String,
    val documentId: UUID,
    val totalPriceIncl: Double,
    val originalFileName: String,
)

// TODO Is the TZ correct ?
fun toOffsetDateTime(date: String): OffsetDateTime = OffsetDateTime.of(
    LocalDate.parse(date, PARAMS_DATE_FORMATTER.withZone(ZoneId.of("Europe/Paris"))).atStartOfDay(),
    ZoneOffset.UTC
)

private fun toBucketName(invoice: Invoice, env: String): String = "agapio-client-${invoice.clientName.lowercase()}-$env"

suspend fun main() {
    val env = getConfiguration()

    val clientId = env.params.clientId
    val startDate = env.params.depositStartDateIncl
    val endDate = env.params.depositEndDateExcl
    val documentBucket = env.aws.documentsBucket

    require(clientId.isNotBlank()) { "clientId should be provided in the configuration" }
    require(startDate.isNotBlank()) { "depositStartDateIncl should be provided in the configuration with the format $DATE_FORMAT" }
    require(endDate.isNotBlank()) { "depositEndDateExcl should be provided in the configuration $DATE_FORMAT" }


    val modules = dependencies(env)

    copyDepositFiles(modules, documentBucket, startDate, endDate, clientId, env.env)

}

@OptIn(ExperimentalTime::class)
private suspend fun copyDepositFiles(
    modules: Dependencies,
    documentBucket: String,
    startDate: String,
    endDate: String,
    clientId: String,
    env: String
) {
    measureTime {
        either {
            modules.s3Service.ensureBucketExists(documentBucket).bind()

            val invoices = modules.invoicePersistence.getAllInvoices(
                toOffsetDateTime(startDate),
                toOffsetDateTime(endDate),
                UUID.fromString(clientId)
            )

            val firstInvoice = ensureNotNull(invoices.getOrNull(0)) { NoInvoice }
            val toBucketName = toBucketName(firstInvoice, env)
            println("${invoices.size} invoice-files to copy")

            modules.s3Service.copyInvoiceFileToClientBucket(documentBucket, toBucketName, invoices).bind()

        }.mapLeft(DomainError::toLog)
    }.let { println("Total duration: $it") }
}

fun DomainError.toLog() {
    when (this) {
        is CopiesFailures -> failures.map(DomainError::toString)
        else -> listOf(this.toString())
    }.forEach(::println)
}