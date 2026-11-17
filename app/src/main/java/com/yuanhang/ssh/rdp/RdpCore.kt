package com.yuanhang.ssh.rdp

/**
 * Constants and byte plumbing for the Remote Desktop Protocol.
 *
 * RDP is a stack of three layers on top of TCP:
 *  1. TPKT (RFC 1006) framing + X.224 connection establishment
 *  2. MCS (T.125) / GCC (T.124) conference setup, which multiplexes the virtual channels
 *  3. The RDP "slow path"/"fast path" PDUs themselves (graphics, input, licensing)
 *
 * Security is negotiated inside the X.224 exchange. This client asks for TLS
 * (PROTOCOL_SSL) and deliberately does *not* offer CredSSP/NLA, so the RC4 key exchange of
 * classic RDP security is never needed – TLS carries the confidentiality instead.
 */
object RdpConstants {

    // ------------------------------------------------------------ X.224 / TPKT

    const val TPKT_VERSION = 3

    /** X.224 Connection Request. */
    const val X224_CR = 0xE0
    /** X.224 Connection Confirm. */
    const val X224_CC = 0xD0
    /** X.224 Data (all MCS traffic is carried in these). */
    const val X224_DT = 0xF0

    /** RDP Negotiation Request / Response type. */
    const val TYPE_RDP_NEG_REQ = 0x01
    const val TYPE_RDP_NEG_RSP = 0x02
    const val TYPE_RDP_NEG_FAILURE = 0x03

    /** Requested/supported security protocols (bit mask). */
    const val PROTOCOL_RDP = 0x00000000
    const val PROTOCOL_SSL = 0x00000001
    const val PROTOCOL_HYBRID = 0x00000002

    /** Negotiation failure codes. */
    const val SSL_REQUIRED_BY_SERVER = 0x00000001
    const val SSL_NOT_ALLOWED_BY_SERVER = 0x00000002
    const val SSL_CERT_NOT_ON_SERVER = 0x00000003
    const val INCONSISTENT_FLAGS = 0x00000004
    const val HYBRID_REQUIRED_BY_SERVER = 0x00000005

    // ------------------------------------------------------------------- MCS

    /** BER application tag for MCS Connect Initial / Connect Response. */
    const val MCS_CONNECT_INITIAL = 0x7F65
    const val MCS_CONNECT_RESPONSE = 0x7F66

    /** MCS domain PDU tags, already shifted into the `<<2` form used on the wire. */
    const val MCS_ERECT_DOMAIN_REQUEST = 0x04
    const val MCS_ATTACH_USER_REQUEST = 0x28
    const val MCS_ATTACH_USER_CONFIRM = 0x2E
    const val MCS_CHANNEL_JOIN_REQUEST = 0x38
    const val MCS_CHANNEL_JOIN_CONFIRM = 0x3E
    const val MCS_SEND_DATA_REQUEST = 0x64
    const val MCS_SEND_DATA_INDICATION = 0x68
    const val MCS_DISCONNECT_PROVIDER_ULTIMATUM = 0x08

    // --------------------------------------------------- client data blocks

    const val CS_CORE = 0xC001
    const val CS_SECURITY = 0xC002
    const val CS_NET = 0xC003
    const val CS_CLUSTER = 0xC004

    /** H221 non-standard keys identifying each client data block. */
    val H221_CS_CORE = byteArrayOf('M'.code.toByte(), 'c'.code.toByte(), 'D'.code.toByte(), 'n'.code.toByte())
    val H221_CS_SEC = byteArrayOf('M'.code.toByte(), 'c'.code.toByte(), 'S'.code.toByte(), 'e'.code.toByte())
    val H221_CS_NET = byteArrayOf('M'.code.toByte(), 'c'.code.toByte(), 'N'.code.toByte(), 'e'.code.toByte())
    val H221_CS_CLUSTER = byteArrayOf('M'.code.toByte(), 'c'.code.toByte(), 'C'.code.toByte(), 'c'.code.toByte())

