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

    @Test
    fun groupsKeepTheirFilesAndAreRemembered() {
        val store = MapStore()
        val library = MediaLibrary(store)
        library.add(listOf(item("a"), item("b"), item("c")))
        val wall = library.createGroup("Front wall")
        library.addToGroup(wall, listOf("c", "a", "a"))
        library.add(listOf(item("d")), groupId = wall)
        assertEquals(listOf("d", "a", "c"), library.itemsIn(wall).map { it.id })
        assertEquals(4, library.itemsIn(null).size)

        library.remove("a")
        library.removeFromGroup(wall, "d")
        library.renameGroup(wall, "Garage")
        val reopened = MediaLibrary(store)
        assertEquals(listOf("Garage"), reopened.groups.value.map { it.name })
        assertEquals(listOf("c"), reopened.itemsIn(wall).map { it.id })

        reopened.deleteGroup(wall)
        assertEquals(emptyList(), reopened.groups.value)
        assertEquals(3, reopened.items.value.size)
    }
}
