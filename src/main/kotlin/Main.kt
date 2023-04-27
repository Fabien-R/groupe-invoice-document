import com.sksamuel.hoplite.ConfigLoader
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

data class Env(val env: String, val postgres: Postgres, val aws: Aws, val params: Params)
data class Postgres(val port: Int, val host: String, val database: String, val user: String, val password: String, val trustAll: Boolean)
data class Aws(val region: String, val secret: String, val key: String, val documentsBucket: String, val dryRun: Boolean)
data class Params(val clientId: String, val depositStartDateIncl: String, val depositEndDateExcl: String)

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

fun String.onlyKeepAlphaNumeric() = this.lowercase().replace(Regex("[^a-zA-Z0-9]+"), "")

private fun toBucketName(invoice: Invoice, env: String): String = "agapio-client-${invoice.clientName.lowercase()}-$env"

@OptIn(ExperimentalTime::class)
fun main() {
    val (env, postgres, aws, params) = ConfigLoader().loadConfigOrThrow<Env>("./src/main/resources/application.json")

    require(params.clientId.isNotBlank()) { "clientId should be provided in the configuration" }
    require(params.depositStartDateIncl.isNotBlank()) { "depositStartDateIncl should be provided in the configuration with the format $DATE_FORMAT" }
    require(params.depositEndDateExcl.isNotBlank()) { "depositEndDateExcl should be provided in the configuration $DATE_FORMAT" }


    val s3Service = S3Service(aws.region, aws.key, aws.secret, aws.dryRun)

    try {
        s3Service.ensureBucketExists(aws.documentsBucket)
    } catch (e: Exception) {
        println(e.message)
        exitProcess(1)
    }

    val hikari = hikari(postgres)
    val database = database(hikari)


    runBlocking {
        val duration = measureTime {
            val invoices = database.invoicesQueries.selectAllInvoicesBetweenStartAndEndForClient(
                toOffsetDateTime(params.depositStartDateIncl),
                toOffsetDateTime(params.depositEndDateExcl),
                UUID.fromString(params.clientId)
            ) { client_name, restaurant_name, _, date, supplier_name, reference, document_id, total_inc, original_filename ->
                Invoice(
                    client_name!!.onlyKeepAlphaNumeric(),
                    restaurant_name!!.onlyKeepAlphaNumeric(),
                    date,
                    supplier_name!!.onlyKeepAlphaNumeric(),
                    reference!!,
                    document_id,
                    total_inc,
                    original_filename!!
                )
            }.executeAsList()

            val firstInvoice = invoices.getOrNull(0)
            if (firstInvoice == null) {
                println("No invoices")
                return@runBlocking
            }
            val toBucketName = toBucketName(firstInvoice, env)

            try {
                s3Service.copyInvoiceFileToClientBucket(aws.documentsBucket, toBucketName, invoices)
            } catch (e: Exception) {
                println("Failed to copy: ${e.message}")
            }
        }
        println("Total duration: $duration")
    }
}