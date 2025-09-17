package com.example.vaultmvp.ui.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

private const val TAG = "VaultMVP"

class PdfDoc private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : Closeable {

    val pageCount: Int get() = renderer.pageCount

    suspend fun renderPageToBitmap(
        index: Int,
        targetWidthPx: Int
    ): Bitmap = withContext(Dispatchers.IO) {
        require(index in 0 until pageCount) { "Page $index out of $pageCount" }
        val page = renderer.openPage(index)
        try {
            val w = page.width
            val h = page.height
            // scale height preserving aspect ratio for a given target width
            val dstW = targetWidthPx.coerceAtLeast(1)
            val dstH = (dstW.toFloat() * h / w).toInt().coerceAtLeast(1)

            // IMPORTANT: ARGB_8888 only
            val bmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
            page.render(
                bmp,
                /* destClip = */ null,
                /* transform = */ null,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )
            bmp
        } finally {
            page.close()
        }
    }

    override fun close() {
        try { renderer.close() } catch (_: Throwable) {}
        try { pfd.close() } catch (_: Throwable) {}
    }

    companion object {
        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        fun open(file: File): PdfDoc {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            return PdfDoc(pfd, renderer)
        }
    }
}
