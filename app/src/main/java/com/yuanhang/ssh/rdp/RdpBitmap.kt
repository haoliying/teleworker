package com.yuanhang.ssh.rdp

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * The remote desktop framebuffer and the bitmap decoder that feeds it.
 *
 * A note on compression, because it shapes this whole file: RDP can deliver bitmaps RLE
 * compressed (per colour plane, [RdpConstants.BITMAP_COMPRESSION]) or bulk compressed with
 * MPPC ([RdpConstants.PACKET_COMPRESSED]). Both are negotiated in the capability exchange,
 * so [RdpClient] advertises support for neither. That keeps the server on the uncompressed
 * path, which is the only one worth hand-writing here – an off-by-one in a hand-rolled RLE
 * decoder silently corrupts every frame, whereas this decoder is straightforward to verify.
 * Should a server compress anyway, the rectangle is skipped rather than garbled.
 */
class RdpBitmap(width: Int, height: Int) {

    @Volatile
    var width: Int = width.coerceIn(1, 4096)
        private set

    @Volatile
    var height: Int = height.coerceIn(1, 4096)
        private set

    private val pixels = IntArray(this.width * this.height)
    private var bitmap: Bitmap? = null
    private val lock = Any()

    init {
        synchronized(lock) {
            for (i in pixels.indices) pixels[i] = 0xFF1F2430.toInt()
        }
    }

    /**
     * Paint one bitmap rectangle into the framebuffer.
     *
     * RDP rows are stored bottom-up and channel order is BGR(A), while Android wants
     * straight ARGB top-down, so both are flipped here.
     */
    fun update(
        dstLeft: Int,
        dstTop: Int,
        rectWidth: Int,
        rectHeight: Int,
        bitsPerPixel: Int,
        flags: Int,
        data: ByteArray,
        dataOffset: Int = 0,
        dataLength: Int = data.size - dataOffset
    ) {
        if (rectWidth <= 0 || rectHeight <= 0) return
        if (flags and RdpConstants.BITMAP_COMPRESSION != 0) return   // not negotiated
        if (flags and RdpConstants.PACKET_COMPRESSED != 0) return    // not negotiated

        val bytesPerPixel = bitsPerPixel / 8
        if (bytesPerPixel <= 0) return

        synchronized(lock) {
            val src = dataOffset
            for (row in 0 until rectHeight) {
                // Source rows run bottom-up.
                val srcRow = rectHeight - 1 - row
                var s = src + srcRow * rectWidth * bytesPerPixel
                if (s + rectWidth * bytesPerPixel > src + dataLength) continue

                val dstY = dstTop + row
                if (dstY < 0 || dstY >= height) continue

                var d = dstY * width + dstLeft
                for (x in 0 until rectWidth) {
                    val dstX = dstLeft + x
                    if (dstX < 0 || dstX >= width) { s += bytesPerPixel; continue }
                    if (s + bytesPerPixel > data.size) break
                    pixels[d] = pixelAt(data, s, bitsPerPixel)
                    s += bytesPerPixel
                    d++
                }
            }
            bitmap = null    // invalidate; rebuilt lazily by [framebuffer]
        }
    }

    private fun pixelAt(data: ByteArray, at: Int, bpp: Int): Int {
        val b = data[at].toInt() and 0xFF
        val g = if (at + 1 < data.size) data[at + 1].toInt() and 0xFF else 0
        val r = if (at + 2 < data.size) data[at + 2].toInt() and 0xFF else 0
        return when (bpp) {
            8 -> 0xFF000000.toInt() or (b shl 16) or (b shl 8) or b
            15 -> {   // RGB555 packed in two bytes
                val v = b or (g shl 8)
                val rr = (v shr 10) and 0x1F
                val gg = (v shr 5) and 0x1F
                val bb = v and 0x1F
                0xFF000000.toInt() or
                    ((rr * 255 / 31) shl 16) or
                    ((gg * 255 / 31) shl 8) or
                    (bb * 255 / 31)
            }
            16 -> {   // RGB565
                val v = b or (g shl 8)
                val rr = (v shr 11) and 0x1F
                val gg = (v shr 5) and 0x3F
                val bb = v and 0x1F
                0xFF000000.toInt() or
                    ((rr * 255 / 31) shl 16) or
                    ((gg * 255 / 63) shl 8) or
                    (bb * 255 / 31)
            }
            24, 32 -> 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            else -> 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }

    /** Resize the desktop (server driven). Existing content is dropped. */
    fun resize(newWidth: Int, newHeight: Int) {
        val w = newWidth.coerceIn(1, 4096)
        val h = newHeight.coerceIn(1, 4096)
        if (w == width && h == height) return
        synchronized(lock) {
            width = w
            height = h
            bitmap = null
        }
    }

    /**
     * Current picture as an Android bitmap.
     *
     * A fresh bitmap is created only when the pixels actually changed since the last call,
     * so the Compose redraw loop (which runs on every invalidated frame) stays cheap.
     */
    fun framebuffer(): Bitmap {
        synchronized(lock) {
            val existing = bitmap
            if (existing != null && !existing.isRecycled) return existing
            val created = Bitmap.createBitmap(
                pixels, 0, width, width, height, Bitmap.Config.ARGB_8888
            )
            bitmap = created
            return created
        }
    }

    fun fill(color: Int) {
        synchronized(lock) {
            pixels.fill(color)
            bitmap = null
        }
    }

    /** Clamp a coordinate before it is sent as a pointer event. */
    fun clampX(x: Int): Int = min(max(x, 0), width - 1)
    fun clampY(y: Int): Int = min(max(y, 0), height - 1)
}