    /** Client Core Data colour depths. */
    const val RNS_UD_COLOR_4BPP = 0xCA00
    const val RNS_UD_COLOR_8BPP = 0xCA01
    const val RNS_UD_COLOR_16BPP = 0xCA02
    const val RNS_UD_COLOR_15BPP = 0xCA03
    const val RNS_UD_COLOR_24BPP = 0xCA04
    const val RNS_UD_COLOR_32BPP = 0xCA05

    /** supportedColorDepths mask in Client Core Data. */
    const val RNS_UD_24BPP_SUPPORT = 0x0001
    const val RNS_UD_16BPP_SUPPORT = 0x0002
    const val RNS_UD_15BPP_SUPPORT = 0x0004
    const val RNS_UD_32BPP_SUPPORT = 0x0008

    /** earlyCapabilityFlags in Client Core Data. */
    const val RNS_UD_CS_SUPPORT_ERRINFO_PDU = 0x0001
    const val RNS_UD_CS_WANT_32BPP_SESSION = 0x0002
    const val RNS_UD_CS_SUPPORT_STATUS_INFO_PDU = 0x0004
    const val RNS_UD_CS_SUPPORT_MONITOR_LAYOUT_PDU = 0x0040

    /** Client Cluster Data flags. */
    const val REDIRECTED_SESSIONID_FIELD_VALID = 0x00000002
    const val REDIRECTION_SUPPORTED = 0x00000001

    // ---------------------------------------------------------- RDP PDU types

    /** Slow-path PDU types (inside MCS Send Data). */
    const val PDUTYPE_DEMANDACTIVEPDU = 0x1
    const val PDUTYPE_CONFIRMACTIVEPDU = 0x3
    const val PDUTYPE_DEACTIVATEALLPDU = 0x6
    const val PDUTYPE_DATAPDU = 0x7
    const val PDUTYPE_SERVER_REDIR_PKT = 0xA

    /** Data PDU types. */
    const val PDUTYPE2_UPDATE = 0x02
    const val PDUTYPE2_CONTROL = 0x14
    const val PDUTYPE2_POINTER = 0x1B
    const val PDUTYPE2_INPUT = 0x1C
    const val PDUTYPE2_SYNCHRONIZE = 0x1F
    const val PDUTYPE2_REFRESH_RECT = 0x21
    const val PDUTYPE2_PLAY_SOUND = 0x22
    const val PDUTYPE2_SUPPRESS_OUTPUT = 0x23
    const val PDUTYPE2_SHUTDOWN_DENIED = 0x24
    const val PDUTYPE2_FONTLIST = 0x27
    const val PDUTYPE2_FONTMAP = 0x28
    const val PDUTYPE2_SET_KEYBOARD_INDICATORS = 0x29
    const val PDUTYPE2_SET_ERROR_INFO = 0x2F
    const val PDUTYPE2_SET_KEYBOARD_IME_STATUS = 0x31
    const val PDUTYPE2_ARC_STATUS_PDU = 0x32

    /** Update PDU types. */
    const val UPDATETYPE_ORDERS = 0x0000
    const val UPDATETYPE_BITMAP = 0x0001
    const val UPDATETYPE_PALETTE = 0x0002
    const val UPDATETYPE_SYNCHRONIZE = 0x0003

    /** Graphics update flags. */
    const val COMPRESSION_TYPE_MASK = 0x000F
    const val PACKET_COMPRESSED = 0x0020
    const val BITMAP_COMPRESSION = 0x0001
    const val NO_BITMAP_COMPRESSION_HDR = 0x0400

    // --------------------------------------------------------- security flags

    const val SEC_EXCHANGE_PKT = 0x0001
    const val SEC_ENCRYPT = 0x0008
    const val SEC_RESET_SEQNO = 0x0010
    const val SEC_INFO_PKT = 0x0040
    const val SEC_LICENSE_PKT = 0x0080
    const val SEC_LICENSE_ENCRYPT_CS = 0x0200
    const val SEC_LICENSE_ENCRYPT_SC = 0x0200
    const val SEC_REDIRECTION_PKT = 0x0400
    const val SEC_SECURE_CHECKSUM = 0x0800
    const val SEC_AUTODETECT_REQ = 0x1000
    const val SEC_FLAGSHI_VALID = 0x8000

    // --------------------------------------------------------- input events

