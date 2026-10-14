import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.jmdns)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.msc.ozprojectionmapperwizard.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "OzProjection"
            // CI numbers each build so a newer installer upgrades the old one.
            packageVersion = "1.0.${providers.environmentVariable("GITHUB_RUN_NUMBER").getOrElse("0")}"
            // jmDNS and Ktor need JDK modules beyond Compose's default minimal runtime.
            includeAllModules = true

            windows {
                menu = true
                shortcut = true
                upgradeUuid = "6f1c2b9e-4f0a-4a51-9d7e-0c6a1f3e8b72"
            }
        }
    }
}