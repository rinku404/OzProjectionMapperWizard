package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.ProjectJson
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer

/**
 * Videos, GIFs and images added on this device to choose from, newest first, saved in [store].
 * Files stay where they are; the platform remembers each one's path by its hash.
 */
class MediaLibrary(private val store: KeyValueStore) {
    private val serializer = ListSerializer(MediaItem.serializer())
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    /** Adds [added] at the front; a file already in the library moves to the front instead of repeating. */
    fun add(added: List<MediaItem>) = change { list ->
        val ids = added.map { it.id }.toSet()
        added.distinctBy { it.id } + list.filterNot { it.id in ids }
    }

    fun remove(id: String) = change { list -> list.filterNot { it.id == id } }

    private fun change(transform: (List<MediaItem>) -> List<MediaItem>) {
        _items.update(transform)
        store.put(KEY, ProjectJson.json.encodeToString(serializer, _items.value))
    }

    private fun load(): List<MediaItem> =
        store.get(KEY)?.let { runCatching { ProjectJson.json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private companion object {
        const val KEY = "library"
    }
}
