package com.msc.ozprojectionmapperwizard.player.app

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
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
    private var currentOnce = false
    private var lastFiring = -1
    /** Where the looping show was when a trigger interrupted it, so it carries on in time afterwards. */
    private var loopResume: LoopResume? = null

    private class LoopResume(val file: String, val positionMs: Long, val atMs: Long)
    /** Until when the PIN and address show over a playing show, after an arrow key on the remote. */
    private var pairingInfoUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        player.activity = this
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
        // A triggered animation hands back to the schedule when it ends.
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val playback = player.director.playback.value
                if (state == Player.STATE_ENDED && playback.once) playback.nowPlaying.showId?.let(player.director::finished)
            }
        })

        // Newer Android sends Back here instead of to onKeyDown.
        if (Build.VERSION.SDK_INT >= 36) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }

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
        if (player.activity === this) player.activity = null
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
            KeyEvent.KEYCODE_BACK -> if (handleBack()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Back closes a message first; otherwise it leaves the show for the setup screen with the PIN
     * and address, and from there goes back to the show. False when there is nothing to do.
     */
    private fun handleBack(): Boolean {
        val director = player.director
        when {
            pairingInfoUntil > 0 -> {
                pairingInfoUntil = 0
                updateSetupText(director.playback.value)
            }
            updateText.visibility == View.VISIBLE -> {
                offeredUpdate = null
                player.updater.clearStatus()
                updateText.visibility = View.GONE
            }
            director.isHome -> director.resumeSchedule()
            director.playback.value.nowPlaying.reason != PlayReason.NO_SHOW -> director.home()
            else -> return false
        }
        return true
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
                val previous = currentFile
                if (playback.once && !currentOnce && previous != null) {
                    loopResume = LoopResume(previous, exo.currentPosition, SystemClock.elapsedRealtime())
                }
                var startMs = 0L
                val back = loopResume
                if (!playback.once && back != null) {
                    val loopMs = ((playback.show?.durationSeconds ?: 0.0) * 1000).toLong()
                    if (back.file == file.path && loopMs > 0) startMs = (back.positionMs + SystemClock.elapsedRealtime() - back.atMs) % loopMs
                    loopResume = null
                }
                currentFile = file.path
                exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)), startMs)
                exo.prepare()
            } else if (playback.once && playback.firing != lastFiring) {
                // Fired again while playing: start over.
                exo.seekTo(0)
            }
            currentOnce = playback.once
            lastFiring = playback.firing
            exo.repeatMode = if (playback.once) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            exo.playWhenReady = !player.live.isLive
            playerView.visibility = View.VISIBLE
        } else {
            currentFile = null
            currentOnce = false
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
            val waiting = if (player.director.isHome) "Press Back to return to the show" else "Waiting for a show"
            "${getString(R.string.app_name)}\n\nPIN  ${player.pairing.pin}\n$address\n\n$waiting\n\nPress OK on the remote to check for updates"
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