    const val INPUT_EVENT_SYNC = 0x0000
    const val INPUT_EVENT_UNUSED = 0x0002
    const val INPUT_EVENT_SCANCODE = 0x0004
    const val INPUT_EVENT_UNICODE = 0x0005
    const val INPUT_EVENT_MOUSE = 0x8001
    const val INPUT_EVENT_MOUSEX = 0x8002

    const val PTRFLAGS_DOWN = 0x8000
    const val PTRFLAGS_MOVE = 0x0800
    const val PTRFLAGS_BUTTON1 = 0x1000   // left
    const val PTRFLAGS_BUTTON2 = 0x2000   // right
    const val PTRFLAGS_BUTTON3 = 0x4000   // middle
    const val PTRFLAGS_WHEEL = 0x0200
    const val PTRFLAGS_HWHEEL = 0x0400

    const val KBDFLAGS_EXTENDED = 0x0100
    const val KBDFLAGS_DOWN = 0x0000
    const val KBDFLAGS_RELEASE = 0x8000

    // ------------------------------------------------------- capability sets

    const val CAPSTYPE_GENERAL = 0x0001
    const val CAPSTYPE_BITMAP = 0x0002
    const val CAPSTYPE_ORDER = 0x0003
    const val CAPSTYPE_BITMAPCACHE = 0x0004
    const val CAPSTYPE_CONTROL = 0x0005
    const val CAPSTYPE_ACTIVATION = 0x0007
    const val CAPSTYPE_POINTER = 0x0008
    const val CAPSTYPE_SHARE = 0x0009
    const val CAPSTYPE_COLORTABLECACHE = 0x000A
    const val CAPSTYPE_SOUND = 0x000C
    const val CAPSTYPE_INPUT = 0x000D
    const val CAPSTYPE_FONT = 0x000E
    const val CAPSTYPE_BRUSH = 0x000F
    const val CAPSTYPE_GLYPHCACHE = 0x0010
    const val CAPSTYPE_OFFSCREENCACHE = 0x0011
    const val CAPSTYPE_BITMAPCACHE_HOSTSUPPORT = 0x0012
    const val CAPSTYPE_BITMAPCACHE_REV2 = 0x0013
    const val CAPSTYPE_VIRTUALCHANNEL = 0x0014
    const val CAPSTYPE_DRAWNINEGRIDCACHE = 0x0015
    const val CAPSTYPE_DRAWGDIPLUS = 0x0016
    const val CAPSTYPE_RAIL = 0x0017
    const val CAPSTYPE_WINDOW = 0x0018
    const val CAPSTYPE_COMPDESK = 0x0019
    const val CAPSTYPE_MULTIFRAGMENTUPDATE = 0x001A
    const val CAPSTYPE_LARGE_POINTER = 0x001B
    const val CAPSTYPE_SURFACE_COMMANDS = 0x001C
    const val CAPSTYPE_BITMAP_CODECS = 0x001D
    const val CAPSTYPE_FRAME_ACKNOWLEDGE = 0x001E

    // ------------------------------------------------------------- fastpath

    /** FastPath output action codes. */
    const val FASTPATH_ACTION_FASTPATH = 0x0
    const val FASTPATH_ACTION_X224 = 0x3

    /** Ordered list of channels requested in Client Network Data (only the mandatory ones). */
    val CHANNEL_NAMES = arrayOf("rdpdr", "rdpsnd", "cliprdr", "drdynvc")
}

/**
 * Growable little-endian output buffer.
 *
 * RDP mixes big-endian BER lengths (MCS) with little-endian integers (everything else), so
 * both orders are provided instead of relying on callers to remember.
 */
class RdpOut(initial: Int = 1024) {

