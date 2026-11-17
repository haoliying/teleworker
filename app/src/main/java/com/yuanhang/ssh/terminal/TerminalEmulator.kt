package com.yuanhang.ssh.terminal

/**
 * A reasonably complete VT100 / xterm escape sequence parser.
 *
 * It consumes raw bytes coming from the remote shell and mutates the attached
 * [TerminalBuffer]. Anything it does not understand is swallowed silently, which
 * is what keeps `top`, `vim`, `htop` and friends from spraying garbage.
 */
class TerminalEmulator(
    private val buffer: TerminalBuffer,
    private val listener: Listener? = null
) {

    interface Listener {
        fun onInvalidate()
        fun onTitleChanged(title: String)
        fun onBell()
        /** The remote side asked us for something (DSR, DA) – write it straight back. */
        fun onWriteBack(data: String)
    }

    private companion object {
        const val MAX_PARAMS = 16

        // Parser states
        const val STATE_GROUND = 0
        const val STATE_ESC = 1
        const val STATE_ESC_INTERMEDIATE = 2
        const val STATE_CSI_ENTRY = 3
        const val STATE_CSI_PARAM = 4
        const val STATE_CSI_INTERMEDIATE = 5
        const val STATE_CSI_IGNORE = 6
        const val STATE_OSC_STRING = 7
        const val STATE_OSC_STRING_ESC = 8
        const val STATE_UTF8 = 9
    }

    private var state = STATE_GROUND

    private val params = IntArray(MAX_PARAMS)
    private var paramCount = 0
    private var currentParam = 0
    private var sawDigit = false
    private var intermediate = 0.toChar()

    /** Sub-parameters used by SGR 38/48 (separated by ':'). */
    private val subParams = mutableListOf<Int>()

    private val oscBuffer = StringBuilder()
    private var utf8Buffer = 0
    private var utf8BytesLeft = 0

    /** Current SGR-independent writing style. */
    private var currentStyle: Long = TextStyle.NORMAL

    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var savedStyle: Long = TextStyle.NORMAL

    private val tabStops = BooleanArray(512) { index -> index % 8 == 0 }

    private var autoWrap = true
    private var originMode = false
    private var insertMode = false
    private var wrapPending = false

    var title: String = ""
        private set

    // ------------------------------------------------------------- feeding

    fun append(data: ByteArray, length: Int = data.size) {
        for (i in 0 until length) {
            val b = data[i].toInt() and 0xFF
            val cp = consumeUtf8(b) ?: continue
            process(cp)
        }
    }

    /**
     * Accumulate UTF-8 continuation bytes. Returns null while a multi-byte
     * sequence is still incomplete.
     */
    private fun consumeUtf8(b: Int): Int? {
        if (utf8BytesLeft == 0) {
            when {
                b < 0x80 -> return b
                b in 0xC0..0xDF -> { utf8Buffer = b and 0x1F; utf8BytesLeft = 1 }
                b in 0xE0..0xEF -> { utf8Buffer = b and 0x0F; utf8BytesLeft = 2 }
                b in 0xF0..0xF7 -> { utf8Buffer = b and 0x07; utf8BytesLeft = 3 }
                else -> return b
            }
            return null
        }
        if (b and 0xC0 != 0x80) {
            // Invalid continuation: emit replacement and restart on this byte.
            utf8BytesLeft = 0
            return 0xFFFD
        }
        utf8Buffer = (utf8Buffer shl 6) or (b and 0x3F)
        utf8BytesLeft--
        if (utf8BytesLeft == 0) {
            val cp = utf8Buffer
            utf8Buffer = 0
            return cp
        }
        return null
    }

    private fun process(cp: Int) {
        if (cp < 0) return

        when (state) {
            STATE_GROUND -> when {
                cp in 0x20..0x7E || cp > 0xA0 -> handlePrintable(cp)
                cp == 0x1B -> state = STATE_ESC
                cp == 0x07 -> listener?.onBell()
                cp == 0x08 -> handleBackspace()
                cp == 0x09 -> handleTab()
                cp == 0x0A -> handleLineFeed()
                cp == 0x0B -> handleLineFeed()
                cp == 0x0C -> handleLineFeed()
                cp == 0x0D -> handleCarriageReturn()
                cp == 0x0E -> Unit // SO - shift out
                cp == 0x0F -> Unit // SI - shift in
                cp == 0x7F -> Unit // DEL ignored, like most terminals
                else -> Unit
            }
            STATE_ESC -> handleEscape(cp)
            STATE_ESC_INTERMEDIATE -> {
                if (cp == 0x5C) state = STATE_GROUND else Unit
            }
            STATE_CSI_ENTRY -> parseCsiParam(cp)
            STATE_CSI_PARAM -> parseCsiParam(cp)
            STATE_CSI_INTERMEDIATE -> {
                if (cp in 0x30..0x7E) {
                    state = STATE_GROUND
                } else if (cp == 0x1B) {
                    state = STATE_ESC
                }
            }
            STATE_CSI_IGNORE -> {
                if (cp == 0x40 || cp in 0x61..0x7A) state = STATE_GROUND
                else if (cp == 0x1B) state = STATE_ESC
            }
            STATE_OSC_STRING -> {
                when (cp) {
                    0x07 -> {
                        dispatchOsc(oscBuffer.toString())
                        oscBuffer.setLength(0)
                        state = STATE_GROUND
                    }
                    0x1B -> state = STATE_OSC_STRING_ESC
                    else -> oscBuffer.append(cp.toChar())
                }
            }
            STATE_OSC_STRING_ESC -> {
                if (cp == 0x5C) {
                    dispatchOsc(oscBuffer.toString())
                    oscBuffer.setLength(0)
                    state = STATE_GROUND
                } else {
                    oscBuffer.append(0x1B.toChar())
                    state = STATE_ESC
                }
            }
            STATE_UTF8 -> Unit
        }
    }

    // ------------------------------------------------------------- printables

    private fun handlePrintable(cp: Int) {
        if (wrapPending && autoWrap) {
            buffer.setCursorCol(0)
            lineFeed()
            wrapPending = false
        }
        if (buffer.cursorCol >= buffer.screenCols) {
            if (autoWrap) {
                buffer.setCursorCol(0)
                lineFeed()
            } else {
                buffer.setCursorCol(buffer.screenCols - 1)
            }
        }
        if (insertMode) buffer.insertCells(buffer.cursorRow, buffer.cursorCol, 1, currentStyle)

        val ch = if (cp in 0x21..0x7E || cp > 0xA0) cp.toChar() else ' '
        buffer.setChar(buffer.cursorRow, buffer.cursorCol, ch, currentStyle)

        if (buffer.cursorCol + 1 >= buffer.screenCols) {
            if (autoWrap) wrapPending = true
        } else {
            buffer.setCursorCol(buffer.cursorCol + 1)
        }
    }

    private fun handleBackspace() {
        wrapPending = false
        if (buffer.cursorCol > 0) buffer.setCursorCol(buffer.cursorCol - 1)
    }

    private fun handleTab() {
        wrapPending = false
        var col = buffer.cursorCol
        while (col < buffer.screenCols - 1 && !tabStops.getOrElse(col + 1) { false }) col++
        buffer.setCursorCol(col)
    }

    private fun handleLineFeed() {
        wrapPending = false
        lineFeed()
    }

    private fun handleCarriageReturn() {
        wrapPending = false
        buffer.setCursorCol(0)
    }

    private fun lineFeed() {
        if (buffer.cursorRow == buffer.scrollBottom) {
            scrollUpRegion(1)
        } else if (buffer.cursorRow < buffer.screenRows - 1) {
            buffer.setCursorRow(buffer.cursorRow + 1)
        }
    }

    private fun reverseIndex() {
        if (buffer.cursorRow == buffer.scrollTop) {
            buffer.insertLines(buffer.scrollTop, 1, currentStyle)
        } else if (buffer.cursorRow > 0) {
            buffer.setCursorRow(buffer.cursorRow - 1)
        }
    }

    private fun scrollUpRegion(count: Int) {
        repeat(count) {
            val full =
                buffer.scrollTop == 0 && buffer.scrollBottom == buffer.screenRows - 1
            if (full) buffer.scrollUpOneLine() else buffer.deleteLines(buffer.scrollTop, 1, currentStyle)
        }
    }

    // ----------------------------------------------------------------- ESC

    private fun handleEscape(cp: Int) {
        when (cp) {
            '['.code -> {
                paramCount = 0
                currentParam = 0
                sawDigit = false
                intermediate = 0.toChar()
                subParams.clear()
                state = STATE_CSI_ENTRY
            }
            ']'.code -> {
                oscBuffer.setLength(0)
                state = STATE_OSC_STRING
            }
            'P'.code -> state = STATE_CSI_IGNORE    // DCS – sink
            '^'.code -> state = STATE_CSI_IGNORE    // PM  – sink
            '_'.code -> state = STATE_CSI_IGNORE    // APC – sink
            'X'.code -> state = STATE_CSI_IGNORE    // SOS – sink
            '('.code, ')'.code, '*'.code, '+'.code -> Unit  // charset selection – ignored
            '#'.code -> Unit
            '7'.code -> {
                savedCursorRow = buffer.cursorRow
                savedCursorCol = buffer.cursorCol
                savedStyle = currentStyle
                state = STATE_GROUND
            }
            '8'.code -> {
                buffer.setCursor(savedCursorRow, savedCursorCol)
                currentStyle = savedStyle
                wrapPending = false
                state = STATE_GROUND
            }
            'D'.code -> {
                lineFeed()
                state = STATE_GROUND
            }
            'E'.code -> {
                handleCarriageReturn()
                lineFeed()
                state = STATE_GROUND
            }
            'M'.code -> {
                reverseIndex()
                state = STATE_GROUND
            }
            'H'.code -> {
                tabStops[buffer.cursorCol.coerceIn(0, tabStops.lastIndex)] = true
                state = STATE_GROUND
            }
            'c'.code -> {
                buffer.clear(currentStyle)
                state = STATE_GROUND
            }
            '='.code, '>'.code -> state = STATE_GROUND
            else -> state = STATE_GROUND
        }
    }

    // ----------------------------------------------------------------- CSI

    private fun parseCsiParam(cp: Int) {
        when {
            cp in 0x30..0x39 -> {
                currentParam = currentParam * 10 + (cp - 0x30)
                sawDigit = true
                state = STATE_CSI_PARAM
            }
            cp == 0x3B -> {                      // ';' → next parameter
                pushParam()
                state = STATE_CSI_PARAM
            }
            cp == 0x3A -> {                      // ':' → sub parameter
                subParams.add(currentParam)
                currentParam = 0
                sawDigit = false
                state = STATE_CSI_PARAM
            }
            cp in 0x3C..0x3F -> {                // '<=?' private markers
                pushParam()
                intermediate = cp.toChar()
                state = STATE_CSI_PARAM
            }
            cp in 0x20..0x2F -> {
                state = STATE_CSI_INTERMEDIATE
            }
            cp in 0x40..0x7E -> {
                pushParam()
                state = STATE_GROUND
                dispatchCsi(intermediate, cp, params, paramCount, subParams)
            }
            cp == 0x7F -> Unit                   // ignore DEL inside sequence
            else -> state = STATE_CSI_IGNORE
        }
    }

    private fun pushParam() {
        if (paramCount < MAX_PARAMS) {
            params[paramCount++] = if (sawDigit) currentParam else -1
        }
        currentParam = 0
        sawDigit = false
    }

    private fun arg(params: IntArray, index: Int, fallback: Int, offset: Boolean): Int {
        val raw = if (index < MAX_PARAMS) params[index] else -1
        val base = if (raw <= 0) fallback else raw
        return if (offset) base - 1 else base
    }

    private fun dispatchCsi(
        privateMarker: Char,
        final: Int,
        params: IntArray,
        count: Int,
        sub: List<Int>
    ) {
        if (privateMarker == '?') {
            dispatchPrivateMode(params, count, final)
            return
        }
        val rows = buffer.screenRows
        val cols = buffer.screenCols

        when (final) {
            'A'.code -> {                        // CUU – cursor up
                wrapPending = false
                val n = arg(params, 0, 1, false)
                buffer.setCursorRow((buffer.cursorRow - n).coerceAtLeast(buffer.scrollTop))
            }
            'B'.code, 'e'.code -> {              // CUD / VPR
                wrapPending = false
                val n = arg(params, 0, 1, false)
                buffer.setCursorRow((buffer.cursorRow + n).coerceAtMost(buffer.scrollBottom))
            }
            'C'.code, 'a'.code -> {              // CUF / HPR
                wrapPending = false
                val n = arg(params, 0, 1, false)
                buffer.setCursorCol(buffer.cursorCol + n)
            }
            'D'.code -> {                        // CUB
                wrapPending = false
                val n = arg(params, 0, 1, false)
                buffer.setCursorCol(buffer.cursorCol - n)
            }
            'E'.code -> {                        // CNL
                wrapPending = false
                val n = arg(params, 0, 1, false)
                buffer.setCursorCol(0)
                buffer.setCursorRow((buffer.cursorRow + n).coerceAtMost(rows - 1))
            }
            'F'.code -> {                        // CPL
                wrapPending = false
                val n = arg(params, 0, 1, false)
                buffer.setCursorCol(0)
                buffer.setCursorRow((buffer.cursorRow - n).coerceAtLeast(0))
            }
            'G'.code, '`'.code -> {              // CHA / HPA
                wrapPending = false
                val n = arg(params, 0, 1, true)
                val base = if (originMode) buffer.scrollTop else 0
                buffer.setCursorCol(base + n)
            }
            'd'.code -> {                        // VPA
                wrapPending = false
                val n = arg(params, 0, 1, true)
                buffer.setCursorRow(n)
            }
            'H'.code, 'f'.code -> {              // CUP
                wrapPending = false
                val row = arg(params, 0, 1, true)
                val col = arg(params, 1, 1, true)
                val r = if (originMode) row + buffer.scrollTop else row
                buffer.setCursor(r.coerceIn(0, rows - 1), col.coerceIn(0, cols - 1))
            }
            'J'.code -> {                        // ED
                wrapPending = false
                when (arg(params, 0, 0, false)) {
                    0 -> {
                        buffer.eraseCells(buffer.cursorRow, buffer.cursorCol, cols, currentStyle)
                        for (r in buffer.cursorRow + 1 until rows) buffer.eraseRow(r, currentStyle)
                    }
                    1 -> {
                        buffer.eraseCells(buffer.cursorRow, 0, buffer.cursorCol + 1, currentStyle)
                        for (r in 0 until buffer.cursorRow) buffer.eraseRow(r, currentStyle)
                    }
                    else -> buffer.eraseRows(0, rows - 1, currentStyle)
                }
            }
            'K'.code -> {                        // EL
                wrapPending = false
                when (arg(params, 0, 0, false)) {
                    0 -> buffer.eraseCells(buffer.cursorRow, buffer.cursorCol, cols, currentStyle)
                    1 -> buffer.eraseCells(buffer.cursorRow, 0, buffer.cursorCol + 1, currentStyle)
                    else -> buffer.eraseRow(buffer.cursorRow, currentStyle)
                }
            }
            'L'.code -> {                        // IL
                wrapPending = false
                if (buffer.cursorRow in buffer.scrollTop..buffer.scrollBottom) {
                    buffer.insertLines(buffer.cursorRow, arg(params, 0, 1, false), currentStyle)
                    buffer.setCursorCol(0)
                }
            }
            'M'.code -> {                        // DL
                wrapPending = false
                if (buffer.cursorRow in buffer.scrollTop..buffer.scrollBottom) {
                    buffer.deleteLines(buffer.cursorRow, arg(params, 0, 1, false), currentStyle)
                    buffer.setCursorCol(0)
                }
            }
            'P'.code -> {                        // DCH
                wrapPending = false
                buffer.deleteCells(
                    buffer.cursorRow,
                    buffer.cursorCol,
                    arg(params, 0, 1, false),
                    currentStyle
                )
            }
            '@'.code -> {                        // ICH
                wrapPending = false
                buffer.insertCells(
                    buffer.cursorRow,
                    buffer.cursorCol,
                    arg(params, 0, 1, false),
                    currentStyle
                )
            }
            'X'.code -> {                        // ECH
                wrapPending = false
                buffer.eraseCells(
                    buffer.cursorRow,
                    buffer.cursorCol,
                    (buffer.cursorCol + arg(params, 0, 1, false)).coerceAtMost(cols),
                    currentStyle
                )
            }
            'S'.code -> {                        // SU
                wrapPending = false
                scrollUpRegion(arg(params, 0, 1, false))
            }
            'T'.code -> {                        // SD
                wrapPending = false
                repeat(arg(params, 0, 1, false)) {
                    buffer.insertLines(buffer.scrollTop, 1, currentStyle)
                }
            }
            'Z'.code -> {                        // CBT – back tab
                wrapPending = false
                var n = arg(params, 0, 1, false)
                var col = buffer.cursorCol
                while (n > 0 && col > 0) {
                    col--
                    if (tabStops.getOrElse(col) { false }) n--
                }
                buffer.setCursorCol(col)
            }
            'I'.code -> {
                var n = arg(params, 0, 1, false)
                var col = buffer.cursorCol
                while (n > 0 && col < cols - 1) {
                    col++
                    if (tabStops.getOrElse(col) { false }) n--
                }
                buffer.setCursorCol(col)
            }
            'g'.code -> {                        // TBC
                when (arg(params, 0, 0, false)) {
                    0 -> tabStops[buffer.cursorCol.coerceIn(0, tabStops.lastIndex)] = false
                    3 -> tabStops.fill(false)
                }
            }
            'h'.code -> {
                for (i in 0 until count) {
                    if (params[i] == 4) insertMode = true
                    if (params[i] == 7) autoWrap = true
                }
            }
            'l'.code -> {
                for (i in 0 until count) {
                    if (params[i] == 4) insertMode = false
                    if (params[i] == 7) autoWrap = false
                }
            }
            'm'.code -> applySgr(params, count, sub)
            'r'.code -> {                        // DECSTBM
                val top = arg(params, 0, 1, true)
                val bottom = arg(params, 1, rows, true)
                buffer.setScrollRegion(top.coerceAtMost(rows - 1), bottom.coerceAtMost(rows - 1))
                val home = if (originMode) buffer.scrollTop else 0
                buffer.setCursorRow(home)
                buffer.setCursorCol(0)
            }
            's'.code -> {
                savedCursorRow = buffer.cursorRow
                savedCursorCol = buffer.cursorCol
            }
            'u'.code -> {
                buffer.setCursor(savedCursorRow, savedCursorCol)
                wrapPending = false
            }
            'n'.code -> {                        // DSR
                if (params[0] == 6) {
                    val r = (if (originMode) buffer.cursorRow - buffer.scrollTop else buffer.cursorRow) + 1
                    listener?.onWriteBack("\u001B[${r};${buffer.cursorCol + 1}R")
                } else if (params[0] == 5) {
                    listener?.onWriteBack("\u001B[0n")
                }
            }
            'c'.code -> {                        // DA
                listener?.onWriteBack("\u001B[?62;1;6;9;15;22c")
            }
            't'.code -> Unit                     // window ops – ignored
            else -> Unit
        }
        listener?.onInvalidate()
    }

    private fun dispatchPrivateMode(params: IntArray, count: Int, final: Int) {
        when (final) {
            'h'.code -> {
                for (i in 0 until count) {
                    when (params[i]) {
                        7 -> autoWrap = true
                        6 -> originMode = true
                    }
                }
            }
            'l'.code -> {
                for (i in 0 until count) {
                    when (params[i]) {
                        7 -> autoWrap = false
                        6 -> originMode = false
                    }
                }
            }
        }
        listener?.onInvalidate()
    }

    // ----------------------------------------------------------------- SGR

    private fun applySgr(params: IntArray, count: Int, sub: List<Int>) {
        var i = 0
        var fg = TextStyle.foregroundIndex(currentStyle)
        var bg = TextStyle.backgroundIndex(currentStyle)
        var fx = TextStyle.effects(currentStyle)

        while (i < count) {
            val p = params[i]
            when (p) {
                0 -> {
                    fg = TextStyle.INDEX_FOREGROUND
                    bg = TextStyle.INDEX_BACKGROUND
                    fx = 0
                }
                1 -> fx = fx or TextStyle.EFFECT_BOLD
                2 -> fx = fx or TextStyle.EFFECT_DIM
                3 -> fx = fx or TextStyle.EFFECT_ITALIC
                4 -> fx = fx or TextStyle.EFFECT_UNDERLINE
                5, 6 -> fx = fx or TextStyle.EFFECT_BLINK
                7 -> fx = fx or TextStyle.EFFECT_REVERSE
                8 -> fx = fx or TextStyle.EFFECT_INVISIBLE
                9 -> fx = fx or TextStyle.EFFECT_STRIKETHROUGH
                21 -> fx = fx and TextStyle.EFFECT_BOLD.inv()
                22 -> fx = fx and (TextStyle.EFFECT_BOLD or TextStyle.EFFECT_DIM).inv()
                23 -> fx = fx and TextStyle.EFFECT_ITALIC.inv()
                24 -> fx = fx and TextStyle.EFFECT_UNDERLINE.inv()
                25 -> fx = fx and TextStyle.EFFECT_BLINK.inv()
                27 -> fx = fx and TextStyle.EFFECT_REVERSE.inv()
                28 -> fx = fx and TextStyle.EFFECT_INVISIBLE.inv()
                29 -> fx = fx and TextStyle.EFFECT_STRIKETHROUGH.inv()
                in 30..37 -> fg = p - 30
                38 -> {
                    val kind = params.getOrElse(i + 1) { -1 }
                    if (kind == 5) {
                        fg = params.getOrElse(i + 2) { 0 }.coerceIn(0, 255)
                        i += 2
                    } else if (kind == 2) {
                        fg = rgbIndex(
                            params.getOrElse(i + 2) { 0 },
                            params.getOrElse(i + 3) { 0 },
                            params.getOrElse(i + 4) { 0 }
                        )
                        i += 4
                    } else if (sub.isNotEmpty()) {
                        fg = sub.first().coerceIn(0, 255)
                    }
                }
                39 -> fg = TextStyle.INDEX_FOREGROUND
                in 40..47 -> bg = p - 40
                48 -> {
                    val kind = params.getOrElse(i + 1) { -1 }
                    if (kind == 5) {
                        bg = params.getOrElse(i + 2) { 0 }.coerceIn(0, 255)
                        i += 2
                    } else if (kind == 2) {
                        bg = rgbIndex(
                            params.getOrElse(i + 2) { 0 },
                            params.getOrElse(i + 3) { 0 },
                            params.getOrElse(i + 4) { 0 }
                        )
                        i += 4
                    } else if (sub.isNotEmpty()) {
                        bg = sub.first().coerceIn(0, 255)
                    }
                }
                49 -> bg = TextStyle.INDEX_BACKGROUND
                in 90..97 -> fg = p - 90 + 8
                in 100..107 -> bg = p - 100 + 8
            }
            i++
        }
        currentStyle = TextStyle.encode(fg, bg, fx)
    }

    /** Map a truecolor RGB triplet onto the closest 6x6x6 cube slot. */
    private fun rgbIndex(r: Int, g: Int, b: Int): Int {
        fun q(v: Int) = (v.coerceIn(0, 255) * 5 + 127) / 255
        return 16 + 36 * q(r) + 6 * q(g) + q(b)
    }

    // ----------------------------------------------------------------- OSC

    private fun dispatchOsc(value: String) {
        if (value.startsWith("0;") || value.startsWith("2;")) {
            title = value.substringAfter(';')
            listener?.onTitleChanged(title)
            listener?.onInvalidate()
        }
    }

    // ------------------------------------------------------------- resizing

    fun resize(rows: Int, cols: Int) {
        buffer.resize(rows, cols)
        wrapPending = false
        listener?.onInvalidate()
    }
}
