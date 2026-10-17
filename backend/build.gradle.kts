plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    id("io.ktor.plugin") version "2.3.12"
}

group = "com.saash"
version = "1.0"

application {
    mainClass.set("com.saash.server.MainKt")
}

ktor {
    fatJar {
        archiveFileName.set("server.jar")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    val ktor = "2.3.12"
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("io.ktor:ktor-server-auth-jvm:$ktor")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("ch.qos.logback:logback-classic:1.4.14")
}
