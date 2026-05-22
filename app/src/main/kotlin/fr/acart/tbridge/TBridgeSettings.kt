package fr.acart.tbridge

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Wrapper sur SharedPreferences pour les réglages tbridge.
 *
 * Sécurité en 3 gates :
 *  1. [isListeningActive] : true seulement si on est dans la fenêtre d'activation
 *     (l'écoute s'auto-désactive après la durée choisie par l'user).
 *  2. [shellOnly] : si true, seul le caller adb shell / root est accepté.
 *  3. [token] : secret partagé requis dans chaque broadcast.
 *
 * Le token est généré aléatoirement à la première lecture si absent (24 chars,
 * ~143 bits d'entropie via SecureRandom).
 */
internal class TBridgeSettings(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Timestamp (ms epoch) jusqu'auquel l'écoute reste active. 0 = jamais. */
    val listenActiveUntilMs: Long
        get() = prefs.getLong(KEY_ACTIVE_UNTIL, 0L)

    /** True si on est dans la fenêtre d'activation. */
    val isListeningActive: Boolean
        get() = listenActiveUntilMs > System.currentTimeMillis()

    /** Active l'écoute pour [durationMs]. Stocke aussi la durée comme last-used. */
    fun activateFor(durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        prefs.edit()
            .putLong(KEY_ACTIVE_UNTIL, until)
            .putLong(KEY_LAST_DURATION, durationMs)
            .apply()
    }

    /** Coupe l'écoute immédiatement. */
    fun deactivate() {
        prefs.edit().putLong(KEY_ACTIVE_UNTIL, 0L).apply()
    }

    /** Dernière durée d'activation choisie (défaut : 1h). */
    var lastUsedDurationMs: Long
        get() = prefs.getLong(KEY_LAST_DURATION, TimeUnit.HOURS.toMillis(1))
        set(value) = prefs.edit().putLong(KEY_LAST_DURATION, value).apply()

    var shellOnly: Boolean
        get() = prefs.getBoolean(KEY_SHELL_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_SHELL_ONLY, value).apply()

    /** Si true (default), le service appelle disableSelf() dès qu'un broadcast est
     *  rejeté pour cause de fenêtre expirée (ou détecte expiry à onServiceConnected).
     *  L'user devra repasser par Paramètres → Accessibilité pour le réactiver. */
    var autoDisableOnExpiry: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DISABLE_ON_EXPIRY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DISABLE_ON_EXPIRY, value).apply()

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

    /** Nettoie une clé orpheline héritée d'un design précédent (boolean toggle remplacé
     *  par la fenêtre time-bounded). À appeler au démarrage de l'app. */
    fun cleanupLegacyKeys() {
        if (prefs.contains("listen_enabled")) {
            prefs.edit().remove("listen_enabled").apply()
        }
    }

    companion object {
        const val PREFS_NAME: String = "tbridge"
        const val KEY_ACTIVE_UNTIL: String = "active_until_ms"
        const val KEY_LAST_DURATION: String = "last_duration_ms"
        const val KEY_SHELL_ONLY: String = "shell_only"
        const val KEY_AUTO_DISABLE_ON_EXPIRY: String = "auto_disable_on_expiry"
        const val KEY_TOKEN: String = "token"
        const val INTENT_EXTRA_TOKEN: String = "token"

        private const val TOKEN_LENGTH = 24
        private val TOKEN_ALPHABET: CharArray =
            (('a'..'z') + ('A'..'Z') + ('0'..'9')).toCharArray()

        /** Presets de durée affichés dans le spinner UI. */
        val DURATION_PRESETS_MS: LongArray = longArrayOf(
            TimeUnit.MINUTES.toMillis(5),
            TimeUnit.MINUTES.toMillis(15),
            TimeUnit.MINUTES.toMillis(30),
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.HOURS.toMillis(2),
            TimeUnit.HOURS.toMillis(4),
        )
    }
}
