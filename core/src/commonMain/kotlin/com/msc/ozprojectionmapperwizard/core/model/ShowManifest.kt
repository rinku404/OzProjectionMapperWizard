package com.msc.ozprojectionmapperwizard.core.model

import kotlinx.serialization.Serializable

/** Sidecar for a rendered show video, so the player can verify it and the controller can list it. */
@Serializable
data class ShowManifest(
    val id: String,
    val name: String,
    val fileName: String,
    val sha256: String,
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: String = "h264",
    val projectorId: String,
    val projectVersion: Int = Project.CURRENT_VERSION,
)
