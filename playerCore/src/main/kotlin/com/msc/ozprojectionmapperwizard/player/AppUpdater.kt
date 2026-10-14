package com.msc.ozprojectionmapperwizard.player

import com.msc.ozprojectionmapperwizard.core.update.GitHubRelease
import com.msc.ozprojectionmapperwizard.core.update.Releases
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Installs new builds of the player app; each platform supplies its own installer. */
interface AppUpdater {
    val appBuild: Int

    /** What the update is doing, in words, or null when idle. */
    val status: String?

    /** Starts downloading and installing; returns at once. */
    fun install(downloadUrl: String, build: Int)
}

/** Plain HTTPS helpers for updates, so the player needs no HTTP client library. */
object UpdateDownloads {
    fun fetchReleases(): List<GitHubRelease> {
        val connection = get(Releases.API_URL)
        try {
            return Releases.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads [url] to [target], reporting bytes so far and the total (or -1 if unknown). */
    fun download(url: String, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val connection = get(url)
        val total = connection.contentLengthLong
        val temp = File(target.parentFile, target.name + ".part")
        connection.inputStream.use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (done - lastReport > 256 * 1024) {
                        onProgress(done, total)
                        lastReport = done
                    }
                }
                onProgress(done, total)
            }
        }
        if (total >= 0 && temp.length() != total) throw IOException("Download stopped early")
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw IOException("Couldn't save the download")
        }
    }

    private fun get(url: String): HttpURLConnection {
        // GitHub sends release files from another host; follow the redirect by hand to stay on HTTPS.
        var current = URL(url)
        repeat(5) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "OzProjection-Player")
            connection.setRequestProperty("Accept", "application/vnd.github+json, */*")
            when (val code = connection.responseCode) {
                in 200..299 -> return connection
                301, 302, 303, 307, 308 -> {
                    val next = URL(current, connection.getHeaderField("Location"))
                    connection.disconnect()
                    if (next.protocol != "https") throw IOException("Refusing a non-HTTPS redirect")
                    current = next
                }
                else -> {
                    connection.disconnect()
                    throw IOException("GitHub answered $code")
                }
            }
        }
        throw IOException("Too many redirects")
    }
}
