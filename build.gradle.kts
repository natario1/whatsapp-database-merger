import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.10"
    application
}

group = "dev.natario"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("com.squareup.sqldelight:sqlite-driver:1.5.3")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

application {
    mainClass.set("dev.natario.MainKt")
}