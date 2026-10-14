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

// CI's run number, baked into the app so it can tell whether a release is newer.
val ciBuildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").map { it.toInt() }.getOrElse(0)

val writeBuildInfo = tasks.register("writeBuildInfo") {
    val out = layout.buildDirectory.dir("generated/build-info")
    val number = ciBuildNumber
    inputs.property("build", number)
    outputs.dir(out)
    doLast { out.get().file("oz-build.txt").asFile.writeText(number.toString()) }
}
sourceSets["main"].resources.srcDir(writeBuildInfo)

compose.desktop {
    application {
        mainClass = "com.msc.ozprojectionmapperwizard.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "OzProjection"
            // CI numbers each build so a newer installer upgrades the old one.
            packageVersion = "1.0.$ciBuildNumber"
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