import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.github.fabien.Database
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import env.Postgres
import javax.sql.DataSource


fun hikari(env: Postgres): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://${env.host}:${env.port}/${env.database}"
            username = env.user
            password = env.password
            driverClassName = "org.postgresql.Driver"
        }
    )

fun database(dataSource: DataSource) = Database(dataSource.asJdbcDriver())
