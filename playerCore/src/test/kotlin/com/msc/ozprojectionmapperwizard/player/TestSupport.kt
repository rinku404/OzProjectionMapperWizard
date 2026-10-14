package com.msc.ozprojectionmapperwizard.player

import com.msc.ozprojectionmapperwizard.core.model.ShowManifest
import java.nio.file.Files
import java.security.MessageDigest

fun tempStorage(): PlayerStorage = PlayerStorage(Files.createTempDirectory("player").toFile())

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun PlayerStorage.addFile(bytes: ByteArray): String {
    val sha = sha256(bytes)
    appendUpload(sha, 0, bytes.inputStream())
    check(completeUpload(sha))
    return sha
}

fun manifest(id: String, sha: String) = ShowManifest(
    id = id, name = id, fileName = "$id.mp4", sha256 = sha, durationSeconds = 30.0,
    width = 1280, height = 720, fps = 30, projectorId = "hy300",
)

object TestPlatform : PlatformInfo {
    override val deviceId = "test-device"
    override val name = "Test projector"
    override val model = "HY300"
    override val platform = "android"
    override val osVersion = "11"
    override val displayWidth = 1280
    override val displayHeight = 720
    override val refreshRate = 60.0
}

