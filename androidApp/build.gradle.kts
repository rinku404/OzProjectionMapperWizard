import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

val ciBuildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").map { it.toInt() }.getOrElse(1)
val ozKeystore: String? = providers.environmentVariable("OZ_KEYSTORE_PATH").orNull
val ozKeystorePassword: String? = providers.environmentVariable("OZ_KEYSTORE_PASSWORD").orNull

android {
    namespace = "com.msc.ozprojectionmapperwizard"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.msc.ozprojectionmapperwizard"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // CI's run number, which is also in the release tag, so the app can tell which build is newer.
        versionCode = ciBuildNumber
        versionName = "0.1.$ciBuildNumber"
    }
    signingConfigs {
        // CI signs every build with the same key (from the repo's secrets), so a new build installs
        // over the old one. Without it, builds use the usual per-machine debug key.
        if (ozKeystore != null) {
            getByName("debug") {
                storeFile = file(ozKeystore)
                storePassword = ozKeystorePassword
                keyAlias = "oz"
                keyPassword = ozKeystorePassword
            }
        }
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}