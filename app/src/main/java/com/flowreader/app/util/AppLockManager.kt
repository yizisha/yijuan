package com.flowreader.app.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PIN hashing and the fingerprint prompt for the app lock.
 *
 * The PIN is never stored: [hashPin] returns `salt:sha256(salt + pin)` and [verifyPin] re-derives
 * it. The salt makes a rainbow table useless, and the hash lives in the same DataStore as the rest
 * of the settings. Biometrics are delegated to [BiometricPrompt]; when no biometric is enrolled the
 * lock falls back to the PIN.
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun verifyPin(pin: String, stored: String?): Boolean = Companion.verifyPin(pin, stored)

    fun isPinValid(pin: String): Boolean = Companion.isPinValid(pin)

    fun canUseBiometric(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun promptBiometric(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure(errString.toString())
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(com.flowreader.app.R.string.app_lock_title))
                .setSubtitle(activity.getString(com.flowreader.app.R.string.app_lock_message))
                .setNegativeButtonText(activity.getString(com.flowreader.app.R.string.action_cancel))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()
        )
    }

    companion object {
        private const val SALT_BYTES = 16
        private const val PIN_MIN = 4
        private const val PIN_MAX = 8

        fun isPinValid(pin: String): Boolean = pin.length in PIN_MIN..PIN_MAX && pin.all { it.isDigit() }

        /** Returns `salt:hash`, both hex. */
        fun hashPin(pin: String): String {
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            return "$salt:${digest(salt + pin)}"
        }

        fun verifyPin(pin: String, stored: String?): Boolean {
            val parts = stored?.split(":", limit = 2) ?: return false
            if (parts.size != 2) return false
            return digest(parts[0] + pin) == parts[1]
        }

        private fun digest(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
