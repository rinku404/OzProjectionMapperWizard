package com.msc.ozprojectionmapperwizard.core

import com.msc.ozprojectionmapperwizard.core.update.Releases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleasesTest {
    private fun release(tag: String, pre: Boolean, vararg assets: String) = """
        {"tag_name":"$tag","name":"$tag name","prerelease":$pre,"draft":false,"id":1,
         "assets":[${assets.joinToString(",") { """{"name":"$it","size":10,"browser_download_url":"${Releases.DOWNLOAD_PREFIX}$tag/$it"}""" }}]}
    """

    private val releases = Releases.parse(
        "[" + listOf(
            release("player-v0.1.3", pre = true, "oz-player.apk"),
            release("controller-v0.1.12", pre = false, "oz-controller.apk", "oz-controller.msi"),
            release("player-v0.1.10", pre = false, "oz-player.apk"),
            release("player-v0.1.11", pre = true, "oz-player.apk"),
            release("controller-v0.1.13", pre = true, "oz-controller.apk"),
        ).joinToString(",") + "]",
    )

    @Test
    fun picksTheNewestBuildByNumberNotByText() {
        assertEquals(10, Releases.newest(releases, Releases.App.PLAYER, includeTestBuilds = false)!!.build)
        assertEquals(11, Releases.newest(releases, Releases.App.PLAYER, includeTestBuilds = true)!!.build)
    }

    @Test
    fun matchesTheRightFileForEachApp() {
        val msi = Releases.newest(releases, Releases.App.CONTROLLER_WINDOWS, includeTestBuilds = true)!!
        // Build 13 has no installer, so Windows gets 12.
        assertEquals(12, msi.build)
        assertTrue(msi.downloadUrl.endsWith("oz-controller.msi"))
        assertEquals(13, Releases.newest(releases, Releases.App.CONTROLLER_ANDROID, includeTestBuilds = true)!!.build)
    }

    @Test
    fun onlyNewerBuildsAreOffered() {
        assertNull(Releases.available(releases, Releases.App.PLAYER, currentBuild = 10, includeTestBuilds = false))
        assertEquals(10, Releases.available(releases, Releases.App.PLAYER, currentBuild = 3, includeTestBuilds = false)!!.build)
    }

    @Test
    fun onlyThisRepositoryIsTrusted() {
        assertTrue(Releases.isTrustedDownload("${Releases.DOWNLOAD_PREFIX}player-v0.1.10/oz-player.apk"))
        assertFalse(Releases.isTrustedDownload("https://example.com/oz-player.apk"))
        assertFalse(Releases.isTrustedDownload("${Releases.DOWNLOAD_PREFIX}../../evil/x.apk"))
    }
}
