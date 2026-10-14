import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// Controller logic shared by the phone and PC apps: talking to players, pairing, uploading shows.
// No UI and no platform APIs; the apps supply discovery, file access and storage.
kotlin {
    jvm()

    android {
        namespace = "com.msc.ozprojectionmapperwizard.controller"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.kotlinx.coroutinesCore)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientCio)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.ktor.serializationKotlinxJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(project(":playerCore"))
        }
    }
}