    var buf = ByteArray(initial)
        private set
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n <= buf.size) return
        var next = buf.size * 2
        while (next < size + n) next *= 2
        buf = buf.copyOf(next)
    }

    fun u8(v: Int): RdpOut { ensure(1); buf[size++] = v.toByte(); return this }

    fun u16le(v: Int): RdpOut {
        ensure(2)
        buf[size++] = v.toByte()
        buf[size++] = (v shr 8).toByte()
        return this
    }

    fun u16be(v: Int): RdpOut {
        ensure(2)
        buf[size++] = (v shr 8).toByte()
        buf[size++] = v.toByte()
        return this
    }

    fun u32le(v: Int): RdpOut {
        ensure(4)
        buf[size++] = v.toByte()
        buf[size++] = (v shr 8).toByte()
        buf[size++] = (v shr 16).toByte()
        buf[size++] = (v shr 24).toByte()
        return this
    }

    fun u32be(v: Int): RdpOut {
        ensure(4)
        buf[size++] = (v shr 24).toByte()
        buf[size++] = (v shr 16).toByte()
        buf[size++] = (v shr 8).toByte()
        buf[size++] = v.toByte()
        return this
    }

    fun bytes(b: ByteArray, len: Int = b.size): RdpOut {
        ensure(len)
        System.arraycopy(b, 0, buf, size, len)
        size += len
        return this
    }

    /** Reserve `n` zero bytes and return the offset they start at. */
    fun reserve(n: Int): Int {
        ensure(n)
        val at = size
        size += n
        buf.fill(0, at, at + n)
        return at
    }

    /** Advance the write cursor over `n` zero bytes (fixed-width fields left empty). */
    fun skip(n: Int): RdpOut {
        reserve(n.coerceAtLeast(0))
        return this
    }

    /** Overwrite a previously written little-endian short (used for back-patching lengths). */
    fun patchU16le(at: Int, v: Int) {
        buf[at] = v.toByte()
        buf[at + 1] = (v shr 8).toByte()
    }

    /** Overwrite a previously written big-endian 3-byte length. */
    fun patchU24be(at: Int, v: Int) {
        buf[at] = ((v shr 16) and 0xFF).toByte()
        buf[at + 1] = ((v shr 8) and 0xFF).toByte()
        buf[at + 2] = (v and 0xFF).toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/**
 * Cursor over an RDP byte stream.
 *
 * Every read is bounds checked: a truncated or malformed packet from the server returns 0
 * rather than throwing, so a single bad update cannot kill the reader thread.
 */
class RdpIn(private val src: ByteArray, var pos: Int = 0, var limit: Int = src.size) {

    fun remaining(): Int = limit - pos

    fun has(n: Int): Boolean = remaining() >= n

    fun u8(): Int = if (has(1)) src[pos++].toInt() and 0xFF else 0

    fun u16le(): Int = if (has(2)) {
        val v = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        v
    } else 0

    fun u16be(): Int = if (has(2)) {
        val v = ((src[pos].toInt() and 0xFF) shl 8) or (src[pos + 1].toInt() and 0xFF)
        pos += 2
        v
    } else 0

    fun u32le(): Int = if (has(4)) {
        val v = (src[pos].toInt() and 0xFF) or
            ((src[pos + 1].toInt() and 0xFF) shl 8) or
            ((src[pos + 2].toInt() and 0xFF) shl 16) or
            ((src[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        v
    } else 0

    fun s16le(): Int {
        val v = u16le()
        return if (v >= 0x8000) v - 0x10000 else v
    }

    fun skip(n: Int) { pos = minOf(limit, pos + n) }

    fun take(n: Int): ByteArray {
        val k = minOf(n, remaining()).coerceAtLeast(0)
        val out = src.copyOfRange(pos, pos + k)
        pos += k
        return out
    }

    fun offset(): Int = pos
    fun seek(to: Int) { pos = to.coerceIn(0, limit) }
}

/** ASN.1 BER length codec used by the MCS layer. */
object Ber {

    fun writeLength(v: Int, out: RdpOut) {
        when {
            v < 0x80 -> out.u8(v)
            v <= 0xFF -> { out.u8(0x81); out.u8(v) }
            else -> { out.u8(0x82); out.u8((v shr 8) and 0xFF); out.u8(v and 0xFF) }
        }
    }

    fun readLength(inp: RdpIn): Int {
        val first = inp.u8()
        if (first < 0x80) return first
        val count = first and 0x7F
        if (count == 0) return 0
        var v = 0
        repeat(count) { v = (v shl 8) or inp.u8() }
        return v
    }
}
