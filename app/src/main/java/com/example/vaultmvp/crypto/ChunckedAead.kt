package com.example.vaultmvp.crypto

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.vaultmvp.crypto.ChunkedAeadFormat.IV_LEN
import com.example.vaultmvp.crypto.ChunkedAeadFormat.TAG_LEN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.min

private const val ALG = "AES/GCM/NoPadding"
private const val DEFAULT_CHUNK = 1 * 1024 * 1024 // 1 MiB
private val rng = SecureRandom()

object ChunkedAead {

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun encryptFile(
        plaintext: File,
        outEncrypted: File,
        wrappedKeyOut: (ByteArray) -> Unit = {},
        chunkSize: Int = DEFAULT_CHUNK
    ) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            KeyManager.ensureWrapKey()
        }
        val contentKey = KeyManager.newContentKey()
        val wrappedKey = KeyManager.wrap(contentKey)
        wrappedKeyOut(wrappedKey)

        FileChannel.open(plaintext.toPath(), StandardOpenOption.READ).use { inCh ->
            FileChannel.open(
                outEncrypted.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ).use { outCh ->

                val total = inCh.size()
                val headerLen = ChunkedAeadFormat.headerSize(wrappedKey.size)
                val headerBuf = ByteBuffer.allocate(headerLen)
                ChunkedAeadFormat.putHeader(headerBuf, chunkSize, total, wrappedKey)
                headerBuf.flip()
                while (headerBuf.hasRemaining()) outCh.write(headerBuf)

                val inBuf = ByteBuffer.allocateDirect(chunkSize)
                val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                val iv = ByteArray(IV_LEN)

                var remaining = total
                while (remaining > 0) {
                    inBuf.clear()
                    val toRead = min(remaining, chunkSize.toLong()).toInt()
                    inBuf.limit(toRead)
                    val read = inCh.read(inBuf)
                    if (read <= 0) break
                    inBuf.flip()

                    rng.nextBytes(iv)
                    val cipher = Cipher.getInstance(ALG)
                    val spec = GCMParameterSpec(TAG_LEN * 8, iv)
                    cipher.init(Cipher.ENCRYPT_MODE, contentKey, spec)

                    val plain = inBuf.readExact(read)
                    val out = cipher.doFinal(plain) // ct || tag
                    val ctLen = out.size - TAG_LEN
                    val tag = out.copyOfRange(ctLen, ctLen + TAG_LEN)

                    outCh.write(ByteBuffer.wrap(iv))
                    lenBuf.clear(); lenBuf.putInt(ctLen); lenBuf.flip()
                    outCh.write(lenBuf)
                    outCh.write(ByteBuffer.wrap(out, 0, ctLen))
                    outCh.write(ByteBuffer.wrap(tag))

                    remaining -= read
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun decryptToFile(encrypted: File, outPlain: File) = withContext(Dispatchers.IO) {
        FileChannel.open(encrypted.toPath(), StandardOpenOption.READ).use { inCh ->
            val header = readHeader(inCh)
            val contentKey = KeyManager.unwrap(header.wrappedKey)
            FileChannel.open(
                outPlain.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ).use { outCh ->
                val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                val iv = ByteArray(IV_LEN)
                var produced: Long = 0

                while (produced < header.totalPlaintext) {
                    readFully(inCh, ByteBuffer.wrap(iv))
                    lenBuf.clear(); readFully(inCh, lenBuf.also { it.clear(); it.limit(4) })
                    val ctLen = lenBuf.getInt(0)
                    val ct = ByteArray(ctLen); readFully(inCh, ByteBuffer.wrap(ct))
                    val tag = ByteArray(TAG_LEN); readFully(inCh, ByteBuffer.wrap(tag))

                    val cipher = Cipher.getInstance(ALG)
                    cipher.init(Cipher.DECRYPT_MODE, contentKey, GCMParameterSpec(TAG_LEN * 8, iv))
                    val buf = ByteArray(ctLen + TAG_LEN)
                    System.arraycopy(ct, 0, buf, 0, ctLen)
                    System.arraycopy(tag, 0, buf, ctLen, TAG_LEN)
                    val plain = cipher.doFinal(buf)

                    outCh.write(ByteBuffer.wrap(plain))
                    produced += plain.size
                }
            }
        }
    }

    data class Header(
        val chunkSize: Int,
        val totalPlaintext: Long,
        val wrappedKey: ByteArray,
        val headerLen: Int
    )

    private fun readHeader(ch: FileChannel): Header {
        val fixed = ByteBuffer.allocate(19)
        readFully(ch, fixed.also { it.clear() })
        fixed.flip()
        val magic = fixed.int
        require(magic == ChunkedAeadFormat.MAGIC) { "Bad magic" }
        val ver = fixed.get()
        require(ver == ChunkedAeadFormat.VERSION) { "Bad version" }
        val chunk = fixed.int
        val total = fixed.long
        val wLen = fixed.short.toInt() and 0xFFFF
        val rem = ByteBuffer.allocate(wLen)
        readFully(ch, rem.also { it.clear() })
        val wrapped = ByteArray(wLen).also { rem.flip(); rem.get(it) }
        val headerLen = 19 + wLen
        return Header(chunk, total, wrapped, headerLen)
    }

    private fun readFully(ch: FileChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) {
            val n = ch.read(buf)
            if (n < 0) throw IllegalStateException("Unexpected EOF")
        }
    }

    private fun ByteBuffer.readExact(len: Int): ByteArray {
        return if (hasArray() && arrayOffset() == 0 && position() == 0 && limit() == len) {
            array()
        } else {
            val tmp = ByteArray(len)
            val dup = duplicate()
            dup.get(tmp)
            tmp
        }
    }
}
