package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.ProjectJson
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A named set of library files, such as the images made for one wall. A file can be in several. */
@Serializable
data class MediaGroup(val id: String, val name: String, val mediaIds: List<String> = emptyList())

/**
 * Videos, GIFs and images added on this device to choose from, newest first, saved in [store].
 * Files stay where they are; the platform remembers each one's path by its hash.
 */
class MediaLibrary(private val store: KeyValueStore) {
    private val itemSerializer = ListSerializer(MediaItem.serializer())
    private val groupSerializer = ListSerializer(MediaGroup.serializer())
    private val _items = MutableStateFlow(load(KEY, itemSerializer))
    private val _groups = MutableStateFlow(load(GROUPS_KEY, groupSerializer))
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()
    val groups: StateFlow<List<MediaGroup>> = _groups.asStateFlow()

    /**
     * Adds [added] at the front; a file already in the library moves to the front instead of
     * repeating. With [groupId] they also go into that group.
     */
    fun add(added: List<MediaItem>, groupId: String? = null) {
        change { list ->
            val ids = added.map { it.id }.toSet()
            added.distinctBy { it.id } + list.filterNot { it.id in ids }
        }
        if (groupId != null) addToGroup(groupId, added.map { it.id })
    }

    /** Removes the file from the library and from every group. */
    fun remove(id: String) {
        change { list -> list.filterNot { it.id == id } }
        changeGroups { groups -> groups.map { it.copy(mediaIds = it.mediaIds - id) } }
    }

    /** The files in [groupId] in library order, or the whole library for null. */
    fun itemsIn(groupId: String?): List<MediaItem> {
        val group = groupId?.let { id -> _groups.value.firstOrNull { it.id == id } } ?: return _items.value
        val ids = group.mediaIds.toSet()
        return _items.value.filter { it.id in ids }
    }

    /** Makes a new empty group and returns its id. */
    fun createGroup(name: String): String {
        val n = (1..Int.MAX_VALUE).first { i -> _groups.value.none { it.id == "group-$i" } }
        val id = "group-$n"
        changeGroups { it + MediaGroup(id, name.trim().ifBlank { "Group $n" }) }
        return id
    }

    fun renameGroup(groupId: String, name: String) =
        changeGroups { groups -> groups.map { if (it.id == groupId && name.isNotBlank()) it.copy(name = name.trim()) else it } }

    /** Deletes the group; its files stay in the library. */
    fun deleteGroup(groupId: String) = changeGroups { groups -> groups.filterNot { it.id == groupId } }

    fun addToGroup(groupId: String, mediaIds: List<String>) = changeGroups { groups ->
        groups.map { g -> if (g.id == groupId) g.copy(mediaIds = g.mediaIds + mediaIds.filterNot { it in g.mediaIds }) else g }
    }

    fun removeFromGroup(groupId: String, mediaId: String) = changeGroups { groups ->
        groups.map { if (it.id == groupId) it.copy(mediaIds = it.mediaIds - mediaId) else it }
    }

    private fun change(transform: (List<MediaItem>) -> List<MediaItem>) {
        _items.update(transform)
        store.put(KEY, ProjectJson.json.encodeToString(itemSerializer, _items.value))
    }

    private fun changeGroups(transform: (List<MediaGroup>) -> List<MediaGroup>) {
        _groups.update(transform)
        store.put(GROUPS_KEY, ProjectJson.json.encodeToString(groupSerializer, _groups.value))
    }

    private fun <T> load(key: String, serializer: KSerializer<List<T>>): List<T> =
        store.get(key)?.let { runCatching { ProjectJson.json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private companion object {
        const val KEY = "library"
        const val GROUPS_KEY = "library.groups"
    }
}
