import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.sqldelight)
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(libs.bundles.arrow)
    implementation(libs.aws.sdk.kotlin.s3)
    implementation(libs.coroutines.core)
    implementation(libs.hikari)
    implementation(libs.bundles.hoplite)
    implementation(libs.postgresql)
    implementation(libs.sqldelight.jdbc)
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions {
        jvmTarget = "13"
        freeCompilerArgs = freeCompilerArgs + "-Xcontext-receivers"
    }
}

application {
    mainClass.set("MainKt")
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("com.github.fabien")
            sourceFolders.set(listOf("sqldelight"))
            dialect(libs.sqldelight.postgresql.get())
            deriveSchemaFromMigrations
        }
    }
}