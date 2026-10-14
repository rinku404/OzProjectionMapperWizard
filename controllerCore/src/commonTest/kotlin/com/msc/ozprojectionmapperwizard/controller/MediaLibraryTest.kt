package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaLibraryTest {
    private class MapStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private fun item(id: String) = MediaItem(id, "$id.png", MediaKind.IMAGE, 10, 10)

    @Test
    fun addsNewestFirstWithoutRepeatsAndRemembers() {
        val store = MapStore()
        val library = MediaLibrary(store)
        library.add(listOf(item("a"), item("b")))
        library.add(listOf(item("c"), item("a")))
        assertEquals(listOf("c", "a", "b"), library.items.value.map { it.id })
        library.remove("a")
        assertEquals(listOf("c", "b"), MediaLibrary(store).items.value.map { it.id })
    }
}
