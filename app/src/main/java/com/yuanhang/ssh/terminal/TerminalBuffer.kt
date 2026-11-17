package com.yuanhang.ssh.terminal

/** Upper bounds on the viewport, so a huge window can never drive an enormous allocation. */
private const val MAX_ROWS = 512
private const val MAX_COLS = 512

/**
 * Screen buffer of the terminal.
 *
 * The buffer keeps a single deque of rows holding both the scrollback history and the
 * visible screen. The last [screenRows] rows of the deque are the visible screen; rows
 * scrolled off the top remain available in history until [maxHistoryLines] is exceeded.
 */
class TerminalBuffer(
    var screenRows: Int = 24,
    var screenCols: Int = 80,
    /**
     * Scrollback depth. Kept moderate because a tablet / unfolded tri-fold viewport can be
     * 300+ columns wide, where every history row costs ~10 bytes per column.
     */
    var maxHistoryLines: Int = 2000
) {

    /** A single row of characters plus their packed styles. */
    class Row(cols: Int) {
        var chars: CharArray = CharArray(cols) { ' ' }
        var styles: LongArray = LongArray(cols) { TextStyle.NORMAL }

        /** Number of trailing cells that are pure blank padding, for cheap trimming. */
        var used = 0

        /**
         * Re-allocate this row to a new width, keeping whatever still fits.
         *
         * Without this, rows created while the screen was narrower keep their old length
         * while [screenCols] grows, and every later read or write past the end throws
         * ArrayIndexOutOfBoundsException – which is exactly what happened when a foldable
         * unfolded to a much wider viewport.
         */
        fun resize(newCols: Int) {
            if (newCols == chars.size) return
            val nextChars = CharArray(newCols) { ' ' }
            val nextStyles = LongArray(newCols) { TextStyle.NORMAL }
            val keep = minOf(chars.size, newCols)
            System.arraycopy(chars, 0, nextChars, 0, keep)
            System.arraycopy(styles, 0, nextStyles, 0, keep)
            chars = nextChars
            styles = nextStyles
            used = used.coerceAtMost(newCols)
        }
    }

    private val lines = ArrayDeque<Row>()

    /** Cursor position, both zero based and relative to the visible screen. */
    var cursorRow = 0
        private set
    var cursorCol = 0
        private set

    /** Top / bottom margin of the active scrolling region (DECSTBM), inclusive. */
    var scrollTop = 0
        private set
    var scrollBottom = screenRows - 1
        private set

    val totalLines: Int get() = lines.size

    init {
        fillScreen()
    }

    // ---------------------------------------------------------------- lines

    private fun newRow(): Row = Row(screenCols)

    private fun fillScreen() {
        while (lines.size < screenRows) lines.addLast(newRow())
    }

    /**
     * Append a new row at the bottom, scrolling the visible screen up by one line.
     * The row that scrolls off is pushed into scrollback history.
     */
    fun scrollUpOneLine() {
        lines.addLast(newRow())
        while (lines.size > maxHistoryLines + screenRows) lines.removeFirst()
    }

    /** Reverse index: insert a blank line at the top of the scrolling region. */
    fun scrollDownOneLine() {
        val at = lines.size - screenRows + scrollTop
        lines.add(at, newRow())
        lines.removeLast()
        while (lines.size > maxHistoryLines + screenRows) lines.removeFirst()
    }

    /** Shared empty row handed out when the deque is momentarily out of sync (reader thread). */
    private val blankRow = Row(0)

    private fun rowAtVisible(row: Int): Row {
        val index = lines.size - screenRows + row
        if (index < 0 || index >= lines.size) return blankRow
        return lines.elementAt(index)
    }

    /** Access a visible row (0 .. screenRows-1). */
    fun getScreenRow(row: Int): Row = rowAtVisible(row)

    /** Access a history row offset from the very beginning of the buffer. */
    fun getHistoryRow(index: Int): Row = lines.elementAt(index)

    /** Total number of rows including scrollback. */
    fun historySize(): Int = lines.size - screenRows

    // -------------------------------------------------------------- cursor

    fun setCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, screenRows - 1)
        cursorCol = col.coerceIn(0, screenCols - 1)
    }

    fun setCursorRow(row: Int) {
        cursorRow = row.coerceIn(0, screenRows - 1)
    }

    fun setCursorCol(col: Int) {
        cursorCol = col.coerceIn(0, screenCols - 1)
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        val t = top.coerceIn(0, screenRows - 1)
        var b = bottom.coerceIn(0, screenRows - 1)
        if (b <= t) b = screenRows - 1
        scrollTop = t
        scrollBottom = b
        // Moving into the region is only meaningful if cursor is inside.
        cursorRow = cursorRow.coerceIn(0, screenRows - 1)
    }

    // --------------------------------------------------------------- edits

    /** Put a single character at an absolute visible position. */
    fun setChar(row: Int, col: Int, char: Char, style: Long) {
        if (row < 0 || row >= screenRows) return
        if (col < 0 || col >= screenCols) return
        val line = rowAtVisible(row)
        if (col >= line.chars.size) return
        line.chars[col] = char
        line.styles[col] = style
        if (col + 1 > line.used) line.used = col + 1
    }

    fun getChar(row: Int, col: Int): Char {
        val line = rowAtVisible(row)
        return if (col in 0 until line.chars.size) line.chars[col] else ' '
    }

    fun getStyle(row: Int, col: Int): Long {
        val line = rowAtVisible(row)
        return if (col in 0 until line.styles.size) line.styles[col] else TextStyle.NORMAL
    }

    /** Erase cells [startCol, endCol) on the given visible row. */
    fun eraseCells(row: Int, startCol: Int, endCol: Int, style: Long) {
        if (row < 0 || row >= screenRows) return
        val from = startCol.coerceIn(0, screenCols)
        val to = endCol.coerceIn(from, screenCols)
        val line = rowAtVisible(row)
        val limit = minOf(to, line.chars.size)
        for (c in from until limit) {
            line.chars[c] = ' '
            line.styles[c] = style
        }
        if (to >= line.used) {
            var u = to
            while (u > 0 && line.chars[u - 1] == ' ') u--
            line.used = u
        }
    }

    /** Erase an entire visible row using the current style. */
    fun eraseRow(row: Int, style: Long) {
        eraseCells(row, 0, screenCols, style)
    }

    fun eraseRows(fromRow: Int, toRow: Int, style: Long) {
        for (r in fromRow.coerceIn(0, screenRows - 1)..toRow.coerceIn(0, screenRows - 1)) {
            eraseRow(r, style)
        }
    }

    /** Insert [count] blank cells at [col] on row [row], shifting the rest right. */
    fun insertCells(row: Int, col: Int, count: Int, style: Long) {
        if (row < 0 || row >= screenRows) return
        val line = rowAtVisible(row)
        val n = count.coerceAtMost(screenCols - col)
        for (i in screenCols - 1 downTo col + n) {
            line.chars[i] = line.chars[i - n]
            line.styles[i] = line.styles[i - n]
        }
        for (i in col until col + n) {
            line.chars[i] = ' '
            line.styles[i] = style
        }
        recomputeUsed(line)
    }

    /** Delete [count] cells at [col] on row [row], shifting the rest left. */
    fun deleteCells(row: Int, col: Int, count: Int, style: Long) {
        if (row < 0 || row >= screenRows) return
        val line = rowAtVisible(row)
        val n = count.coerceAtMost(screenCols - col)
        for (i in col until screenCols - n) {
            line.chars[i] = line.chars[i + n]
            line.styles[i] = line.styles[i + n]
        }
        for (i in screenCols - n until screenCols) {
            line.chars[i] = ' '
            line.styles[i] = style
        }
        recomputeUsed(line)
    }

    /** Insert [count] blank lines at [row] within the scrolling region. */
    fun insertLines(row: Int, count: Int, style: Long) {
        var n = count
        while (n > 0) {
            lines.add(lines.size - screenRows + row, newRow())
            // Drop the line that leaves the scrolling region.
            lines.removeAt(lines.size - screenRows + scrollBottom)
            n--
        }
        while (lines.size > maxHistoryLines + screenRows) lines.removeFirst()
    }

    /** Delete [count] lines at [row] within the scrolling region. */
    fun deleteLines(row: Int, count: Int, style: Long) {
        var n = count
        while (n > 0) {
            lines.removeAt(lines.size - screenRows + row)
            lines.add(lines.size - screenRows + scrollBottom, newRow())
            n--
        }
        while (lines.size > maxHistoryLines + screenRows) lines.removeFirst()
    }

    private fun recomputeUsed(line: Row) {
        var u = screenCols
        while (u > 0 && line.chars[u - 1] == ' ') u--
        line.used = u
    }

    // ------------------------------------------------------------- resizing

    fun resize(newRows: Int, newCols: Int) {
        if (newRows <= 0 || newCols <= 0) return
        // Clamp: a foldable in freeform mode can report viewports wide enough that the
        // naive row/column count would allocate tens of megabytes of line storage.
        val rows = newRows.coerceAtMost(MAX_ROWS)
        val cols = newCols.coerceAtMost(MAX_COLS)
        if (rows == screenRows && cols == screenCols) return
        screenRows = rows
        screenCols = cols
        scrollTop = 0
        scrollBottom = rows - 1
        // Every existing row – visible and scrollback alike – must adopt the new width.
        for (line in lines) line.resize(cols)
        fillScreen()
        cursorRow = cursorRow.coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceIn(0, newCols - 1)
    }

    fun clear(style: Long) {
        lines.clear()
        fillScreen()
        cursorRow = 0
        cursorCol = 0
    }

    /**
     * Extract the whole visible area (with trailing blanks trimmed) as plain text,
     * as well as the logical line containing absolute row [row].
     */
    fun getSelectedLine(row: Int): Row = rowAtVisible(row)

    /**
     * Return lines as text starting from [startRow] to [endRow] inclusive.
     */
    fun getText(startRow: Int, endRow: Int): Row = rowAtVisible(startRow)
}
