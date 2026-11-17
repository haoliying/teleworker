package com.yuanhang.ssh.ssh

import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * An interactive shell bound to a [com.yuanhang.ssh.terminal.TerminalEmulator].
 *
 * Bytes arriving on the channel are pushed straight into the emulator; anything the
 * emulator needs to answer (cursor reports, device attributes) is written back
 * through the same channel.
 */
class ShellSession(
    val sessionId: String,
    val displayName: String,
    private val ssh: Session,
    rows: Int,
    cols: Int
) {

    val terminal = com.yuanhang.ssh.terminal.TerminalBuffer(rows, cols)
    val emulator: com.yuanhang.ssh.terminal.TerminalEmulator

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val listeners = mutableSetOf<(String) -> Unit>()

    private var channel: com.jcraft.jsch.ChannelShell? = null
    private var shellIn: OutputStream? = null
    private var shellOut: InputStream? = null
    private var readingJob: Job? = null

    @Volatile
    var isConnected = false
        private set

    @Volatile
    var exitMessage: String? = null
        private set

    @Volatile
    private var dirty = false

    init {
        emulator = com.yuanhang.ssh.terminal.TerminalEmulator(
            terminal,
            object : com.yuanhang.ssh.terminal.TerminalEmulator.Listener {
                override fun onInvalidate() { dirty = true }
                override fun onTitleChanged(title: String) = Unit
                override fun onBell() = Unit
                override fun onWriteBack(data: String) { writeRaw(data.toByteArray()) }
            }
        )
    }

    /**
     * Emitted whenever the screen content changed. Collected on the main thread by the
     * renderer, which is why this is a flow rather than a plain callback – terminal
     * output arrives on the socket's IO coroutine.
     */
    /** Keystroke queue, drained on the IO thread by [startWriter]. */
    private val outbox = kotlinx.coroutines.channels.Channel<ByteArray>(
        kotlinx.coroutines.channels.Channel.UNLIMITED
    )

    private val _invalidations = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val invalidations: SharedFlow<Unit> = _invalidations.asSharedFlow()

    /** Register a callback fired whenever new output lands in the buffer. */
    fun addInvalidateListener(listener: (String) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeInvalidateListener(listener: (String) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it(sessionId) }
    }

    fun start() {
        val ch = ssh.openChannel("shell") as com.jcraft.jsch.ChannelShell
        ch.setPty(true)
        ch.setPtySize(terminal.screenCols, terminal.screenRows, 0, 0)
        ch.setEnv("LANG", "C.UTF-8")
        ch.setEnv("TERM", "xterm-256color")

        // JSch logs a warning if the streams are fetched after connect(): the channel can
        // start delivering data the moment it opens, and a pipe registered late would miss
        // the first chunk (which is usually the prompt).
        val fromRemote = ch.inputStream
        val toRemote = ch.outputStream
        ch.connect(20_000)

        channel = ch
        shellIn = toRemote
        shellOut = fromRemote
        isConnected = true

        startWriter()
        startReader()
        startInvalidatePump()

        // Some servers only echo the prompt once the terminal size is announced.
        write("\n")
    }

    /** Serialises keystrokes so a fast typist's bytes reach the server in order. */
    private fun startWriter() {
        scope.launch {
            for (bytes in outbox) writeRawOnIo(bytes)
        }
    }

    private fun startReader() {
        readingJob = scope.launch {
            val buf = ByteArray(16 * 1024)
            val out = shellOut ?: return@launch
            try {
                while (isConnected) {
                    val read = out.read(buf)
                    if (read < 0) break
                    if (read > 0) {
                        emulator.append(buf, read)
                        dirty = true
                    }
                }
            } catch (ignore: Throwable) {
                // Socket closed by peer – normal during disconnect.
            } finally {
                isConnected = false
                exitMessage = "连接已断开"
                dirty = true
                notifyListeners()
            }
        }
    }

    /** Coalesce rapid updates so the renderer repaints at a sane frame rate. */
    private fun startInvalidatePump() {
        scope.launch {
            while (true) {
                delay(30)
                if (dirty) {
                    dirty = false
                    _invalidations.tryEmit(Unit)
                    notifyListeners()
                }
            }
        }
    }

    fun writeRaw(data: ByteArray) {
        try {
            val out = shellIn ?: return
            out.write(data)
            out.flush()
        } catch (t: Throwable) {
            exitMessage = t.message
        }
    }

    private fun writeRawOnIo(data: ByteArray) = writeRaw(data)

    /**
     * Queue keystrokes for the server.
     *
     * Called straight from the UI thread on every key event, so it must not touch the
     * socket: the bytes go into an unbounded channel that a single IO coroutine drains in
     * order.
     */
    fun write(data: String) {
        if (data.isEmpty()) return
        outbox.trySend(data.toByteArray(Charsets.UTF_8))
    }

    fun resize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        if (rows == terminal.screenRows && cols == terminal.screenCols) return
        emulator.resize(rows, cols)
        // setPtySize writes to the SSH transport – keep it off the calling (UI) thread.
        scope.launch {
            try {
                channel?.setPtySize(cols, rows, 0, 0)
            } catch (t: Throwable) {
                // Server may have gone away already.
            }
        }
    }

    fun close() {
        isConnected = false
        runCatching { outbox.close() }
        readingJob?.cancel()
        try { channel?.disconnect() } catch (t: Throwable) { /* ignore */ }
        scope.launch {
            try { ssh.disconnect() } catch (t: Throwable) { /* ignore */ }
        }
    }
}
