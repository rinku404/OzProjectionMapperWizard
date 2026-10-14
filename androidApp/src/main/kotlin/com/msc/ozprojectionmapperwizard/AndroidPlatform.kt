package com.msc.ozprojectionmapperwizard

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.msc.ozprojectionmapperwizard.controller.AppUpdates
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
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.TimeZone
import kotlin.coroutines.resume

/** Create in the activity's onCreate: the file picker has to be registered before the activity starts. */
class AndroidPlatform(private val activity: ComponentActivity) : ControllerPlatform {
    private val context: Context = activity.applicationContext
    private var pending: CompletableDeferred<Uri?>? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pending?.complete(uri)
        pending = null
    }

    override val deviceName: String =
        (if (Build.VERSION.SDK_INT >= 25) Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) else null)
            ?: Build.MODEL

    override val store: KeyValueStore = object : KeyValueStore {
        private val prefs = context.getSharedPreferences("controller", Context.MODE_PRIVATE)
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }

    override val discovery = PlayerDiscovery { nsdPlayers() }

    override suspend fun pickVideo(): PickedVideo? {
        val result = CompletableDeferred<Uri?>()
        pending?.cancel()
        pending = result
        picker.launch(arrayOf("video/*"))
        val uri = result.await() ?: return null
        return withContext(Dispatchers.IO) { describe(uri) }
    }

    override fun clock() = ClockSync(System.currentTimeMillis(), TimeZone.getDefault().id)

    override val appBuild: Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }.getOrDefault(0)

    override val updateApp = Releases.App.CONTROLLER_ANDROID

    // Rendering runs on the PC app for now.
    override val renderer: ShowRenderer? = null

    override suspend fun installUpdate(update: Update, onProgress: (SendProgress) -> Unit) = withContext(Dispatchers.IO) {
        val apk = File(context.cacheDir, "update.apk")
        apk.outputStream().buffered().use { out ->
            AppUpdates().download(update.downloadUrl, { bytes, n -> out.write(bytes, 0, n) }, onProgress)
        }
        ApkInstaller.install(context, apk)
    }

    private fun describe(uri: Uri): PickedVideo {
        val resolver = context.contentResolver
        var name = "video.mp4"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { name = it }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var counted = 0L
        resolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                counted += n
            }
        }
        if (size < 0) size = counted
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return PickedVideo(name, size, sha) { offset, max ->
            withContext(Dispatchers.IO) {
                resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        channel.position(offset)
                        val buffer = ByteBuffer.allocate(max)
                        while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
                        buffer.array().copyOf(buffer.position())
                    }
                }
            }
        }
    }

    private fun nsdPlayers(): Flow<List<DiscoveredPlayer>> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        val found = linkedMapOf<String, DiscoveredPlayer>()
        // Older Android resolves one service at a time, so resolves wait in line.
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) {
                    found.remove(info.serviceName)
                    trySend(found.values.toList())
                }
            }

            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        launch(Dispatchers.IO) {
            for (info in toResolve) {
                val resolved = resolve(nsd, info) ?: continue
                @Suppress("DEPRECATION")
                val host = resolved.host?.hostAddress ?: continue
                synchronized(found) {
                    found[resolved.serviceName] = DiscoveredPlayer(resolved.serviceName, PlayerAddress(host, resolved.port))
                    trySend(found.values.toList())
                }
            }
        }

        send(emptyList())
        nsd.discoverServices(PlayerProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            toResolve.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (cont.isActive) cont.resume(serviceInfo)
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }
        })
    }
}
