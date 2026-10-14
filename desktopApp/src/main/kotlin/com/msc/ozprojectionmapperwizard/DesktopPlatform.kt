package com.msc.ozprojectionmapperwizard

import com.msc.ozprojectionmapperwizard.controller.AppUpdates
import com.msc.ozprojectionmapperwizard.controller.ChunkSource
import com.msc.ozprojectionmapperwizard.controller.DiscoveredPlayer
import com.msc.ozprojectionmapperwizard.controller.KeyValueStore
import com.msc.ozprojectionmapperwizard.controller.PickedVideo
import com.msc.ozprojectionmapperwizard.controller.PlayerAddress
import com.msc.ozprojectionmapperwizard.controller.PlayerDiscovery
import com.msc.ozprojectionmapperwizard.controller.SendProgress
import com.msc.ozprojectionmapperwizard.core.protocol.ClockSync
import com.msc.ozprojectionmapperwizard.core.protocol.PlayerProtocol
import com.msc.ozprojectionmapperwizard.core.update.Releases
import com.msc.ozprojectionmapperwizard.core.update.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Properties
import java.util.TimeZone
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlin.system.exitProcess

class DesktopPlatform : ControllerPlatform {
    override val deviceName: String =
        System.getenv("COMPUTERNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "PC"

    override val store: KeyValueStore = PropertiesStore(File(System.getProperty("user.home"), ".ozprojection/settings.properties"))

    override val discovery = PlayerDiscovery { mdnsPlayers() }

    override suspend fun pickVideo(): PickedVideo? {
        val file = withContext(Dispatchers.Main) {
            val dialog = FileDialog(null as Frame?, "Choose a video", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS }
            dialog.isVisible = true
            dialog.files.firstOrNull()
        } ?: return null
        return withContext(Dispatchers.IO) {
            PickedVideo(file.name, file.length(), sha256(file)) { offset, max ->
                withContext(Dispatchers.IO) { readChunk(file, offset, max) }
            }
        }
    }

    override fun clock() = ClockSync(System.currentTimeMillis(), TimeZone.getDefault().id)

    // Written into the app by the build (CI's run number); 0 when run from the IDE.
    override val appBuild: Int =
        javaClass.getResource("/oz-build.txt")?.readText()?.trim()?.toIntOrNull() ?: 0

    override val updateApp: Releases.App? =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows") && appBuild > 0) Releases.App.CONTROLLER_WINDOWS else null

    /** Downloads the new installer, starts it and closes this app so its files can be replaced. */
    override suspend fun installUpdate(update: Update, onProgress: (SendProgress) -> Unit) {
        withContext(Dispatchers.IO) { downloadAndRun(update, onProgress) }
    }

    private suspend fun downloadAndRun(update: Update, onProgress: (SendProgress) -> Unit) {
        val msi = File(System.getProperty("java.io.tmpdir"), "oz-controller-${update.build}.msi")
        msi.outputStream().buffered().use { out ->
            AppUpdates().download(update.downloadUrl, { bytes, n -> out.write(bytes, 0, n) }, onProgress)
        }
        ProcessBuilder("msiexec", "/i", msi.absolutePath).start()
        delay(1_500)
        exitProcess(0)
    }

    private fun mdnsPlayers(): Flow<List<DiscoveredPlayer>> = callbackFlow {
        val type = PlayerProtocol.SERVICE_TYPE + ".local."
        val found = linkedMapOf<String, DiscoveredPlayer>()
        // JmmDNS listens on every network interface, so it works on PCs with Wi-Fi and Ethernet.
        val mdns = JmmDNS.Factory.getInstance()
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                mdns.requestServiceInfo(event.type, event.name)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                synchronized(found) {
                    found.remove(event.name)
                    trySend(found.values.toList())
                }
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val host = info.inet4Addresses.firstOrNull()?.hostAddress
                    ?: info.inetAddresses.firstOrNull { it !is Inet4Address }?.hostAddress
                    ?: return
                synchronized(found) {
                    found[event.name] = DiscoveredPlayer(event.name, PlayerAddress(host, info.port))
                    trySend(found.values.toList())
                }
            }
        }
        send(emptyList())
        mdns.addServiceListener(type, listener)
        awaitClose {
            mdns.removeServiceListener(type, listener)
            runCatching { mdns.close() }
        }
    }.flowOn(Dispatchers.IO)

    private class PropertiesStore(private val file: File) : KeyValueStore {
        private val properties = Properties().apply {
            if (file.exists()) file.inputStream().use { load(it) }
        }

        @Synchronized
        override fun get(key: String): String? = properties.getProperty(key)

        @Synchronized
        override fun put(key: String, value: String?) {
            if (value == null) properties.remove(key) else properties.setProperty(key, value)
            file.parentFile.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { properties.store(it, "Oz Projection controller") }
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "ts")

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1 shl 20).use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun readChunk(file: File, offset: Long, max: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
            val length = minOf(max.toLong(), raf.length() - offset).coerceAtLeast(0).toInt()
            val bytes = ByteArray(length)
            raf.seek(offset)
            raf.readFully(bytes)
            bytes
        }
    }
}
