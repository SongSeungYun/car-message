plugins {
    id("com.android.application") version "8.9.1"
    id("org.jetbrains.kotlin.android") version "2.1.20"
}

android {
    namespace = "com.hyundaiproject.messaging"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hyundaiproject.messaging"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    // 툴체인(JDK 17 고정) 대신 결과물만 17로: Gradle을 돌리는 JDK가 17이든 21이든 빌드된다
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
