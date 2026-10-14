package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.ProjectJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Small persistent settings, kept by each platform in its own way. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** A player this controller has paired with. */
@Serializable
data class KnownPlayer(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
) {
    val address: PlayerAddress get() = PlayerAddress(host, port)
    fun client(): PlayerClient = PlayerClient(address, token)
}

/** The paired players and their tokens, saved in [store]. */
class KnownPlayers(private val store: KeyValueStore) {
    private val serializer = ListSerializer(KnownPlayer.serializer())
    private val _players = MutableStateFlow(load())
    val players: StateFlow<List<KnownPlayer>> = _players.asStateFlow()

    fun byId(deviceId: String): KnownPlayer? = _players.value.firstOrNull { it.deviceId == deviceId }

    /** Adds or replaces the player with the same device id. */
    fun remember(player: KnownPlayer) = change { list -> list.filterNot { it.deviceId == player.deviceId } + player }

    /** Notes a new address for a known player, e.g. after the router handed out another IP. */
    fun moved(deviceId: String, address: PlayerAddress) = change { list ->
        list.map { if (it.deviceId == deviceId) it.copy(host = address.host, port = address.port) else it }
    }

    fun forget(deviceId: String) = change { list -> list.filterNot { it.deviceId == deviceId } }

    /** Asks the player at [address] for a token with the [pin] it shows, and remembers it. */
    suspend fun pair(address: PlayerAddress, pin: String, controllerName: String): KnownPlayer {
        val client = PlayerClient(address)
        val info = client.info()
        val response = client.pair(pin, controllerName)
        val player = KnownPlayer(response.deviceId, info.name, address.host, address.port, response.token)
        remember(player)
        return player
    }

    private fun change(transform: (List<KnownPlayer>) -> List<KnownPlayer>) {
        _players.update(transform)
        store.put(KEY, ProjectJson.json.encodeToString(serializer, _players.value))
    }

    private fun load(): List<KnownPlayer> =
        store.get(KEY)?.let { runCatching { ProjectJson.json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private companion object {
        const val KEY = "players"
    }
}
