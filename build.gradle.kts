import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.10"
    id("app.cash.sqldelight") version "2.0.0-alpha05"
    application
}

group = "me.fabie"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("app.cash.sqldelight:jdbc-driver:2.0.0-alpha05")
    implementation("com.zaxxer:HikariCP:5.0.1")
    implementation("org.postgresql:postgresql:42.6.0")
    implementation("aws.sdk.kotlin:s3:0.19.5-beta")
    implementation("com.sksamuel.hoplite:hoplite-core:2.7.0")
    implementation("com.sksamuel.hoplite:hoplite-json:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "13"
}

application {
    mainClass.set("MainKt")
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("com.github.fabien")
            sourceFolders.set(listOf("sqldelight"))
            dialect("app.cash.sqldelight:postgresql-dialect:2.0.0-alpha05")
            deriveSchemaFromMigrations
        }
    }
}