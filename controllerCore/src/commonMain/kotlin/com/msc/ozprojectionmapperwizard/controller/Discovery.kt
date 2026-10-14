package com.msc.ozprojectionmapperwizard.controller

import kotlinx.coroutines.flow.Flow

/** A player found on the local network, before asking it anything. */
data class DiscoveredPlayer(val serviceName: String, val address: PlayerAddress)

/** Finds players advertising [com.msc.ozprojectionmapperwizard.core.protocol.PlayerProtocol.SERVICE_TYPE]. */
fun interface PlayerDiscovery {
    /** The players currently visible; collecting starts the search and cancelling stops it. */
    fun players(): Flow<List<DiscoveredPlayer>>
}
