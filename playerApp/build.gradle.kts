import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":playerCore"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutinesAndroid)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
}

// The app installed on the projector: plays the scheduled show full-screen and serves the
// player API so phones and PCs on the same Wi-Fi can find it and send it shows.
android {
    namespace = "com.msc.ozprojectionmapperwizard.player.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.msc.ozprojectionmapperwizard.player"
        minSdk = libs.versions.player.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
