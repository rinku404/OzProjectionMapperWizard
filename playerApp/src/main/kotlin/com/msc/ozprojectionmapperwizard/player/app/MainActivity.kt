package com.msc.ozprojectionmapperwizard.player.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.msc.ozprojectionmapperwizard.core.protocol.PlayReason
import com.msc.ozprojectionmapperwizard.core.protocol.PlayerProtocol
import com.msc.ozprojectionmapperwizard.core.update.Releases
import com.msc.ozprojectionmapperwizard.core.update.Update
import com.msc.ozprojectionmapperwizard.player.Playback
import com.msc.ozprojectionmapperwizard.player.UpdateDownloads
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The projected screen: loops the current show, or shows the setup screen with the pairing PIN. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var exo: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var testPattern: View
    private lateinit var setupText: TextView
    private lateinit var liveView: LiveView
    private lateinit var updateText: TextView
    /** A newer build found by pressing OK, waiting for a second OK to install. */
    private var offeredUpdate: Update? = null
    private var updateCheck: Job? = null
    private var hideUpdateText: Job? = null
    private var currentFile: String? = null
    /** Until when the PIN and address show over a playing show, after an arrow key on the remote. */
    private var pairingInfoUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        playerView = findViewById(R.id.player_view)
        testPattern = findViewById(R.id.test_pattern)
        setupText = findViewById(R.id.setup_text)
        liveView = findViewById(R.id.live_view)
        updateText = findViewById(R.id.update_text)
        exo = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
        }
        playerView.player = exo

        PlayerService.start(this)
        scope.launch { player.director.playback.collect { show(it) } }
        // Live alignment covers the show while a controller is connected, then the show carries on.
        scope.launch {
            player.live.state.collect { state ->
                liveView.state = state
                liveView.visibility = if (state != null) View.VISIBLE else View.GONE
                exo.playWhenReady = state == null && currentFile != null
            }
        }
        // The PIN changes after each pairing and the address after a network change.
        scope.launch {
            while (isActive) {
                updateSetupText(player.director.playback.value)
                delay(2_000)
            }
        }
        // Shows update progress, including updates a controller started.
        scope.launch {
            while (isActive) {
                player.updater.status?.let { showUpdateText(it, hideAfterMillis = 0) }
                delay(1_000)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        exo.release()
        super.onDestroy()
    }

    /**
     * OK on the remote checks for a new build; OK again installs it; Back closes the message. The
     * arrows or Menu show the PIN and address over the show, so a controller can pair again.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_GUIDE -> {
                pairingInfoUntil = System.currentTimeMillis() + PAIRING_INFO_MILLIS
                updateSetupText(player.director.playback.value)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                val offer = offeredUpdate
                if (offer != null) {
                    offeredUpdate = null
                    player.updater.install(offer.downloadUrl, offer.build)
                    showUpdateText("Downloading build ${offer.build}…", hideAfterMillis = 0)
                } else if (updateCheck?.isActive != true && player.updater.status == null) {
                    checkForUpdate()
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> if (pairingInfoUntil > 0) {
                pairingInfoUntil = 0
                updateSetupText(player.director.playback.value)
                return true
            } else if (updateText.visibility == View.VISIBLE) {
                offeredUpdate = null
                player.updater.clearStatus()
                updateText.visibility = View.GONE
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun checkForUpdate() {
        val current = player.updater.appBuild
        showUpdateText("Checking for updates…", hideAfterMillis = 0)
        updateCheck = scope.launch {
            try {
                val releases = withContext(Dispatchers.IO) { UpdateDownloads.fetchReleases() }
                val update = Releases.available(releases, Releases.App.PLAYER, current, includeTestBuilds = false)
                if (update == null) {
                    showUpdateText("Oz Player is up to date (build $current).", hideAfterMillis = 5_000)
                } else {
                    offeredUpdate = update
                    showUpdateText("Build ${update.build} is available (this is build $current).\nPress OK to install, Back to cancel.", hideAfterMillis = 0)
                }
            } catch (e: Exception) {
                showUpdateText("Couldn't check for updates. Is the projector on the internet?\n(${e.message})", hideAfterMillis = 8_000)
            }
        }
    }

    private fun showUpdateText(text: String, hideAfterMillis: Long) {
        hideUpdateText?.cancel()
        updateText.text = text
        updateText.visibility = View.VISIBLE
        if (hideAfterMillis > 0) {
            hideUpdateText = scope.launch {
                delay(hideAfterMillis)
                updateText.visibility = View.GONE
            }
        }
    }

    private fun show(playback: Playback) {
        val file = playback.file
        if (file != null && file.exists()) {
            if (file.path != currentFile) {
                currentFile = file.path
                exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                exo.prepare()
            }
            exo.playWhenReady = !player.live.isLive
            playerView.visibility = View.VISIBLE
        } else {
            currentFile = null
            exo.stop()
            exo.clearMediaItems()
            playerView.visibility = View.INVISIBLE
        }
        val setup = playback.nowPlaying.reason == PlayReason.NO_SHOW
        testPattern.visibility = if (setup) View.VISIBLE else View.GONE
        updateSetupText(playback)
    }

    /** The setup screen when there is no show; over a show, the PIN and address for a while after an arrow key. */
    private fun updateSetupText(playback: Playback) {
        val setup = playback.nowPlaying.reason == PlayReason.NO_SHOW
        if (pairingInfoUntil != 0L && System.currentTimeMillis() > pairingInfoUntil) pairingInfoUntil = 0
        val showInfo = !setup && pairingInfoUntil != 0L
        setupText.visibility = if (setup || showInfo) View.VISIBLE else View.GONE
        if (!setup && !showInfo) return
        val address = localAddress()?.let { "http://$it:${PlayerProtocol.DEFAULT_PORT}" } ?: "Not connected to Wi-Fi"
        setupText.text = if (setup) {
            "${getString(R.string.app_name)}\n\nPIN  ${player.pairing.pin}\n$address\n\nWaiting for a show\n\nPress OK on the remote to check for updates"
        } else {
            "PIN  ${player.pairing.pin}\n$address\n\nPair a controller with this PIN\nBack to hide"
        }
    }

    private fun localAddress(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress

    private companion object {
        const val PAIRING_INFO_MILLIS = 120_000L
    }
}
