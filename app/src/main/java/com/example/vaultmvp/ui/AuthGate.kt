// AuthGate.kt
package com.example.vaultmvp.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

fun promptBiometric(
    activity: FragmentActivity,
    title: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)

    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }
            override fun onAuthenticationFailed() {
                // Let the system prompt continue; don't hard-fail here.
            }
        }
    )

    val bm = BiometricManager.from(activity)
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // ✅ API 30+: combine biometric + device credential via allowedAuthenticators
        builder.setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
    } else {
        // ✅ API 28–29: DO NOT use allowedAuthenticators with DEVICE_CREDENTIAL.
        // Use the legacy flag instead (and don't set a negative button).
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }

    // Quick sanity check so we fail gracefully if nothing is available/enrolled
    val canAuth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
    } else {
        bm.canAuthenticate() // legacy check
    }

    if (canAuth == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
        canAuth == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ||
        canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
    ) {
        onError("No biometric or device credential available/enrolled")
        return
    }

    prompt.authenticate(builder.build())
}
