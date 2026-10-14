package com.msc.ozprojectionmapperwizard

import com.msc.ozprojectionmapperwizard.controller.KeyValueStore
import com.msc.ozprojectionmapperwizard.controller.PickedVideo
import com.msc.ozprojectionmapperwizard.controller.PlayerDiscovery
import com.msc.ozprojectionmapperwizard.core.protocol.ClockSync

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
}
