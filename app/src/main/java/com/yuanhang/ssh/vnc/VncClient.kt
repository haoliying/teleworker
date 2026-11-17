package com.yuanhang.ssh.vnc

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * A self contained RFB 3.8 client.
 *
 * It deliberately supports the encodings that nearly every real world server emits
 * (Raw, CopyRect, RRE, Hextile, Zlib) rather than chasing Tight/ZRLE; the client always
 * negotiates a 32bpp true colour format so decoding stays uniform.
 */
class VncClient(
    private val host: String,
    private val port: Int,
    private val password: String?,
    private val listener: Listener
) {

    interface Listener {
        fun onFrameInvalidated()
        fun onDesktopSizeChanged(width: Int, height: Int)
        fun onDisconnected(reason: String?)
    }

    @Volatile
    var width = 0
        private set

    @Volatile
    var height = 0
        private set

    @Volatile
    var desktopName: String = ""
        private set

    lateinit var pixels: IntArray
        private set

    /** Reusable ARGB bitmap mirroring [pixels]; recreated only when the desktop resizes. */
    @Volatile
    private var cachedBitmap: android.graphics.Bitmap? = null

    /**
     * Copies the current pixel array into a bitmap suitable for Compose.
     * Callers must be on the UI thread; the read is synchronised against updates so we
     * never observe a half-written frame.
     */
    fun framebuffer(): android.graphics.Bitmap? {
        if (!::pixels.isInitialized || width <= 0 || height <= 0) return null
        synchronized(pixels) {
            cachedBitmap?.let { existing ->
                if (existing.width != width || existing.height != height) {
                    existing.recycle()
                    cachedBitmap = null
                }
            }
            val bmp = cachedBitmap ?: android.graphics.Bitmap
                .createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                .also { cachedBitmap = it }
            bmp.setPixels(pixels, 0, width, 0, 0, width, height)
            return bmp
        }
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    private var inflater: Inflater? = null
    private var zlibStream: InflaterInputStream? = null

    private var readBuffer = ByteArray(64 * 1024)
    private val tileBuffer = ByteArray(Const.TILE_SIZE * Const.TILE_SIZE * 4)

    // ----------------------------------------------------------- lifecycle

    fun connect() {
        worker = Thread(::runSession, "vnc-session").also { it.isDaemon = true; it.start() }
    }

    fun disconnect() {
        running = false
        try { socket?.close() } catch (t: Throwable) { /* ignore */ }
    }

    val isRunning: Boolean get() = running

    // ------------------------------------------------------------ protocol

    private fun runSession() {
        try {
            socket = Socket(host, port).apply {
                tcpNoDelay = true
                soTimeout = 60_000
                receiveBufferSize = 512 * 1024
            }
            input = DataInputStream(socket!!.getInputStream())
            output = socket!!.getOutputStream()
            running = true

            handshake()
            negotiateSecurity()
            readServerInit()
            sendPixelFormat()
            sendEncodings()
            pump()

        } catch (t: Throwable) {
            if (running) {
                listener.onDisconnected(t.message ?: "VNC 连接失败")
            }
        } finally {
            running = false
            closeQuietly()
        }
    }

    private fun handshake() {
        val banner = ByteArray(12)
        readFully(banner)
        val offered = String(banner, Charsets.US_ASCII)
        if (!offered.startsWith("RFB ")) {
            throw IOException("不是有效的 VNC 服务：$offered")
        }
        output!!.write(Const.VERSION.toByteArray(Charsets.US_ASCII))
        output!!.flush()
    }

    private fun negotiateSecurity() {
        val count = input!!.readUnsignedByte()
        if (count == 0) {
            val reasonLen = input!!.readInt()
            val reason = ByteArray(reasonLen)
            readFully(reason)
            throw IOException(String(reason, Charsets.UTF_8))
        }
        val types = ByteArray(count)
        readFully(types)

        val supportsNone = types.any { it == Const.SECURITY_NONE.toByte() }
        val supportsVnc = types.any { it == Const.SECURITY_VNC.toByte() }

        when {
            supportsVnc && !password.isNullOrEmpty() -> {
                output!!.write(Const.SECURITY_VNC)
                output!!.flush()
                val challenge = ByteArray(16)
                readFully(challenge)
                output!!.write(VncAuth.encryptChallenge(challenge, password))
                output!!.flush()
                checkSecurityResult()
            }
            supportsNone -> {
                output!!.write(Const.SECURITY_NONE)
                output!!.flush()
                checkSecurityResult()
            }
            supportsVnc -> throw IOException("该 VNC 服务需要口令")
            else -> throw IOException("服务端不支持可用的认证方式")
        }
    }

    private fun checkSecurityResult() {
        val result = input!!.readInt()
        if (result != Const.SECURITY_RESULT_OK) {
            if (result == 1) {
                val reasonLen = input!!.readInt()
                val reason = ByteArray(reasonLen)
                readFully(reason)
                throw IOException(String(reason, Charsets.UTF_8))
            }
            throw IOException("VNC 认证失败")
        }
    }

    private fun readServerInit() {
        val w = input!!.readUnsignedShort()
        val h = input!!.readUnsignedShort()
        val format = ByteArray(16)
        readFully(format)
        val nameLen = input!!.readInt()
        val name = ByteArray(nameLen)
        readFully(name)

        width = w
        height = h
        desktopName = String(name, Charsets.ISO_8859_1)
        pixels = IntArray(w * h)
        listener.onDesktopSizeChanged(w, h)

        // ClientInit – we are happy to share the desktop.
        output!!.write(1)
        output!!.flush()
    }

    private fun sendPixelFormat() {
        output!!.write(ClientMsg.SET_PIXEL_FORMAT)
        output!!.write(byteArrayOf(0, 0, 0))      // padding
        output!!.write(PixelFormatRequest.bytes)
        output!!.flush()
    }

    private fun sendEncodings() {
        val encodings = intArrayOf(
            Encoding.COPY_RECT,
            Encoding.HEXTILE,
            Encoding.ZLIB,
            Encoding.RRE,
            Encoding.RAW,
            Encoding.DESKTOP_SIZE,
            Encoding.LAST_RECT
        )
        output!!.write(ClientMsg.SET_ENCODINGS)
        output!!.write(0)                          // padding
        output!!.writeShort(encodings.size)
        encodings.forEach { output!!.writeInt(it) }
        output!!.flush()
    }

    private fun pump() {
        while (running) {
            requestUpdate(incremental = true)
            val type = input!!.readUnsignedByte()

            when (type) {
                ServerMsg.FRAMEBUFFER_UPDATE -> handleFramebufferUpdate()
                ServerMsg.BELL -> Unit
                ServerMsg.SERVER_CUT_TEXT -> skipServerCutText()
                ServerMsg.SET_COLOUR_MAP_ENTRIES -> skipColourMap()
                else -> throw IOException("未知的 VNC 消息：$type")
            }
        }
    }

    private fun skipServerCutText() {
        input!!.readInt() // padding + length high bytes
        val len = input!!.readInt()
        var remaining = len
        while (remaining > 0) {
            val n = remaining.coerceAtMost(readBuffer.size)
            readFully(readBuffer, n)
            remaining -= n
        }
    }

    private fun skipColourMap() {
        input!!.readUnsignedShort() // first colour
        val count = input!!.readUnsignedShort()
        repeat(count) {
            input!!.readUnsignedShort()
            input!!.readUnsignedShort()
            input!!.readUnsignedShort()
        }
    }

    fun requestUpdate(incremental: Boolean) {
        val out = output ?: return
        try {
            out.write(ClientMsg.FRAMEBUFFER_UPDATE_REQUEST)
            out.write(if (incremental) 1 else 0)
            out.writeShort(0)  // x
            out.writeShort(0)  // y
            out.writeShort(width)
            out.writeShort(height)
            out.flush()
        } catch (t: Throwable) {
            running = false
        }
    }

    // ---------------------------------------------------------- decoding

    private fun handleFramebufferUpdate() {
        input!!.readUnsignedByte() // padding
        val rectCount = input!!.readUnsignedShort()

        for (i in 0 until rectCount) {
            val x = input!!.readUnsignedShort()
            val y = input!!.readUnsignedShort()
            val w = input!!.readUnsignedShort()
            val h = input!!.readUnsignedShort()
            val encoding = input!!.readInt()

            when (encoding) {
                Encoding.DESKTOP_SIZE -> {
                    handleDesktopSize(w, h)
                    return
                }
                Encoding.LAST_RECT -> return
                Encoding.RAW -> readRaw(x, y, w, h)
                Encoding.COPY_RECT -> readCopyRect(x, y, w, h)
                Encoding.RRE -> readRre(x, y, w, h)
                Encoding.HEXTILE -> readHextile(x, y, w, h)
                Encoding.ZLIB -> readZlib(x, y, w, h)
                Encoding.TIGHT -> throw IOException("不支持 Tight 编码")
                Encoding.ZRLE -> throw IOException("不支持 ZRLE 编码")
                else -> throw IOException("不支持的矩形编码：$encoding")
            }
        }
        listener.onFrameInvalidated()
    }

    private fun handleDesktopSize(w: Int, h: Int) {
        val old = pixels
        width = w
        height = h
        pixels = IntArray(w * h)
        val copyW = w.coerceAtMost(if (old.isEmpty()) 0 else old.size)
        if (copyW > 0) System.arraycopy(old, 0, pixels, 0, copyW.coerceAtMost(old.size))
        listener.onDesktopSizeChanged(w, h)
        listener.onFrameInvalidated()
    }

    /** Fill a rectangle with a single colour (used by several encodings). */
    private fun fillRect(x: Int, y: Int, w: Int, h: Int, colour: Int) {
        val x1 = x.coerceIn(0, width)
        val y1 = y.coerceIn(0, height)
        val x2 = (x + w).coerceIn(0, width)
        val y2 = (y + h).coerceIn(0, height)
        for (row in y1 until y2) {
            val base = row * width
            java.util.Arrays.fill(pixels, base + x1, base + x2, colour)
        }
    }

    private fun copyRegion(srcX: Int, srcY: Int, dstX: Int, dstY: Int, w: Int, h: Int) {
        val tmpW = w.coerceAtMost(width - dstX).coerceAtMost(width - srcX)
        if (tmpW <= 0) return
        for (row in 0 until h) {
            val sy = srcY + row
            val dy = dstY + row
            if (sy !in 0 until height || dy !in 0 until height) continue
            System.arraycopy(pixels, sy * width + srcX, pixels, dy * width + dstX, tmpW)
        }
    }

    /** Read a single pixel value and convert it to ARGB_8888. */
    private fun readPixel(): Int {
        val b0 = input!!.readUnsignedByte()
        val b1 = input!!.readUnsignedByte()
        val b2 = input!!.readUnsignedByte()
        val b3 = input!!.readUnsignedByte()
        // We negotiated big-endian 32bpp with redShift 16 / greenShift 8 / blueShift 0,
        // so the wire order is padding, R, G, B.
        val r = b1
        val g = b2
        val b = b3
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        for (row in 0 until h) {
            val py = y + row
            if (py !in 0 until height) {
                repeat(w * 4) { input!!.readUnsignedByte() }
                continue
            }
            val base = py * width
            for (col in 0 until w) {
                val px = x + col
                if (px in 0 until width) {
                    pixels[base + px] = readPixel()
                } else {
                    repeat(4) { input!!.readUnsignedByte() }
                }
            }
        }
    }

    private fun readCopyRect(x: Int, y: Int, w: Int, h: Int) {
        val srcX = input!!.readUnsignedShort()
        val srcY = input!!.readUnsignedShort()
        copyRegion(srcX, srcY, x, y, w, h)
    }

    private fun readRre(x: Int, y: Int, w: Int, h: Int) {
        val background = readPixel()
        val subRectCount = input!!.readInt()
        fillRect(x, y, w, h, background)
        repeat(subRectCount) {
            val colour = readPixel()
            val sx = input!!.readUnsignedShort()
            val sy = input!!.readUnsignedShort()
            val sw = input!!.readUnsignedShort()
            val sh = input!!.readUnsignedShort()
            fillRect(sx, sy, sw, sh, colour)
        }
    }

    private fun readZlib(x: Int, y: Int, w: Int, h: Int) {
        if (inflater == null) inflater = Inflater()
        else inflater!!.reset()

        val compressedLength = input!!.readInt()
        val compressed = ByteArray(compressedLength)
        readFully(compressed)
        val raw = decompress(compressed, compressedLength, w * h * 4)

        var offset = 0
        for (row in 0 until h) {
            val py = y + row
            for (col in 0 until w) {
                val px = x + col
                val argb = (0xFF shl 24) or
                    ((raw[offset + 1].toInt() and 0xFF) shl 16) or
                    ((raw[offset + 2].toInt() and 0xFF) shl 8) or
                    (raw[offset + 3].toInt() and 0xFF)
                offset += 4
                if (py in 0 until height && px in 0 until width) {
                    pixels[py * width + px] = argb
                }
            }
        }
    }

    /** RFB sends multi-byte integers big endian, which OutputStream has no helper for. */
    private fun OutputStream.writeShort(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun OutputStream.writeInt(value: Int) {
        write((value shr 24) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun decompress(data: ByteArray, length: Int, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(expected.coerceAtLeast(64 * 1024))
        InflaterInputStream(data.inputStream(0, length), inflater, 4096).use { zIn ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = zIn.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun readHextile(x: Int, y: Int, w: Int, h: Int) {
        val tilesX = (w + Const.TILE_SIZE - 1) / Const.TILE_SIZE
        val tilesY = (h + Const.TILE_SIZE - 1) / Const.TILE_SIZE

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val tileX = x + tx * Const.TILE_SIZE
                val tileY = y + ty * Const.TILE_SIZE
                val tileW = Const.TILE_SIZE.coerceAtMost(x + w - tileX)
                val tileH = Const.TILE_SIZE.coerceAtMost(y + h - tileY)

                val subEncoding = input!!.readUnsignedByte()
                if (subEncoding and 1 != 0) {         // raw
                    readRaw(tileX, tileY, tileW, tileH)
                    continue
                }
                val backgroundSpecified = subEncoding and 2 != 0
                val foregroundSpecified = subEncoding and 4 != 0
                val anySubRects = subEncoding and 8 != 0
                val subRectsColoured = subEncoding and 16 != 0

                var background = 0xFF000000.toInt()
                var foreground = 0xFF000000.toInt()

                if (backgroundSpecified) background = readPixel()
                if (foregroundSpecified) foreground = readPixel()
                if (backgroundSpecified || foregroundSpecified || !anySubRects) {
                    fillRect(tileX, tileY, tileW, tileH, background)
                }
                if (!anySubRects) continue

                val count = input!!.readUnsignedByte()
                for (i in 0 until count) {
                    val colour = if (subRectsColoured) readPixel() else foreground
                    val packed = input!!.readUnsignedByte()
                    val sx = (packed shr 4) and 0x0F
                    val sy = packed and 0x0F
                    val sw = input!!.readUnsignedByte() and 0x0F
                    val sh = input!!.readUnsignedByte() and 0x0F
                    val rx = tileX + sx
                    val ry = tileY + sy
                    val rw = sw + 1
                    val rh = sh + 1
                    fillRect(rx, ry, rw.coerceAtMost(tileW - sx), rh.coerceAtMost(tileH - sy), colour)
                }
            }
        }
    }

    // ------------------------------------------------------------- input

    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val out = output ?: return
        try {
            out.write(ClientMsg.POINTER_EVENT)
            out.write(buttonMask)
            out.writeShort(x)
            out.writeShort(y)
            out.flush()
        } catch (t: Throwable) {
            running = false
        }
    }

    fun sendKeyEvent(keysym: Int, pressed: Boolean) {
        val out = output ?: return
        try {
            out.write(ClientMsg.KEY_EVENT)
            out.write(if (pressed) 1 else 0)
            out.write(0); out.write(0)   // padding
            out.writeInt(keysym)
            out.flush()
        } catch (t: Throwable) {
            running = false
        }
    }

    // ------------------------------------------------------------- helpers

    private fun readFully(buffer: ByteArray, length: Int = buffer.size) {
        val input = this.input ?: throw IOException("已断开")
        var off = 0
        while (off < length) {
            val n = input.read(buffer, off, length - off)
            if (n < 0) throw IOException("连接已关闭")
            off += n
        }
    }

    private fun closeQuietly() {
        try { input?.close() } catch (t: Throwable) { /* ignore */ }
        try { output?.close() } catch (t: Throwable) { /* ignore */ }
        try { socket?.close() } catch (t: Throwable) { /* ignore */ }
        inflater?.end()
        inflater = null
    }
}
