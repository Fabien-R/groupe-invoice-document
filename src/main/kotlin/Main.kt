import com.sksamuel.hoplite.ConfigLoader
import io.vertx.kotlin.coroutines.await
import io.vertx.pgclient.PgConnectOptions
import io.vertx.pgclient.PgPool
import io.vertx.pgclient.SslMode
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
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
data class Postgres(val port: Int, val host: String, val database: String, val user: String, val password: String, val trustAll: Boolean, val sslMode: SslMode)
data class Aws(val region: String, val secret: String, val key: String, val documentsBucket: String, val dryRun: Boolean)
data class Params(val clientId: String, val depositStartDateIncl: String, val depositEndDateExcl: String)

data class Invoice(
    val clientName: String,
    val restaurantName: String,
    val date: LocalDate?,
    val supplierName: String,
    val reference: String,
    val documentId: UUID,
    val totalPriceIncl: Float,
    val originalFileName: String,
)

fun createPgClient(postgres: Postgres): SqlClient {
    val connectOptions: PgConnectOptions = PgConnectOptions()
        .setPort(postgres.port)
        .setHost(postgres.host)
        .setDatabase(postgres.database)
        .setUser(postgres.user)
        .setPassword(postgres.password)
        .setTrustAll(postgres.trustAll).setSslMode(postgres.sslMode)

    // Pool options
    val poolOptions: PoolOptions = PoolOptions().setMaxSize(5)

// Create the pooled client
    return PgPool.client(connectOptions, poolOptions)
}

// TODO Is the TZ correct ?
fun toOffsetDateTime(date: String): OffsetDateTime = OffsetDateTime.of(
    LocalDate.parse(date, PARAMS_DATE_FORMATTER.withZone(ZoneId.of("Europe/Paris"))).atStartOfDay(),
    ZoneOffset.UTC
)


fun postgresOnlyKeepAlphaNumeric(s: String) = "lower(regexp_replace($s, '[^a-zA-Z0-9]+', '', 'g'))"

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

    val pgClient = createPgClient(postgres)

    runBlocking {
        val invoices = pgClient.preparedQuery(
            """
        select ${postgresOnlyKeepAlphaNumeric("c.name")} as client_name, ${postgresOnlyKeepAlphaNumeric("r.name")} as restaurant_name, r.id as restaurant_id, date, ${
                postgresOnlyKeepAlphaNumeric(
                    "s.name"
                )
            } as supplier_name, reference, invoices.document_id, coalesce(round(invoices.total_price_ttc::decimal/10000, 2), 0.0) as total_incl, initial_deposits.original_filename
        from invoices
        left join documents initial_deposits on invoices.original_deposit_id = initial_deposits.id
        left join restaurants r on invoices.restaurant_id = r.id
        left join clients c on r.client_id = c.id
        left join suppliers s on invoices.supplier_id = s.id
        where invoices.status = 'complete'
          and initial_deposits.created_at >=$1
          and initial_deposits.created_at <$2
          and c.id=$3
        order by c.name, r.name, s.name, date, reference, invoices.id

    """.trimIndent()
        )
            .mapping { row ->
                Invoice(
                    row.getString("client_name"),
                    row.getString("restaurant_name"),
                    row.getLocalDate("date"),
                    row.getString("supplier_name"),
                    row.getString("reference") ?: "",
                    row.getUUID("document_id"),
                    row.getFloat("total_incl"),
                    row.getString("original_filename")
                )
            }
            .execute(
                Tuple.of(
                    toOffsetDateTime(params.depositStartDateIncl),
                    toOffsetDateTime(params.depositEndDateExcl),
                    params.clientId,
                )
            )
            .onFailure {
                pgClient.close()
                println("error while retrieving invoices: ${it.message}")
            }.onSuccess {
                println("Retrieve ${it.rowCount()} invoices successfully")
                pgClient.close()
            }
            .map { rs ->
                rs.toList()
            }
            .await()

        val duration = measureTime {
            try {
                s3Service.copyInvoiceFileToClientBucket(aws.documentsBucket, invoices, env)
            } catch (e: Exception) {
                println("Failed to copy: ${e.message}")
            }
        }
        println("Succeeded to copy in $duration")
    }
}