package com.yuanhang.ssh.vnc

/** RFB message types that flow from client to server. */
internal object ClientMsg {
    const val SET_PIXEL_FORMAT = 0
    const val SET_ENCODINGS = 2
    const val FRAMEBUFFER_UPDATE_REQUEST = 3
    const val KEY_EVENT = 4
    const val POINTER_EVENT = 5
    const val CLIENT_CUT_TEXT = 6
}

/** RFB message types that flow from server to client. */
internal object ServerMsg {
    const val FRAMEBUFFER_UPDATE = 0
    const val SET_COLOUR_MAP_ENTRIES = 1
    const val BELL = 2
    const val SERVER_CUT_TEXT = 3
}

internal object Encoding {
    const val RAW = 0
    const val COPY_RECT = 1
    const val RRE = 2
    const val CORRE = 4
    const val HEXTILE = 5
    const val ZLIB = 6
    const val TIGHT = 7
    const val ZRLE = 16

    // Pseudo encodings
    const val CURSOR = -239
    const val DESKTOP_SIZE = -223
    const val LAST_RECT = -224
    const val CONTINUOUS_UPDATES = -313
    const val JPEG_QUALITY = -32
    const val COMPRESSION_LEVEL = -256
}

/** The pixel format this client always negotiates: 32bpp little endian BGRA. */
internal object PixelFormatRequest {
    val bytes: ByteArray
        get() {
            val b = mutableListOf<Byte>()
            b += 32.toByte()   // bits per pixel
            b += 24.toByte()   // depth
            b += 0.toByte()    // big endian flag = false
            b += 1.toByte()    // true colour = yes
            b += u16(255)      // red max
            b += u16(255)      // green max
            b += u16(255)      // blue max
            b += 16.toByte()   // red shift
            b += 8.toByte()    // green shift
            b += 0.toByte()    // blue shift
            b += 0.toByte(); b += 0.toByte(); b += 0.toByte() // padding
            require(b.size == 16) { "pixel format must be 16 bytes, was ${b.size}" }
            return b.toByteArray()
        }

    private fun u16(v: Int): List<Byte> = listOf((v shr 8).toByte(), v.toByte())
}

/**
 * X11 keysym mappings required for the RFB key event. VNC does not transport raw
 * scan codes – it transports keysyms – so printable characters map directly while
 * everything else needs a table.
 */
object KeySyms {
    const val BACK_SPACE = 0xFF08
    const val TAB = 0xFF09
    const val LINEFEED = 0xFF0A
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val DELETE = 0xFFFF
    const val INSERT = 0xFF63

    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END_ = 0xFF57

    const val SHIFT = 0xFFE1
    const val CONTROL = 0xFFE3
    const val ALT = 0xFFE9
    const val SUPER = 0xFFEB

    val F_KEYS = intArrayOf(
        0xFFBE, 0xFFBF, 0xFFC0, 0xFFC1, 0xFFC2, 0xFFC3,
        0xFFC4, 0xFFC5, 0xFFC6, 0xFFC7, 0xFFC8, 0xFFC9
    )

    /** Map an Android key / character onto a keysym. */
    fun forChar(c: Char): Int = c.code and 0xFFFF

    fun forFunctionKey(index: Int): Int =
        F_KEYS.getOrElse(index.coerceIn(0, F_KEYS.lastIndex)) { RETURN }
}

object Const {
    const val SECURITY_NONE = 1
    const val SECURITY_VNC = 2
    const val SECURITY_RESULT_OK = 0
    const val VERSION = "RFB 003.008\n"
    internal const val TILE_SIZE = 16
}
