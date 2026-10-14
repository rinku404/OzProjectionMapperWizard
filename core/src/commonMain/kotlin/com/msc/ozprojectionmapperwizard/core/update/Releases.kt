package com.msc.ozprojectionmapperwizard.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * App updates come from this repository's GitHub releases. CI tags each build "<app>-v0.1.<build>",
 * where <build> is also the app's versionCode, so comparing build numbers says what is newer.
 * Builds from master are normal releases; builds from other branches are prereleases ("test builds").
 */
object Releases {
    const val REPO = "rinku404/OzProjectionMapperWizard"
    const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=50"
    const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"

    enum class App(val tagPrefix: String, val assetName: String) {
        PLAYER("player-v", "oz-player.apk"),
        CONTROLLER_ANDROID("controller-v", "oz-controller.apk"),
        CONTROLLER_WINDOWS("controller-v", "oz-controller.msi"),
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<GitHubRelease> = json.decodeFromString(ListSerializer(GitHubRelease.serializer()), text)

    /** The newest build of [app], or null if there is none. Test builds only when [includeTestBuilds]. */
    fun newest(releases: List<GitHubRelease>, app: App, includeTestBuilds: Boolean): Update? =
        releases.asSequence()
            .filter { !it.draft && (includeTestBuilds || !it.prerelease) && it.tagName.startsWith(app.tagPrefix) }
            .mapNotNull { release ->
                val build = buildOf(release.tagName) ?: return@mapNotNull null
                val asset = release.assets.firstOrNull { it.name == app.assetName } ?: return@mapNotNull null
                Update(build, release.tagName, release.name ?: release.tagName, release.prerelease, asset.downloadUrl, asset.size)
            }
            .maxByOrNull { it.build }

    /** The newest build if it is newer than [currentBuild]. */
    fun available(releases: List<GitHubRelease>, app: App, currentBuild: Int, includeTestBuilds: Boolean): Update? =
        newest(releases, app, includeTestBuilds)?.takeIf { it.build > currentBuild }

    /** Only downloads from this repository's releases are installed. */
    fun isTrustedDownload(url: String): Boolean = url.startsWith(DOWNLOAD_PREFIX) && ".." !in url

    fun buildOf(tag: String): Int? = tag.substringAfterLast('.').toIntOrNull()
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String,
)

/** A newer build that can be installed. */
@Serializable
data class Update(
    val build: Int,
    val tag: String,
    val name: String,
    val testBuild: Boolean,
    val downloadUrl: String,
    val sizeBytes: Long,
)
