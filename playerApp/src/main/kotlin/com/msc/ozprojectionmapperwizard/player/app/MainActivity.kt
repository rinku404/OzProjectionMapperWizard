package com.msc.ozprojectionmapperwizard.player.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
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
import com.msc.ozprojectionmapperwizard.player.Playback
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The projected screen: loops the current show, or shows the setup screen with the pairing PIN. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var exo: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var testPattern: View
    private lateinit var setupText: TextView
    private lateinit var liveView: LiveView
    private var currentFile: String? = null

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
    }

    override fun onDestroy() {
        scope.cancel()
        exo.release()
        super.onDestroy()
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
        setupText.visibility = if (setup) View.VISIBLE else View.GONE
        updateSetupText(playback)
    }

    private fun updateSetupText(playback: Playback) {
        if (playback.nowPlaying.reason != PlayReason.NO_SHOW) return
        val address = localAddress()?.let { "http://$it:${PlayerProtocol.DEFAULT_PORT}" } ?: "Not connected to Wi-Fi"
        setupText.text = "${getString(R.string.app_name)}\n\nPIN  ${player.pairing.pin}\n$address\n\nWaiting for a show"
    }

    private fun localAddress(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
}
