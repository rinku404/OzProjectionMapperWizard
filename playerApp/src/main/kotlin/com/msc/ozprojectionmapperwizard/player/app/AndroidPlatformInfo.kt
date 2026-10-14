package com.msc.ozprojectionmapperwizard.player.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import com.msc.ozprojectionmapperwizard.player.PlatformInfo
import com.msc.ozprojectionmapperwizard.player.PlayerStorage
import java.util.UUID

class AndroidPlatformInfo(private val context: Context, storage: PlayerStorage) : PlatformInfo {
    override val deviceId: String = storage.readText(ID_FILE)?.trim()?.takeIf { it.isNotEmpty() }
        ?: UUID.randomUUID().toString().also { storage.writeText(ID_FILE, it) }

    override val name: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    override val model: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    override val platform = "android"
    override val osVersion: String = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    // The physical mode, not the UI size: shows must be rendered at the panel's real resolution.
    private val mode get() = display.mode
    override val displayWidth get() = maxOf(mode.physicalWidth, mode.physicalHeight)
    override val displayHeight get() = minOf(mode.physicalWidth, mode.physicalHeight)
    override val refreshRate get() = mode.refreshRate.toDouble()

    private val display: Display
        get() = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    private companion object {
        const val ID_FILE = "device-id.txt"
    }
}
