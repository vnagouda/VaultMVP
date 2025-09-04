package com.example.vaultmvp.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * File layout (LE):
 * magic[4] = 'VMVP' (0x50564D56 little-endian)
 * version[1] = 1
 * chunkSizeBytes[4]
 * totalPlaintextSize[8]
 * wrappedKeyLen[2], wrappedKey[wrappedKeyLen]
 * ---
 * For each chunk:
 *   iv[12]
 *   ctLen[4]      (ciphertext length, WITHOUT tag)
 *   ct[ctLen]
 *   tag[16]       (GCM tag)
 */
object ChunkedAeadFormat {
    const val MAGIC = 0x50564D56
    const val VERSION: Byte = 1
    const val IV_LEN = 12
    const val TAG_LEN = 16

    fun headerSize(wrappedKeyLen: Int): Int = 4 + 1 + 4 + 8 + 2 + wrappedKeyLen

    fun putHeader(
        bb: ByteBuffer,
        chunkSizeBytes: Int,
        totalPlaintext: Long,
        wrappedKey: ByteArray
    ) {
        bb.order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(MAGIC)
        bb.put(VERSION)
        bb.putInt(chunkSizeBytes)
        bb.putLong(totalPlaintext)
        bb.putShort(wrappedKey.size.toShort())
        bb.put(wrappedKey)
    }

    data class ParsedHeader(
        val chunkSizeBytes: Int,
        val totalPlaintext: Long,
        val wrappedKey: ByteArray,
        val headerLen: Int
    )

    fun parseHeader(bb: ByteBuffer): ParsedHeader {
        bb.order(ByteOrder.LITTLE_ENDIAN)
        require(bb.int == MAGIC) { "Bad magic" }
        val ver = bb.get()
        require(ver == VERSION) { "Unsupported version $ver" }
        val chunk = bb.int
        val total = bb.long
        val wLen = bb.short.toInt() and 0xFFFF
        val wrapped = ByteArray(wLen)
        bb.get(wrapped)
        val headerLen = 4 + 1 + 4 + 8 + 2 + wLen
        return ParsedHeader(chunk, total, wrapped, headerLen)
    }
}
