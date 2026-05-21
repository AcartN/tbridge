package fr.acart.tbridge

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * Wrapper sur SharedPreferences pour les 2 réglages de tbridge :
 *  - [listenEnabled] : activer ou non la réception des broadcasts adb
 *  - [token] : secret partagé requis dans chaque broadcast (extra `token`)
 *
 * Le token est généré aléatoirement à la première lecture si absent (24 chars alphanum,
 * ~143 bits d'entropie via SecureRandom).
 */
internal class TBridgeSettings(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var listenEnabled: Boolean
        get() = prefs.getBoolean(KEY_LISTEN_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LISTEN_ENABLED, value).apply()

    var shellOnly: Boolean
        get() = prefs.getBoolean(KEY_SHELL_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_SHELL_ONLY, value).apply()

    val token: String
        get() = prefs.getString(KEY_TOKEN, null) ?: regenerateToken()

    fun regenerateToken(): String {
        val newToken = generateRandomToken()
        prefs.edit().putString(KEY_TOKEN, newToken).apply()
        return newToken
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun generateRandomToken(): String {
        val rng = SecureRandom()
        val sb = StringBuilder(TOKEN_LENGTH)
        repeat(TOKEN_LENGTH) {
            sb.append(TOKEN_ALPHABET[rng.nextInt(TOKEN_ALPHABET.size)])
        }
        return sb.toString()
    }

    companion object {
        const val PREFS_NAME: String = "tbridge"
        const val KEY_LISTEN_ENABLED: String = "listen_enabled"
        const val KEY_SHELL_ONLY: String = "shell_only"
        const val KEY_TOKEN: String = "token"
        const val INTENT_EXTRA_TOKEN: String = "token"
        private const val TOKEN_LENGTH = 24
        private val TOKEN_ALPHABET: CharArray =
            (('a'..'z') + ('A'..'Z') + ('0'..'9')).toCharArray()
    }
}
