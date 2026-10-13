package com.msc.ozprojectionmapperwizard.player

import com.msc.ozprojectionmapperwizard.core.schedule.Schedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerStorageTest {
    private val storage = tempStorage()
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = sha256(bytes)

    @Test
    fun resumesAnUploadInChunks() {
        storage.appendUpload(sha, 0, bytes.copyOfRange(0, 70_000).inputStream())
        assertEquals(70_000L to false, storage.uploadStatus(sha))
        storage.appendUpload(sha, 70_000, bytes.copyOfRange(70_000, bytes.size).inputStream())
        assertTrue(storage.completeUpload(sha))
        assertEquals(bytes.size.toLong() to true, storage.uploadStatus(sha))
    }

    @Test
    fun rejectsAWrongOffset() {
        storage.appendUpload(sha, 0, bytes.copyOfRange(0, 1000).inputStream())
        val e = assertFailsWith<UploadConflict> { storage.appendUpload(sha, 5000, bytes.inputStream()) }
        assertEquals(1000, e.receivedBytes)
    }

    @Test
    fun discardsCorruptUploads() {
        storage.appendUpload(sha, 0, ByteArray(10).inputStream())
        assertFalse(storage.completeUpload(sha))
        assertEquals(0L to false, storage.uploadStatus(sha))
    }

    @Test
    fun installsAndRemovesShows() {
        val fileSha = storage.addFile(bytes)
        storage.installShow(manifest("evening", fileSha))
        assertEquals(listOf("evening"), storage.shows().map { it.id })
        assertTrue(storage.removeShow("evening"))
        assertFalse(storage.file(fileSha).exists())
    }

    @Test
    fun refusesShowsWithoutTheirVideo() {
        assertFailsWith<IllegalArgumentException> { storage.installShow(manifest("x", sha)) }
    }

    @Test
    fun refusesPathTricks() {
        assertFailsWith<IllegalArgumentException> { storage.file("../../etc/passwd") }
        val fileSha = storage.addFile(bytes)
        assertFailsWith<IllegalArgumentException> { storage.installShow(manifest("../x", fileSha)) }
    }

    @Test
    fun savesTheSchedule() {
        val schedule = Schedule(default = "ambient")
        storage.saveSchedule(schedule)
        assertEquals(schedule, PlayerStorage(storage.root).schedule())
    }
}
