package com.msc.ozprojectionmapperwizard

import com.msc.ozprojectionmapperwizard.controller.KeyValueStore
import com.msc.ozprojectionmapperwizard.controller.PickedVideo
import com.msc.ozprojectionmapperwizard.controller.PlayerDiscovery
import com.msc.ozprojectionmapperwizard.controller.SendProgress
import com.msc.ozprojectionmapperwizard.core.protocol.ClockSync
import com.msc.ozprojectionmapperwizard.core.update.Releases
import com.msc.ozprojectionmapperwizard.core.update.Update

/** What the controller UI needs from the phone or PC it runs on. */
interface ControllerPlatform {
    /** Shown on the projector's list of paired controllers. */
    val deviceName: String
    val store: KeyValueStore
    val discovery: PlayerDiscovery

    /** Lets the person choose a video and hashes it; null if they cancelled. */
    suspend fun pickVideo(): PickedVideo?

    /** This device's current time and time zone, sent to projectors that have no internet. */
    fun clock(): ClockSync

    /** This app's build number (0 when not built by CI). */
    val appBuild: Int

    /** The release file this device installs, or null if it can't update itself. */
    val updateApp: Releases.App?

    /** Downloads [update] and starts installing it; Android then asks for confirmation, Windows runs the installer. */
    suspend fun installUpdate(update: Update, onProgress: (SendProgress) -> Unit)
}
