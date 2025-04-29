plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("buildsrc.convention.kotlin-jvm")
    application
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    implementation(project(":utils"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
}

tasks {
    shadowJar {
        archiveFileName.set("MinecraftPlugin.jar")
    }
}

application {
    mainClass = "org.example.app.MainPluginKt"
}

kotlin {
    jvmToolchain(21)
}