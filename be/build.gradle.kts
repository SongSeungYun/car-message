import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("io.ktor.plugin") version "3.6.1"
}

application {
    mainClass.set("carmessage.ApplicationKt")
}

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    implementation("io.ktor:ktor-server-netty:3.6.1")
    implementation("io.ktor:ktor-server-websockets:3.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("ch.qos.logback:logback-classic:1.6.6")

    testImplementation("io.ktor:ktor-server-test-host:3.6.1")
    testImplementation(kotlin("test"))
}
