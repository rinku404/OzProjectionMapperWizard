import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

// Player logic shared by every device that drives a projector: the Android player app now,
// a PC or Raspberry Pi player later. Only playback and discovery live in the platform apps.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutinesCore)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverCio)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverWebsockets)
    implementation(libs.ktor.serializationKotlinxJson)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.ktor.serverTestHost)
}
