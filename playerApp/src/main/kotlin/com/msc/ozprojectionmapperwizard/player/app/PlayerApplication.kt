package com.msc.ozprojectionmapperwizard.player.app

import android.app.Application
import com.msc.ozprojectionmapperwizard.player.LiveSession
import com.msc.ozprojectionmapperwizard.player.Pairing
import com.msc.ozprojectionmapperwizard.player.PlaybackDirector
import com.msc.ozprojectionmapperwizard.player.PlayerClock
import com.msc.ozprojectionmapperwizard.player.PlayerServer
import com.msc.ozprojectionmapperwizard.player.PlayerStorage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Owns the player engine for the whole process; the service and the screen both use it. */
class PlayerApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var storage: PlayerStorage
        private set
    lateinit var pairing: Pairing
        private set
    lateinit var director: PlaybackDirector
        private set
    lateinit var server: PlayerServer
        private set
    val live = LiveSession()

    override fun onCreate() {
        super.onCreate()
        storage = PlayerStorage(File(filesDir, "player"))
        pairing = Pairing(storage)
        val clock = PlayerClock(storage)
        director = PlaybackDirector(storage, clock)
        server = PlayerServer(storage, pairing, clock, director, AndroidPlatformInfo(this, storage), live)
        director.start(scope)
    }
}

val android.content.Context.player get() = applicationContext as PlayerApplication
