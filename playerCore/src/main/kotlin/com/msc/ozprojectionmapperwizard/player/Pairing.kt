package com.msc.ozprojectionmapperwizard.player

import java.security.SecureRandom

/**
 * PIN pairing: the player projects a 6-digit [pin]; a controller that sends it gets a token that
 * stays valid across reboots. After [MAX_FAILURES] wrong guesses the PIN changes.
 */
class Pairing(private val storage: PlayerStorage, private val random: SecureRandom = SecureRandom()) {
    var pin: String = newPin()
        private set

    private var failures = 0
    private val tokens: MutableSet<String> =
        storage.readText(TOKENS_FILE)?.lines()?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()

    /** Returns a new token, or null when the PIN is wrong. */
    @Synchronized
    fun pair(pin: String): String? {
        if (pin != this.pin) {
            if (++failures >= MAX_FAILURES) rotatePin()
            return null
        }
        val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        tokens += token
        storage.writeText(TOKENS_FILE, tokens.joinToString("\n"))
        rotatePin()
        return token
    }

    fun isAuthorized(token: String?): Boolean = token != null && token in tokens

    @Synchronized
    fun forgetAll() {
        tokens.clear()
        storage.writeText(TOKENS_FILE, "")
    }

    private fun rotatePin() {
        pin = newPin()
        failures = 0
    }

    private fun newPin() = (0 until 6).joinToString("") { random.nextInt(10).toString() }

    companion object {
        const val MAX_FAILURES = 5
        private const val TOKENS_FILE = "tokens.txt"
    }
}
