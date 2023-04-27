package env

import com.sksamuel.hoplite.ConfigLoader

data class Env(val env: String, val postgres: Postgres, val aws: Aws, val params: Params)
data class Postgres(val port: Int, val host: String, val database: String, val user: String, val password: String)
data class Aws(val region: String, val secret: String, val key: String, val documentsBucket: String, val dryRun: Boolean)
data class Params(val clientId: String, val depositStartDateIncl: String, val depositEndDateExcl: String)

fun getConfiguration() = ConfigLoader().loadConfigOrThrow<Env>("./src/main/resources/application.json")