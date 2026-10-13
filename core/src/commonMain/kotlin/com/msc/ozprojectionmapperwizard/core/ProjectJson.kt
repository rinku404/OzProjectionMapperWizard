package com.msc.ozprojectionmapperwizard.core

import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ShowManifest
import com.msc.ozprojectionmapperwizard.core.schedule.Schedule
import kotlinx.serialization.json.Json

/** JSON used for project.json, schedule.json and show manifests on every platform. */
object ProjectJson {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // Lets an older player read files written by a newer controller.
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun encodeProject(project: Project): String = json.encodeToString(Project.serializer(), project)
    fun decodeProject(text: String): Project = json.decodeFromString(Project.serializer(), text)

    fun encodeSchedule(schedule: Schedule): String = json.encodeToString(Schedule.serializer(), schedule)
    fun decodeSchedule(text: String): Schedule = json.decodeFromString(Schedule.serializer(), text)

    fun encodeManifest(manifest: ShowManifest): String = json.encodeToString(ShowManifest.serializer(), manifest)
    fun decodeManifest(text: String): ShowManifest = json.decodeFromString(ShowManifest.serializer(), text)
}
