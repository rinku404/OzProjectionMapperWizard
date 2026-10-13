package com.msc.ozprojectionmapperwizard.player

import com.msc.ozprojectionmapperwizard.core.ProjectJson
import com.msc.ozprojectionmapperwizard.core.model.ShowManifest
import com.msc.ozprojectionmapperwizard.core.schedule.Schedule
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Everything the player keeps on disk, under [root]:
 * - `files/<sha256>`: uploaded media and show videos, named by content hash
 * - `files/<sha256>.part`: an upload in progress
 * - `shows/<id>.json`: installed show manifests
 * - `schedule.json`
 *
 * Writes go to a temporary file first and are renamed into place, so a power cut never leaves a
 * half-written schedule or show behind.
 */
class PlayerStorage(val root: File) {
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val showsDir = File(root, "shows").apply { mkdirs() }
    private val scheduleFile = File(root, "schedule.json")

    fun file(sha256: String): File = File(filesDir, checkHash(sha256))
    private fun partFile(sha256: String) = File(filesDir, "${checkHash(sha256)}.part")

    fun uploadStatus(sha256: String): Pair<Long, Boolean> {
        if (file(sha256).exists()) return file(sha256).length() to true
        return partFile(sha256).let { if (it.exists()) it.length() else 0L } to false
    }

    /** Appends [data] to the upload of [sha256]. [offset] must equal the bytes already received. */
    @Synchronized
    fun appendUpload(sha256: String, offset: Long, data: InputStream): Long {
        val (received, complete) = uploadStatus(sha256)
        if (complete) throw UploadConflict("File is already complete", received)
        if (offset != received) throw UploadConflict("Expected offset $received, got $offset", received)
        RandomAccessFile(partFile(sha256), "rw").use { out ->
            out.seek(offset)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = data.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.length()
        }
    }

    /** Checks the received bytes against their hash and keeps the file. False means start over. */
    @Synchronized
    fun completeUpload(sha256: String): Boolean {
        if (file(sha256).exists()) return true
        val part = partFile(sha256)
        if (!part.exists()) return false
        if (sha256Of(part) != sha256.lowercase()) {
            part.delete()
            return false
        }
        return part.renameTo(file(sha256))
    }

    fun shows(): List<ShowManifest> =
        showsDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { ProjectJson.decodeManifest(it.readText()) }.getOrNull() }
            .sortedBy { it.name }

    fun show(id: String): ShowManifest? = shows().firstOrNull { it.id == id }

    /** Installs a show whose video was already uploaded. */
    fun installShow(manifest: ShowManifest) {
        require(file(manifest.sha256).exists()) { "Upload the video ${manifest.sha256} before installing the show" }
        writeAtomically(File(showsDir, "${checkId(manifest.id)}.json"), ProjectJson.encodeManifest(manifest))
    }

    /** Removes a show and its video, unless another show uses the same video. */
    fun removeShow(id: String): Boolean {
        val manifest = show(id) ?: return false
        File(showsDir, "${checkId(id)}.json").delete()
        if (shows().none { it.sha256 == manifest.sha256 }) file(manifest.sha256).delete()
        return true
    }

    fun schedule(): Schedule =
        if (scheduleFile.exists()) runCatching { ProjectJson.decodeSchedule(scheduleFile.readText()) }.getOrDefault(Schedule())
        else Schedule()

    fun saveSchedule(schedule: Schedule) = writeAtomically(scheduleFile, ProjectJson.encodeSchedule(schedule))

    fun freeBytes(): Long = root.usableSpace

    fun readText(name: String): String? = File(root, name).takeIf { it.exists() }?.readText()
    fun writeText(name: String, text: String) = writeAtomically(File(root, name), text)

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not write ${target.name}" }
        }
    }

    private fun checkHash(sha256: String): String {
        require(HASH.matches(sha256)) { "Not a sha256 hex string: $sha256" }
        return sha256.lowercase()
    }

    private fun checkId(id: String): String {
        require(ID.matches(id)) { "Show ids may only use letters, digits, '-' and '_'" }
        return id
    }

    companion object {
        private val HASH = Regex("[0-9a-fA-F]{64}")
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class UploadConflict(message: String, val receivedBytes: Long) : Exception(message)
