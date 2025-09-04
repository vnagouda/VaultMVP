package com.example.vaultmvp.data.media

import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.example.vaultmvp.util.LOG_TAG
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.min

/**
 * Reads our CHUNKED format directly for ExoPlayer.
 * Record per chunk: iv[12] + ctLen[4] + ct[ctLen] + tag[16]
 * Header:
 *   magic[4]='VMVP', ver[1]=1, chunkSize[4], totalPlain[8]
 */
@UnstableApi
class EncryptedFileDataSource(
    private val file: File,
    private val unwrapKey: () -> javax.crypto.SecretKey // provided by repo
) : BaseDataSource(false) {

    data class Header(val chunkSize: Int, val totalPlain: Long, val headerLen: Int)
    data class ChunkEntry(val pos: Long, val ctLen: Int)

    private lateinit var ch: FileChannel
    private lateinit var header: Header
    private lateinit var key: javax.crypto.SecretKey
    private var opened = false
    private var posPlain = 0L
    private var remaining: Long = -1L

    private val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
    private var table: List<ChunkEntry> = emptyList()

    @RequiresApi(Build.VERSION_CODES.O)
    override fun open(dataSpec: DataSpec): Long {
        check(!opened)
        ch = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        header = readHeader(ch)
        key = unwrapKey()
        buildChunkTable()
        posPlain = dataSpec.position
        remaining = if (dataSpec.length == -1L) header.totalPlain - posPlain else dataSpec.length
        opened = true
        return remaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return -1
        val toRead = if (remaining < 0) length else min(length.toLong(), remaining).toInt()
        if (toRead == 0) return 0

        val n = readDecrypted(posPlain, target, offset, toRead)
        if (n > 0) {
            posPlain += n
            if (remaining > 0) remaining -= n
            bytesTransferred(n)
        }
        return if (n == 0) -1 else n
    }

    override fun close() {
        if (opened) {
            ch.close()
            opened = false
        }
    }

    override fun getUri(): Uri? = Uri.fromFile(file)

    // ---- internals ----

    private fun readHeader(fc: FileChannel): Header {
        val fixed = ByteBuffer.allocate(17) // 4 magic + 1 ver + 4 chunk + 8 total
        readFully(fc, fixed.also { it.clear() })
        fixed.flip()
        val magic = fixed.int
        require(magic == 0x50564D56) { "Bad magic" } // 'VMVP' little endian
        val ver = fixed.get()
        require(ver == 1.toByte()) { "Bad version $ver" }
        val chunk = fixed.int
        val total = fixed.long
        return Header(chunkSize = chunk, totalPlain = total, headerLen = 17)
    }

    private fun buildChunkTable() {
        val entries = ArrayList<ChunkEntry>()
        var p = header.headerLen.toLong()
        var produced = 0L
        while (produced < header.totalPlain) {
            // iv(12), ctLen(4), ct, tag(16)
            ch.position(p + 12)
            lenBuf.clear()
            readFully(ch, lenBuf.also { it.clear(); it.limit(4) })
            val ctLen = lenBuf.getInt(0)
            val recSize = 12 + 4 + ctLen + 16
            entries.add(ChunkEntry(pos = p, ctLen = ctLen))
            p += recSize
            val remain = header.totalPlain - produced
            produced += min(remain, header.chunkSize.toLong())
        }
        table = entries
    }

    private fun readDecrypted(plainPos: Long, out: ByteArray, off: Int, len: Int): Int {
        if (plainPos >= header.totalPlain) return -1
        var remain = len
        var outPos = off
        var p = plainPos
        while (remain > 0 && p < header.totalPlain) {
            val idx = (p / header.chunkSize).toInt()
            val inChunkOffset = (p % header.chunkSize).toInt()
            val chunk = decryptChunk(idx)
            val copy = min(remain, chunk.size - inChunkOffset)
            if (copy <= 0) break
            System.arraycopy(chunk, inChunkOffset, out, outPos, copy)
            outPos += copy
            p += copy
            remain -= copy
        }
        return outPos - off
    }

    private fun decryptChunk(index: Int): ByteArray {
        val e = table[index]
        ch.position(e.pos)
        val iv = ByteArray(12); readFully(ch, ByteBuffer.wrap(iv))
        val ct = ByteArray(e.ctLen); readFully(ch, ByteBuffer.wrap(ct))
        val tag = ByteArray(16); readFully(ch, ByteBuffer.wrap(tag))

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val all = ByteArray(ct.size + 16)
        System.arraycopy(ct, 0, all, 0, ct.size)
        System.arraycopy(tag, 0, all, ct.size, 16)
        return cipher.doFinal(all)
    }

    private fun readFully(fc: FileChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) {
            val n = fc.read(buf)
            if (n < 0) error("EOF")
        }
    }
}
