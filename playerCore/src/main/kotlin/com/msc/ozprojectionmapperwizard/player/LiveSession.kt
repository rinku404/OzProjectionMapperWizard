package com.msc.ozprojectionmapperwizard.player

import com.msc.ozprojectionmapperwizard.core.protocol.LiveState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live alignment state. While [state] is not null the screen draws it instead of the show.
 * Only the newest controller connection drives it; an older one closing does not end the session.
 */
class LiveSession {
    private val _state = MutableStateFlow<LiveState?>(null)
    val state: StateFlow<LiveState?> = _state.asStateFlow()
    private var owner: Any? = null

    val isLive: Boolean get() = _state.value != null

    @Synchronized
    fun begin(connection: Any) {
        owner = connection
    }

    @Synchronized
    fun update(connection: Any, state: LiveState) {
        if (owner === connection) _state.value = state
    }

    @Synchronized
    fun end(connection: Any) {
        if (owner !== connection) return
        owner = null
        _state.value = null
    }
}
