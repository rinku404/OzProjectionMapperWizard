package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.update.GitHubRelease
import com.msc.ozprojectionmapperwizard.core.update.Releases
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable

/** Checks this app's GitHub releases and downloads new builds. */
class AppUpdates(private val http: HttpClient = PlayerClient.sharedHttpClient) {
    suspend fun releases(): List<GitHubRelease> {
        val response = http.get(Releases.API_URL) {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/vnd.github+json")
        }
        if (!response.status.isSuccess()) throw PlayerException(response.status.value, "GitHub answered ${response.status.value}. Try again later.")
        return Releases.parse(response.bodyAsText())
    }

    /** Streams [url] into [write], reporting progress. */
    suspend fun download(url: String, write: suspend (ByteArray, Int) -> Unit, onProgress: (SendProgress) -> Unit = {}) {
        require(Releases.isTrustedDownload(url)) { "Updates only come from this app's GitHub releases" }
        http.prepareGet(url) { header("User-Agent", USER_AGENT) }.execute { response ->
            if (!response.status.isSuccess()) throw PlayerException(response.status.value, "Download failed (${response.status.value})")
            val total = response.contentLength() ?: -1L
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            while (true) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n < 0) break
                if (n == 0) continue
                write(buffer, n)
                done += n
                onProgress(SendProgress(done, total))
            }
            if (total >= 0 && done != total) throw PlayerException(0, "The download stopped early. Try again.")
        }
    }

    private companion object {
        const val USER_AGENT = "OzProjection-Controller"
    }
}
