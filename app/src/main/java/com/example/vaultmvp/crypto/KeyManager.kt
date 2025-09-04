package com.example.vaultmvp.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val WRAP_ALIAS = "VaultMVP_WrapKey"

object KeyManager {

    /** Ensures a Keystore AES-256 key that we use to encrypt/decrypt (wrap/unwrap) the content key bytes. */
    fun ensureWrapKey() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(WRAP_ALIAS)) return

        val kpg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            WRAP_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT   // <-- FIX: use ENCRYPT|DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        kpg.init(spec)
        kpg.generateKey()
    }

    /** Create a random AES-256 content key (used for bulk file encryption). */
    fun newContentKey(): SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        return kg.generateKey()
    }

    /** "Wrap" = encrypt the content key’s raw bytes using the Keystore AES-GCM key. */
    fun wrap(contentKey: SecretKey): ByteArray {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val wrapKey = ks.getKey(WRAP_ALIAS, null) as SecretKey

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey)

        // We encrypt the encoded content key bytes; output is ct||tag (standard GCM).
        return cipher.doFinal(contentKey.encoded)
    }

    /** "Unwrap" = decrypt the wrapped bytes back to a raw AES key and return a fast SecretKeySpec. */
    fun unwrap(wrapped: ByteArray): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val wrapKey = ks.getKey(WRAP_ALIAS, null) as SecretKey

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapKey)

        val raw = cipher.doFinal(wrapped) // raw 32 bytes (for AES-256)
        return SecretKeySpec(raw, "AES")
    }
}
