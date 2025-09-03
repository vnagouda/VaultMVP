package com.example.vaultmvp.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.example.vaultmvp.util.LOG_TAG
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object VaultCrypto {
    private const val KEY_ALIAS = "VaultMvpKey"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val BUF = 256 * 1024

    fun ensureKey(): SecretKey {
        Log.d(LOG_TAG, "Crypto.ensureKey: checking keystore for alias=$KEY_ALIAS")
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            Log.d(LOG_TAG, "Crypto.ensureKey: existing key loaded")
            return it.secretKey
        }

        Log.d(LOG_TAG, "Crypto.ensureKey: generating new AES-GCM key (256-bit)")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey().also { Log.d(LOG_TAG, "Crypto.ensureKey: key generated") }
        }
    }

    // NOTE: This implementation supplies its own IV. Keep IV unique & random.
    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        key: SecretKey,
        totalBytes: Long? = null,
        onProgress: ((Float) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ) {
        Log.d(LOG_TAG, "Crypto.encryptStream: begin; totalBytes=$totalBytes")

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            // Let keystore create a random IV
            init(Cipher.ENCRYPT_MODE, key)
        }
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Unexpected IV: ${iv.size}" }

        // Write IV header
        output.write(iv)

        val inBuf = java.io.BufferedInputStream(input, BUF)
        val outBuf = java.io.BufferedOutputStream(output, BUF)
        val cos = CipherOutputStream(outBuf, cipher)

        val buf = ByteArray(BUF)
        var processed = 0L
        while (true) {
            if (isCancelled?.invoke() == true) throw InterruptedException("Encryption cancelled")
            val read = inBuf.read(buf)
            if (read <= 0) break
            cos.write(buf, 0, read)
            processed += read
            if (totalBytes != null && totalBytes > 0) {
                onProgress?.invoke((processed.toDouble() / totalBytes.toDouble()).toFloat())
            }
        }
        cos.flush()
        cos.close() // finalizes tag
        Log.d(
            LOG_TAG,
            "Crypto.encryptStream: finished; processed=$processed bytes, ivLen=${iv.size}"
        )
    }

    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        key: SecretKey,
    ) {
        Log.d(LOG_TAG, "Crypto.decryptStream: begin")

        val inBuf = java.io.BufferedInputStream(input, BUF)

        // Read IV header
        val iv = ByteArray(IV_BYTES)
        require(inBuf.read(iv) == IV_BYTES) { "Invalid header" }

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }

        val cis = CipherInputStream(inBuf, cipher)
        val outBuf = java.io.BufferedOutputStream(output, BUF)

        val buf = ByteArray(BUF)
        var copied = 0L
        while (true) {
            val read = cis.read(buf)
            if (read <= 0) break
            outBuf.write(buf, 0, read)
            copied += read
        }
        outBuf.flush()
        Log.d(LOG_TAG, "Crypto.decryptStream: finished; copied=$copied bytes, ivLen=${iv.size}")
    }
}