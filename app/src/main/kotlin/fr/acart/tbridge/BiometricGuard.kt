package fr.acart.tbridge

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Wrapper sur [BiometricPrompt] pour protéger une action sensible (toggle de
 * réglage de sécurité, régénération du token).
 *
 * Stratégie :
 *  - On demande [BIOMETRIC_STRONG] (empreinte / face).
 *  - Fallback automatique sur [DEVICE_CREDENTIAL] (PIN / pattern / password) si
 *    pas de biométrie enrôlée.
 *  - Si ni l'un ni l'autre disponible → on refuse et on toast l'user.
 */
internal class BiometricGuard(private val activity: FragmentActivity) {

    private val executor = ContextCompat.getMainExecutor(activity)
    private val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun protect(
        title: String,
        subtitle: String? = null,
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {},
    ) {
        val manager = BiometricManager.from(activity)
        when (val status = manager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> showPrompt(title, subtitle, onSuccess, onCancel)
            else -> {
                Toast.makeText(
                    activity,
                    "Authentification indisponible (code $status). Réglage non modifié.",
                    Toast.LENGTH_LONG,
                ).show()
                onCancel()
            }
        }
    }

    private fun showPrompt(
        title: String,
        subtitle: String?,
        onSuccess: () -> Unit,
        onCancel: () -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onCancel()
                }
                // onAuthenticationFailed : l'user a échoué (mauvaise empreinte) → le prompt
                // permet de re-tenter, on ne fait rien ici.
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { subtitle?.let(::setSubtitle) }
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
}
