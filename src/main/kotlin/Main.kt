import env.dependencies
import env.getConfiguration
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.ofPattern
import java.util.*
import kotlin.system.exitProcess
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime


const val DATE_FORMAT = "yyyy-MM-dd"
val PARAMS_DATE_FORMATTER: DateTimeFormatter = ofPattern(DATE_FORMAT)

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

@OptIn(ExperimentalTime::class)
fun main() {
    val env = getConfiguration()

    val clientId = env.params.clientId
    val startDate = env.params.depositStartDateIncl
    val endDate = env.params.depositEndDateExcl
    val documentBucket = env.aws.documentsBucket

    require(clientId.isNotBlank()) { "clientId should be provided in the configuration" }
    require(startDate.isNotBlank()) { "depositStartDateIncl should be provided in the configuration with the format $DATE_FORMAT" }
    require(endDate.isNotBlank()) { "depositEndDateExcl should be provided in the configuration $DATE_FORMAT" }


    val modules  = dependencies(env)

    try {
        modules.s3Service.ensureBucketExists(documentBucket)
    } catch (e: Exception) {
        println(e.message)
        exitProcess(1)
    }



    runBlocking {
        val duration = measureTime {
            val invoices = modules.invoicePersistence.getAllInvoices(
                toOffsetDateTime(env.params.depositStartDateIncl),
                toOffsetDateTime(endDate),
                UUID.fromString(clientId)
            )


            val firstInvoice = invoices.getOrNull(0)
            if (firstInvoice == null) {
                println("No invoices")
                return@runBlocking
            }
            val toBucketName = toBucketName(firstInvoice, env.env)

            try {
                modules.s3Service.copyInvoiceFileToClientBucket(documentBucket, toBucketName, invoices)
            } catch (e: Exception) {
                println("Failed to copy: ${e.message}")
            }
        }
        println("Total duration: $duration")
    }
}