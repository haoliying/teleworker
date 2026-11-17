package com.yuanhang.ssh.rdp

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.nio.charset.Charset

/**
 * A minimal Remote Desktop Protocol client.
 *
 * Scope, deliberately: this drives the connection sequence (X.224 -> MCS/GCC -> capability
 * exchange -> activation) and renders bitmap updates, then sends mouse/keyboard input back.
 * It advertises neither bulk (MPPC) nor bitmap compression and no drawing orders, which
 * steers the server onto plain bitmap updates – the only graphics path implemented here.
 *
 * Security is TLS only. The client requests PROTOCOL_SSL and never offers CredSSP, so a
 * server that insists on Network Level Authentication will refuse the connection. That is a
 * deliberate trade: CredSSP is a full SPNEGO/NTLM stack, and getting it wrong yields a
 * connection that fails with no diagnostic at all.
 */
class RdpClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val domain: String = "",
    private val desktopWidth: Int = 1280,
    private val desktopHeight: Int = 800
) {

    interface Listener {
        fun onFrameInvalidated()
        fun onDesktopSizeChanged(width: Int, height: Int)
        fun onDisconnected(reason: String?)
    }

    var listener: Listener? = null

    @Volatile
    var isConnected = false
        private set

    var width: Int = desktopWidth
        private set
    var height: Int = desktopHeight
        private set

    private val frame = RdpBitmap(desktopWidth, desktopHeight)

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    /** Channel ids handed out by the server during the MCS conference setup. */
    private var userId = 0
    private var ioChannelId = 0

    private var shareId = 0
    private var useTls = false

    private val lock = Any()

    // ------------------------------------------------------------------ connect

    /**
     * Run the whole connection sequence.
     *
     * @throws IOException on a socket failure, or IllegalStateException with a readable
     *                     message when the server refuses (e.g. requires NLA).
     */
    fun connect() {
        val sock = Socket()
        sock.connect(java.net.InetSocketAddress(host, port), 15_000)
        sock.soTimeout = 30_000
        socket = sock
        input = DataInputStream(sock.getInputStream())
        output = sock.getOutputStream()

        try {
            negotiateSecurity()
            sendMcsConnectInitial()
            readMcsConnectResponse()
            erectDomainAndJoinChannels()
            sendClientInfo()
            activate()
            isConnected = true
            startReader()
        } catch (t: Throwable) {
            runCatching { sock.close() }
            isConnected = false
            throw t
        }
    }

    /** X.224 handshake; decides (and applies) the security protocol. */
    private fun negotiateSecurity() {
        val out = RdpOut(64)
        // TPKT
        out.u8(RdpConstants.TPKT_VERSION)
        out.u8(0)
        val tpktLenAt = out.reserve(2)
        // X.224 Connection Request, LI covers everything after it.
        val liAt = out.reserve(1)
        out.u8(RdpConstants.X224_CR)
        out.u16be(0x0000)   // DST-REF
        out.u16be(0x0000)   // SRC-REF
        out.u8(0x00)        // class option
        // RDP Negotiation Request: type, flags, length, requestedProtocols
        out.u8(RdpConstants.TYPE_RDP_NEG_REQ)
        out.u8(0x00)
        out.u16le(0x0008)
        out.u32le(RdpConstants.PROTOCOL_SSL)
        val total = out.size
        out.patchU16le(tpktLenAt, total)
        out.buf[liAt] = (total - 5).toByte()   // LI = bytes after the LI octet
        write(out)

        val response = readTpkt()
        val inp = RdpIn(response)
        inp.u8() // version
        inp.u8() // reserved
        inp.u16be() // length
        val li = inp.u8()
        val code = inp.u8()
        when (code) {
            RdpConstants.X224_CC -> {
                // Skip DST-REF(2), SRC-REF(2), class option(1) then read the optional
                // negotiation response that follows within the LI window.
                inp.skip(5)
                if (li > 6 && inp.has(1)) {
                    val negType = inp.u8()
                    if (negType == RdpConstants.TYPE_RDP_NEG_RSP) {
                        inp.u8()            // flags
                        inp.u16le()         // length
                        val selected = inp.u32le()
                        useTls = selected and RdpConstants.PROTOCOL_SSL != 0
                    } else if (negType == RdpConstants.TYPE_RDP_NEG_FAILURE) {
                        inp.u8()
                        inp.u16le()
                        val code2 = inp.u32le()
                        throw IllegalStateException(
                            when (code2) {
                                RdpConstants.HYBRID_REQUIRED_BY_SERVER ->
                                    "服务器要求网络级别身份验证(NLA)，请关闭后重试"
                                RdpConstants.SSL_REQUIRED_BY_SERVER -> "服务器要求 TLS"
                                else -> "安全协商失败(0x${code2.toString(16)})"
                            }
                        )
                    }
                }
                if (!useTls) {
                    // RDP standard security would need the RC4 key exchange, which is not
                    // implemented; refuse now rather than fail cryptically later.
                    throw IllegalStateException("服务器未选择 TLS，本客户端仅支持 TLS 安全层")
                }
                upgradeToTls()
            }
            else -> throw IllegalStateException("X.224 连接被拒绝(0x${code.toString(16)})")
        }
    }

    /** Wrap the established socket in TLS. RDP servers almost always use self-signed certs. */
    private fun upgradeToTls() {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, java.security.SecureRandom())
        val base = socket ?: throw IOException("socket closed")
        val ssl = ctx.socketFactory.createSocket(base, host, port, true) as SSLSocket
        ssl.startHandshake()
        socket = ssl
        input = DataInputStream(ssl.inputStream)
        output = ssl.outputStream
    }

    // ------------------------------------------------------- MCS / GCC exchange

    /** MCS Connect Initial carrying the (PER encoded) GCC Conference Create Request. */
    private fun sendMcsConnectInitial() {
        val gcc = buildGccConferenceCreateRequest()

        // Build the body first so its BER length can be encoded correctly – the GCC block
        // alone exceeds 127 bytes, so the two-octet form is always required here.
        val body = RdpOut(1024)
        body.u8(0x04); body.u8(0x01); body.u8(0x01)   // callingDomainSelector = "1"
        body.u8(0x04); body.u8(0x01); body.u8(0x01)   // calledDomainSelector  = "1"
        body.u8(0x01); body.u8(0x01); body.u8(0xFF)   // upwardFlag = TRUE

        writeDomainParameters(body, 34, 2, 0, 65535)          // target
        writeDomainParameters(body, 1, 1, 1, 1056)            // minimum
        writeDomainParameters(body, 65535, 65535, 65535, 65535) // maximum

        body.u8(0x04)                      // userData ::= OCTET STRING
        Ber.writeLength(gcc.size, body)
        body.bytes(gcc)

        val out = RdpOut(body.size + 8)
        out.u8(0x7F)   // [APPLICATION 101] Connect Initial, constructed
        out.u8(0x65)
        Ber.writeLength(body.size, out)
        out.bytes(body.toByteArray())
        sendX224Data(out)
    }

    private fun writeDomainParameters(
        out: RdpOut,
        maxChannelIds: Int,
        maxUserIds: Int,
        maxTokenIds: Int,
        maxMcspduSize: Int
    ) {
        val body = RdpOut(64)
        writeBerInteger(body, maxChannelIds)
        writeBerInteger(body, maxUserIds)
        writeBerInteger(body, maxTokenIds)
        writeBerInteger(body, 1)          // numPriorities
        writeBerInteger(body, 0)          // minThroughput
        writeBerInteger(body, 1)          // maxHeight
        writeBerInteger(body, maxMcspduSize)
        writeBerInteger(body, 2)          // protocolVersion
        out.u8(0x30)                      // SEQUENCE
        Ber.writeLength(body.size, out)
        out.bytes(body.toByteArray())
    }

    private fun writeBerInteger(out: RdpOut, value: Int) {
        out.u8(0x02)
        if (value <= 0xFF) {
            Ber.writeLength(1, out)
            out.u8(value)
        } else {
            Ber.writeLength(2, out)
            out.u16be(value)
        }
    }

    /**
     * GCC Conference Create Request, PER encoded.
     *
     * The layout is fixed by the T.124 ASN.1 (and confirmed against the worked example in
     * MS-RDPBCGR): a 12-byte PER preamble ending in the client-to-server H.221 key "Duca",
     * then a PER length, then the concatenated client data blocks. Each block carries its own
     * User Data Header (type + length), so no per-block H.221 keys are used.
     */
    private fun buildGccConferenceCreateRequest(): ByteArray {
        val blocks = RdpOut(512)
        blocks.bytes(buildClientCoreData())
        blocks.bytes(buildClientSecurityData())
        blocks.bytes(buildClientNetworkData())
        blocks.bytes(buildClientClusterData())

        val out = RdpOut(600)
        // PER preamble: ConnectGCCPDU / ConferenceCreateRequest option bits.
        out.bytes(byteArrayOf(0x00, 0x08))
        // ConferenceName: numeric, length 1, value "1"
        out.bytes(byteArrayOf(0x00, 0x10))
        // TerminationMethod automatic + padding
        out.bytes(byteArrayOf(0x00, 0x01))
        // One user data set: value present, key = h221NonStandard, length 4, "Duca"
        out.bytes(byteArrayOf(0xC0.toByte(), 0x00))
        out.bytes(byteArrayOf('D'.code.toByte(), 'u'.code.toByte(), 'c'.code.toByte(), 'a'.code.toByte()))
        writePerLength(out, blocks.size)
        out.bytes(blocks.toByteArray())
        return out.toByteArray()
    }

    private fun writePerLength(out: RdpOut, length: Int) {
        when {
            length <= 0x7F -> out.u8(length)
            length <= 0x3FFF -> {
                out.u8(0x80 or ((length shr 8) and 0xFF))
                out.u8(length and 0xFF)
            }
            else -> {
                out.u8(0xC0 or ((length shr 16) and 0xFF))
                out.u8((length shr 8) and 0xFF)
                out.u8(length and 0xFF)
            }
        }
    }

    private fun buildClientCoreData(): ByteArray {
        val body = RdpOut(256)
        body.u32le(0x00080004)                 // version: RDP 5.0+
        body.u16le(desktopWidth)
        body.u16le(desktopHeight)
        body.u16le(RdpConstants.RNS_UD_COLOR_16BPP)
        body.u16le(0xAA03)                     // SASSeq
        body.u32le(0x00000409)                 // keyboard layout (en-US)
        body.u32le(2600)                       // client build
        val nameBytes = "remoter".toByteArray(Charset.forName("UTF-16LE"))
        body.bytes(nameBytes)
        body.skip(32 - nameBytes.size)         // clientName is fixed at 32 bytes
        body.u32le(4)                          // keyboardType
        body.u32le(0)                          // keyboardSubType
        body.u32le(12)                         // keyboardFunctionKey
        body.skip(64)                          // imeFileName
        // ---- optional fields
        body.u16le(RdpConstants.RNS_UD_COLOR_16BPP)  // postBeta2ColorDepth
        body.u16le(1)                                 // clientProductId
        body.u32le(0)                                 // serialNumber
        body.u16le(16)                                // highColorDepth
        body.u16le(                                   // supportedColorDepths
            RdpConstants.RNS_UD_16BPP_SUPPORT or RdpConstants.RNS_UD_24BPP_SUPPORT
        )
        body.u16le(RdpConstants.RNS_UD_CS_SUPPORT_ERRINFO_PDU)
        body.skip(64)                          // clientDigProductId
        body.u8(0)                             // connectionType
        body.u8(0)                             // pad1octet
        body.u32le(RdpConstants.PROTOCOL_SSL)  // serverSelectedProtocol

        val out = RdpOut(256)
        out.u16le(RdpConstants.CS_CORE)
        out.u16le(4 + body.size)
        out.bytes(body.toByteArray())
        return out.toByteArray()
    }

    private fun buildClientSecurityData(): ByteArray {
        val out = RdpOut(16)
        out.u16le(RdpConstants.CS_SECURITY)
        out.u16le(12)
        out.u32le(RdpConstants.PROTOCOL_SSL)   // encryptionMethods: none selected (TLS)
        out.u32le(0)                           // extEncryptionMethods
        return out.toByteArray()
    }

    /**
     * Client Network Data: only the mandatory I/O channel is requested.
     *
     * Static virtual channels (cliprdr, rdpdr, ...) are deliberately omitted – each would
     * need a virtual-channel implementation behind it, and requesting one that never answers
     * leaves the server waiting.
     */
    private fun buildClientNetworkData(): ByteArray {
        val out = RdpOut(16)
        out.u16le(RdpConstants.CS_NET)
        out.u16le(8)
        out.u16le(0)   // MCSChannelId: filled by the server, zero here
        out.u16le(0)   // channelCount
        return out.toByteArray()
    }

    private fun buildClientClusterData(): ByteArray {
        val out = RdpOut(16)
        out.u16le(RdpConstants.CS_CLUSTER)
        out.u16le(12)
        out.u32le(0)   // flags: no redirection
        out.u32le(0)   // redirectedSessionId
        return out.toByteArray()
    }

    /** Parse the server's Connect Response, mainly to learn the I/O channel id. */
    private fun readMcsConnectResponse() {
        val payload = readMcsPayload()
        val inp = RdpIn(payload)
        val tag = inp.u16be()
        if (tag != RdpConstants.MCS_CONNECT_RESPONSE) {
            throw IllegalStateException("MCS Connect Response 标记错误: 0x${tag.toString(16)}")
        }
        Ber.readLength(inp)
        // result, calledConnectId, domainParameters, then userData (OCTET STRING).
        inp.skip(1)                      // result (0 = success)
        Ber.readLength(inp)              // calledConnectId
        // domainParameters is a SEQUENCE of 8 integers; skip it by its BER length.
        if (inp.u8() == 0x30) {
            val len = Ber.readLength(inp)
            inp.skip(len)
        }
        if (inp.u8() != 0x04) return     // no userData
        val userDataLen = Ber.readLength(inp)
        val gcc = inp.take(userDataLen)
        parseServerNetworkData(gcc)
    }

    /**
     * Server Core/Security/Network data blocks are PER encoded. Only Network Data matters
     * here: it assigns the I/O channel id everything else is sent over.
     */
    private fun parseServerNetworkData(gcc: ByteArray) {
        // Skip the PER preamble the same way the request is built, then look for the
        // server blocks. The blocks are T.124 user data with a 4-byte User Data Header
        // (type + length), so they can be located by scanning for the SC_NET type.
        var i = 0
        while (i + 4 <= gcc.size) {
            val type = (gcc[i].toInt() and 0xFF) or ((gcc[i + 1].toInt() and 0xFF) shl 8)
            val len = (gcc[i + 2].toInt() and 0xFF) or ((gcc[i + 3].toInt() and 0xFF) shl 8)
            if (type == SC_NET && len >= 8 && i + 4 + len <= gcc.size) {
                val p = i + 4
                ioChannelId = (gcc[p].toInt() and 0xFF) or ((gcc[p + 1].toInt() and 0xFF) shl 8)
                return
            }
            if (len < 4) break
            i += len
        }
        // Fall back to the default I/O channel if the block was not located.
        if (ioChannelId == 0) ioChannelId = 1003
    }

    private val SC_CORE = 0x0C01
    private val SC_SECURITY = 0x0C02
    private val SC_NET = 0x0C03

    // ------------------------------------------------------------ MCS domain PDUs

    private fun erectDomainAndJoinChannels() {
        // Erect Domain Request: subHeight/subInterval = 1.
        val erect = RdpOut(8)
        erect.u8(RdpConstants.MCS_ERECT_DOMAIN_REQUEST)
        erect.u8(0x01)
        erect.u8(0x00)
        erect.u8(0x01)
        erect.u8(0x01)
        sendX224Data(erect)

        // Attach User Request -> Confirm yields our user id.
        val attach = RdpOut(4)
        attach.u8(RdpConstants.MCS_ATTACH_USER_REQUEST)
        attach.u8(0x00)
        sendX224Data(attach)

        val confirm = readMcsPayload()
        val ci = RdpIn(confirm)
        val tag = ci.u8()
        if (tag != RdpConstants.MCS_ATTACH_USER_CONFIRM) {
            throw IllegalStateException("MCS Attach User 失败: 0x${tag.toString(16)}}")
        }
        ci.u8()
        userId = ci.u16be()

        // Join the user channel and the I/O channel.
        joinChannel(userId)
        if (ioChannelId != userId) joinChannel(ioChannelId)
    }

    private fun joinChannel(channelId: Int) {
        val out = RdpOut(8)
        out.u8(RdpConstants.MCS_CHANNEL_JOIN_REQUEST)
        out.u8(0x00)
        out.u16be(userId)
        out.u16be(channelId)
        sendX224Data(out)

        val reply = readMcsPayload()
        val inp = RdpIn(reply)
        val tag = inp.u8()
        if (tag != RdpConstants.MCS_CHANNEL_JOIN_CONFIRM) {
            throw IllegalStateException("加入通道 $channelId 失败")
        }
        inp.skip(4)
        inp.u16be()   // confirmed channel id
    }

    // --------------------------------------------------------------- activation

    /** Client Info PDU: the credentials, sent unencrypted because TLS already covers it. */
    private fun sendClientInfo() {
        val body = RdpOut(512)
        body.u32le(0)   // codePage, unused when the flag is not set
        body.u32le(
            INFO_MOUSE or INFO_UNICODE or INFO_LOGONERRORS or INFO_LOGONNOTIFY or
                INFO_ENABLEWINDOWSKEY or INFO_MAXIMIZESHELL or INFO_REMOTECONSOLEAUDIO
        )
        appendNullTerminatedUtf16(body, domain)
        appendNullTerminatedUtf16(body, username)
        appendNullTerminatedUtf16(body, password)
        appendNullTerminatedUtf16(body, "")   // alternateShell
        appendNullTerminatedUtf16(body, "")   // workingDir

        // Optional: client address and directory follow; omit both.
        val out = RdpOut(600)
        out.u16le(RdpConstants.SEC_INFO_PKT)
        out.u16le(0)
        out.bytes(body.toByteArray())
        sendSlowPath(out)
    }

    private fun appendNullTerminatedUtf16(out: RdpOut, value: String) {
        val bytes = value.toByteArray(Charset.forName("UTF-16LE"))
        out.bytes(bytes)
        out.u16le(0)
    }

    /** Wait for Demand Active, answer with Confirm Active, then synchronise and grab control. */
    private fun activate() {
        val demand = waitForDemandActive()
        sendConfirmActive(demand)
        sendSynchronize()
        sendControlCooperate()
        sendControlRequestControl()
        // Font list is the traditional "go" that makes the server start painting.
        sendFontList()
    }

    /**
     * Consume PDUs until the server demands activation.
     *
     * Licensing PDUs can arrive before activation, so anything that is not a Demand Active
     * is skipped rather than treated as an error.
     */
    private fun waitForDemandActive(): ByteArray {
        repeat(20) {
            val payload = readSlowPathPayload()
            if (payload.isEmpty()) return@repeat
            val inp = RdpIn(payload)
            inp.u16le()                // security flags
            inp.u16le()                // security flagsHi
            val len = inp.u16le()      // shareControlHeader totalLength
            val type = inp.u16le()
            inp.u16le()                // pduSource
            if (type != RdpConstants.PDUTYPE_DEMANDACTIVEPDU) return@repeat

            val at = inp.offset()
            if (at + 4 <= payload.size) {
                shareId = (payload[at].toInt() and 0xFF) or
                    ((payload[at + 1].toInt() and 0xFF) shl 8) or
                    ((payload[at + 2].toInt() and 0xFF) shl 16) or
                    ((payload[at + 3].toInt() and 0xFF) shl 24)
            }
            val rest = ByteArray((len - 6).coerceAtLeast(0))
            val copyLen = minOf(rest.size, (payload.size - at).coerceAtLeast(0))
            System.arraycopy(payload, at, rest, 0, copyLen)
            return rest
        }
        throw IllegalStateException("未收到 Demand Active PDU")
    }

    /** Confirm Active: echo the share id and declare a deliberately small capability set. */
    private fun sendConfirmActive(demand: ByteArray) {
        val caps = buildCapabilitySets()
        val sourceDescriptor = byteArrayOf(
            'r'.code.toByte(), 'e'.code.toByte(), 'm'.code.toByte(), 'o'.code.toByte(),
            't'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte(), 0
        )

        val body = RdpOut(1024)
        body.u32le(shareId)
        body.u16le(0x03EA)                       // originatorId (client)
        body.u16le(sourceDescriptor.size)
        body.u16le(caps.size)
        body.bytes(sourceDescriptor)
        body.u16le(CAPABILITY_COUNT)
        body.u16le(0)                            // pad2octets
        body.bytes(caps)

        val out = RdpOut(1200)
        out.u16le(RdpConstants.SEC_ENCRYPT or RdpConstants.SEC_RESET_SEQNO) // flags: unused under TLS
        out.u16le(0)
        out.u16le(6 + body.size)
        out.u16le(RdpConstants.PDUTYPE_CONFIRMACTIVEPDU)
        out.u16le(0x03EA)
        out.bytes(body.toByteArray())
        sendSlowPath(out)
    }

    private val CAPABILITY_COUNT = 6

    private fun buildCapabilitySets(): ByteArray {
        val out = RdpOut(512)

        // ---- General: no bulk compression, so the server must not use MPPC.
        val general = RdpOut(64)
        general.u16le(0x0001)   // osMajorType = WINDOWS
        general.u16le(0x0002)   // osMinorType = WINDOWS_NT
        general.u16le(0x0200)   // protocolVersion = TS_CAPS_PROTOCOLVERSION
        general.u16le(0)
        general.u16le(0)        // generalCompressionTypes: none
        general.u16le(0)        // extraFlags
        general.u16le(0)
        general.u16le(0)
        general.u16le(0)        // generalCompressionLevel
        general.u8(0)           // refreshRectSupport
        general.u8(0)           // suppressOutputSupport
        writeCapability(out, RdpConstants.CAPSTYPE_GENERAL, general)

        // ---- Bitmap: compression off, resizing on.
        val bitmap = RdpOut(64)
        bitmap.u16le(16)                 // preferredBitsPerPixel
        bitmap.u16le(1)
        bitmap.u16le(1)
        bitmap.u16le(1)
        bitmap.u16le(desktopWidth)
        bitmap.u16le(desktopHeight)
        bitmap.u16le(0)
        bitmap.u16le(1)                  // desktopResizeFlag
        bitmap.u16le(0)                  // bitmapCompressionFlag – see class comment
        bitmap.u8(0)                     // highColorFlags
        bitmap.u8(0)                     // drawingFlags
        bitmap.u16le(1)                  // multipleRectangleSupport
        bitmap.u16le(0)
        writeCapability(out, RdpConstants.CAPSTYPE_BITMAP, bitmap)

        // ---- Order: support no drawing orders at all, forcing bitmap updates.
        val order = RdpOut(128)
        order.skip(16)                   // terminalDescriptor
        order.u32le(0)
        order.u16le(0)                   // desktopX
        order.u16le(0)                   // desktopY
        order.u32le(0)
        order.u16le(0)                   // desktopSaveXGranularity
        order.u16le(0)
        order.u16le(0)
        order.u16le(1)                   // maximumOrderLevel
        order.u16le(0)                   // numberFonts
        order.u16le(0)                   // orderFlags
        order.skip(32)                   // orderSupport: all zero
        order.u16le(0)
        order.u32le(0)                   // desktopSaveSize
        order.u16le(0)
        order.u16le(0)
        writeCapability(out, RdpConstants.CAPSTYPE_ORDER, order)

        // ---- Bitmap cache: declared empty so the server never emits cache orders.
        val cache = RdpOut(64)
        cache.u32le(0)
        cache.u32le(0)
        cache.u32le(0)
        repeat(3) { cache.u16le(0); cache.u16le(0) }
        cache.u16le(0)
        writeCapability(out, RdpConstants.CAPSTYPE_BITMAPCACHE, cache)

        // ---- Input: scancodes + unicode + extended mouse.
        val input = RdpOut(128)
        input.u16le(INPUT_FLAG_SCANCODES or INPUT_FLAG_MOUSEX or INPUT_FLAG_UNICODE)
        input.u16le(0)
        input.u32le(0x00000409)
        input.u32le(4)                   // keyboardType
        input.u32le(0)
        input.u32le(12)                  // keyboardFunctionKey
        input.skip(64)                   // imeFileName
        writeCapability(out, RdpConstants.CAPSTYPE_INPUT, input)

        // ---- Virtual channel: declared but unused (no static channels requested).
        val vc = RdpOut(16)
        vc.u32le(0)
        vc.u32le(0)                      // VCChunkSize
        writeCapability(out, RdpConstants.CAPSTYPE_VIRTUALCHANNEL, vc)

        return out.toByteArray()
    }

    private fun writeCapability(out: RdpOut, type: Int, data: RdpOut) {
        out.u16le(type)
        out.u16le(4 + data.size)
        out.bytes(data.toByteArray())
    }

    private fun sendSynchronize() {
        val payload = RdpOut(16)
        payload.u32le(shareId)     // target user comes from the data header
        payload.u16le(1)           // messageType = SYNCMSGTYPE_SYNC
        payload.u16le(0x03EA)      // targetUser
        sendDataPdu(RdpConstants.PDUTYPE2_SYNCHRONIZE, payload)
    }

    private fun sendControlCooperate() {
        val payload = RdpOut(16)
        payload.u16le(CTRLACTION_COOPERATE)
        payload.u16le(0)
        payload.u32le(0)
        sendDataPdu(RdpConstants.PDUTYPE2_CONTROL, payload)
    }

    private fun sendControlRequestControl() {
        val payload = RdpOut(16)
        payload.u16le(CTRLACTION_REQUEST_CONTROL)
        payload.u16le(0)
        payload.u32le(shareId)
        sendDataPdu(RdpConstants.PDUTYPE2_CONTROL, payload)
    }

    private fun sendFontList() {
        val payload = RdpOut(16)
        payload.u16le(0)   // numberFonts
        payload.u16le(0)
        payload.u16le(0x0003)  // listFlags
        payload.u16le(0x0032)  // pad
        sendDataPdu(RdpConstants.PDUTYPE2_FONTLIST, payload)
    }

    // ------------------------------------------------------------------- input

    /**
     * Send a pointer event.
     *
     * @param mask bit 1 = left, bit 2 = middle, bit 4 = right (same shape as the VNC canvas,
     *             so the existing gesture handling can be shared).
     */
    fun sendPointerEvent(x: Int, y: Int, mask: Int) {
        val px = frame.clampX(x)
        val py = frame.clampY(y)
        var flags = RdpConstants.PTRFLAGS_MOVE
        if (mask and 1 != 0) flags = flags or RdpConstants.PTRFLAGS_BUTTON1
        if (mask and 4 != 0) flags = flags or RdpConstants.PTRFLAGS_BUTTON2
        if (mask and 2 != 0) flags = flags or RdpConstants.PTRFLAGS_BUTTON3
        if (mask == 0) flags = RdpConstants.PTRFLAGS_MOVE
        else flags = flags or RdpConstants.PTRFLAGS_DOWN

        val event = RdpOut(16)
        event.u32le(0)                 // eventTime
        event.u16le(RdpConstants.INPUT_EVENT_MOUSE)
        event.u16le(flags)
        event.u16le(px)
        event.u16le(py)
        sendInputEvent(event)
    }

    /**
     * Send a hardware scancode key event.
     *
     * Extended keys (arrows, Delete, the Windows key) are marked with a flag rather than
     * the 0xE0 prefix a physical keyboard would emit, which is how RDP expects them.
     */
    fun sendScancode(scancode: Int, down: Boolean, extended: Boolean = false) {
        val event = RdpOut(16)
        event.u32le(0)
        event.u16le(RdpConstants.INPUT_EVENT_SCANCODE)
        var flags = if (down) RdpConstants.KBDFLAGS_DOWN else RdpConstants.KBDFLAGS_RELEASE
        if (extended) flags = flags or RdpConstants.KBDFLAGS_EXTENDED
        event.u16le(flags)
        event.u16le(scancode)
        event.u16le(0)
        sendInputEvent(event)
    }

    /** Press and release a scancode in one go (used by the on-screen function keys). */
    fun tapScancode(scancode: Int, extended: Boolean = false) {
        sendScancode(scancode, true, extended)
        sendScancode(scancode, false, extended)
    }

    /** Send a printable character as a Unicode key event (simplest path for text). */
    fun sendUnicode(ch: Char) {
        val event = RdpOut(16)
        event.u32le(0)
        event.u16le(RdpConstants.INPUT_EVENT_UNICODE)
        event.u16le(ch.code and 0xFFFF)
        event.u16le(0)
        sendInputEvent(event)
    }

    private fun sendInputEvent(event: RdpOut) {
        val payload = RdpOut(64)
        payload.u16le(1)      // numberEvents
        payload.u16le(0)      // pad2octets
        payload.bytes(event.toByteArray())
        scope.launch {
            runCatching { sendDataPdu(RdpConstants.PDUTYPE2_INPUT, payload) }
        }
    }

    // ------------------------------------------------------------ reader loop

    private fun startReader() {
        readerJob = scope.launch {
            try {
                while (isConnected) {
                    val payload = readSlowPathPayload()
                    if (payload.isEmpty()) continue
                    handleSlowPath(payload)
                }
            } catch (t: Throwable) {
                isConnected = false
                listener?.onDisconnected(t.message ?: "连接已断开")
            }
        }
    }

    private fun handleSlowPath(payload: ByteArray) {
        val inp = RdpIn(payload)
        inp.u16le()                // security flags
        inp.u16le()                // flagsHi
        val len = inp.u16le()
        val type = inp.u16le()
        inp.u16le()                // pduSource

        if (type != RdpConstants.PDUTYPE_DATAPDU) return

        // TS_SHAREDATAHEADER
        inp.u32le()                // shareId
        inp.u8()                   // pad1
        inp.u8()                   // streamId
        inp.u16le()                // uncompressedLength
        val type2 = inp.u8()
        inp.u8()                   // compressedType
        inp.u16le()                // compressedLength

        if (type2 == RdpConstants.PDUTYPE2_UPDATE) handleUpdate(inp, payload)
    }

    private fun handleUpdate(inp: RdpIn, payload: ByteArray) {
        val updateType = inp.u16le()
        when (updateType) {
            RdpConstants.UPDATETYPE_BITMAP -> handleBitmapUpdate(inp, payload)
            RdpConstants.UPDATETYPE_PALETTE -> Unit
            RdpConstants.UPDATETYPE_SYNCHRONIZE -> Unit
            RdpConstants.UPDATETYPE_ORDERS -> Unit    // no orders are advertised
        }
    }

    private fun handleBitmapUpdate(inp: RdpIn, payload: ByteArray) {
        val count = inp.u16le()
        repeat(count) {
            if (inp.remaining() < 18) return
            val left = inp.u16le()
            val top = inp.u16le()
            val right = inp.u16le()
            val bottom = inp.u16le()
            val w = inp.u16le()
            val h = inp.u16le()
            val bpp = inp.u16le()
            val flags = inp.u16le()
            val length = if (flags and RdpConstants.NO_BITMAP_COMPRESSION_HDR != 0) {
                // No explicit length field: everything left in the PDU is image data.
                payload.size - inp.offset()
            } else {
                inp.u16le()
            }
            if (length > 0 && inp.remaining() >= length) {
                frame.update(left, top, w, h, bpp, flags, payload, inp.offset(), length)
                inp.skip(length)
                listener?.onFrameInvalidated()
            } else {
                return
            }
        }
        // Desktop size changes are driven by the Deactivate/Reactivate sequence, not here.
    }

    // ------------------------------------------------------------- transports

    /**
     * Wrap a payload in an MCS Send Data Request.
     *
     * The outer PER length has to cover the initiator, the channel id, the
     * priority/segmentation octet *and* the inner length field, so it depends on how many
     * octets the inner length takes – hard-coding a constant silently truncates the PDU
     * once the payload crosses 127 bytes, which it does on the very first capability set.
     */
    private fun sendSlowPath(body: RdpOut) {
        val payload = body.toByteArray()
        val mcs = RdpOut(payload.size + 32)
        mcs.u8(RdpConstants.MCS_SEND_DATA_REQUEST)
        writePerLength(mcs, 5 + perLengthOctets(payload.size) + payload.size)
        mcs.u16be(userId)
        mcs.u16be(ioChannelId)
        mcs.u8(0x70)
        writePerLength(mcs, payload.size)
        mcs.bytes(payload)
        sendX224Data(mcs)
    }

    private fun perLengthOctets(length: Int): Int = when {
        length <= 0x7F -> 1
        length <= 0x3FFF -> 2
        else -> 3
    }

    private fun sendDataPdu(type2: Int, payload: RdpOut) {
        val data = RdpOut(payload.size + 32)
        data.u32le(shareId)
        data.u8(0)                 // pad1
        data.u8(0x01)              // streamId
        data.u16le(payload.size)   // uncompressedLength
        data.u8(type2)
        data.u8(0)                 // compressedType
        data.u16le(0)              // compressedLength
        data.bytes(payload.toByteArray())

        val body = RdpOut(data.size + 32)
        body.u16le(0)              // security flags (TLS does the encryption)
        body.u16le(0)
        body.u16le(6 + data.size)  // shareControlHeader totalLength
        body.u16le(RdpConstants.PDUTYPE_DATAPDU)
        body.u16le(0x03EA)         // pduSource = client
        body.bytes(data.toByteArray())
        sendSlowPath(body)
    }

    private fun sendX224Data(mcs: RdpOut) {
        val out = RdpOut(mcs.size + 8)
        out.u8(RdpConstants.TPKT_VERSION)
        out.u8(0)
        out.u16be(4 + 3 + mcs.size)
        out.u8(0x02)               // X.224 LI
        out.u8(RdpConstants.X224_DT)
        out.u8(0x80)               // EOT
        out.bytes(mcs.toByteArray())
        write(out)
    }

    private fun write(out: RdpOut) {
        synchronized(lock) {
            val stream = output ?: return
            stream.write(out.toByteArray())
            stream.flush()
        }
    }

    /** Read one TPKT frame and return its payload (everything past the 4-byte header). */
    private fun readTpkt(): ByteArray {
        val stream = input ?: throw IOException("not connected")
        val version = stream.readUnsignedByte()
        if (version != RdpConstants.TPKT_VERSION) {
            throw IOException("TPKT 版本错误: $version")
        }
        stream.readUnsignedByte()              // reserved
        val length = (stream.readUnsignedByte() shl 8) or stream.readUnsignedByte()
        if (length < 4) throw IOException("TPKT 长度非法: $length")
        val body = ByteArray(length - 4)
        stream.readFully(body)
        return body
    }

    /** Read one TPKT frame and strip the X.224 data header, leaving the MCS payload. */
    private fun readMcsPayload(): ByteArray {
        val frameBytes = readTpkt()
        if (frameBytes.size < 3) return ByteArray(0)
        return frameBytes.copyOfRange(3, frameBytes.size)
    }

    /** Read a slow-path PDU and return the bytes after security + share control headers. */
    private fun readSlowPathPayload(): ByteArray {
        val mcs = readMcsPayload()
        if (mcs.isEmpty()) return ByteArray(0)
        val inp = RdpIn(mcs)
        val tag = inp.u8()
        if (tag != RdpConstants.MCS_SEND_DATA_INDICATION) {
            // Licensing or other domain PDUs arrive here too; they are not graphics.
            return ByteArray(0)
        }
        writePerLengthSkip(inp)        // MCS length
        inp.u16be()                    // initiator
        inp.u16be()                    // channelId
        inp.u8()                       // segmentation
        writePerLengthSkip(inp)        // user data length
        return inp.take(inp.remaining())
    }

    /** Consume a PER length without needing its value. */
    private fun writePerLengthSkip(inp: RdpIn) {
        val first = inp.u8()
        when {
            first and 0x80 == 0 -> Unit
            first and 0xC0 == 0x80 -> inp.skip(1)
            else -> inp.skip(2)
        }
    }

    // ------------------------------------------------------------------ public

    fun framebuffer(): Bitmap = frame.framebuffer()

    fun disconnect() {
        isConnected = false
        readerJob?.cancel()
        runCatching { socket?.close() }
        socket = null
    }

    fun setDesktopSize(w: Int, h: Int) {
        width = w
        height = h
        frame.resize(w, h)
        listener?.onDesktopSizeChanged(w, h)
    }

    companion object {
        // Client Info PDU flags.
        private const val INFO_MOUSE = 0x00000001
        private const val INFO_UNICODE = 0x00000010
        private const val INFO_LOGONERRORS = 0x00000002
        private const val INFO_LOGONNOTIFY = 0x00000004
        private const val INFO_ENABLEWINDOWSKEY = 0x00000100
        private const val INFO_MAXIMIZESHELL = 0x00000008
        private const val INFO_REMOTECONSOLEAUDIO = 0x00002000

        private const val CTRLACTION_COOPERATE = 0x0004
        private const val CTRLACTION_REQUEST_CONTROL = 0x0001

        private const val INPUT_FLAG_SCANCODES = 0x0001
        private const val INPUT_FLAG_MOUSEX = 0x0004
        private const val INPUT_FLAG_UNICODE = 0x0008
    }
}
