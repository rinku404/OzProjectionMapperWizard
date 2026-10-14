package com.msc.ozprojectionmapperwizard.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {
    private val storage = tempStorage()

    @Test
    fun tokensSurviveRestart() {
        val pairing = Pairing(storage)
        val token = assertNotNull(pairing.pair(pairing.pin))
        assertTrue(Pairing(storage).isAuthorized(token))
    }

    @Test
    fun pinChangesAfterRepeatedWrongGuesses() {
        val pairing = Pairing(storage)
        val first = pairing.pin
        repeat(Pairing.MAX_FAILURES) { assertNull(pairing.pair("wrong")) }
        assertNotEquals(first, pairing.pin)
        assertFalse(pairing.isAuthorized(null))
    }
}
