package com.msc.ozprojectionmapperwizard.player.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.msc.ozprojectionmapperwizard.player.AppUpdater
import com.msc.ozprojectionmapperwizard.player.UpdateDownloads
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Downloads a new build of the player and hands it to Android's installer, which asks for OK on screen. */
class AndroidUpdater(private val context: Context, private val scope: CoroutineScope) : AppUpdater {
    override val appBuild: Int = runCatching {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()
    }.getOrDefault(0)

    @Volatile
    override var status: String? = null
        internal set

    private var job: Job? = null

    @Synchronized
    override fun install(downloadUrl: String, build: Int) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                status = "Downloading build $build…"
                val apk = File(context.cacheDir, "update.apk")
                UpdateDownloads.download(downloadUrl, apk) { done, total ->
                    status = if (total > 0) "Downloading build $build: ${done * 100 / total}%" else "Downloading build $build…"
                }
                status = "Installing build $build. Press OK on the remote to confirm."
                installApk(context, apk)
            } catch (e: Exception) {
                status = "Update failed: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    fun clearStatus() {
        if (job?.isActive != true) status = null
    }

    companion object {
        /** Streams [apk] into a PackageInstaller session; [InstallResultReceiver] shows the confirmation. */
        fun installApk(context: Context, apk: File) {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("player.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(context, sessionId, Intent(context, InstallResultReceiver::class.java), flags)
                session.commit(callback.intentSender)
            }
        }
    }
}

/** Gets the installer's answers: opens its confirmation screen, or reports why it failed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = context.player.updater
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> updater.status = null
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                updater.status = "Update not installed${message?.let { ": $it" } ?: ""}"
            }
        }
    }
}
