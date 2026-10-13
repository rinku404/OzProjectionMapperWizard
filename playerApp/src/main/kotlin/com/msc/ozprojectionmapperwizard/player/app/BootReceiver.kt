package com.msc.ozprojectionmapperwizard.player.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Starts the player after the projector boots, so it plays without anyone touching it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlayerService.start(context)
        // Android 10+ only lets a background app open a screen with "display over other apps";
        // otherwise setting the player as the home app does the same job.
        if (Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
