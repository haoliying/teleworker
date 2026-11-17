package com.yuanhang.ssh.ui.components

/**
 * Escape sequences emitted for keys a soft keyboard cannot produce.
 *
 * Plus the matching X11 keysyms used when the same physical keys are sent to a
 * VNC session instead of a shell.
 */
object TerminalKeys {

    const val ESC = "\u001B"
    const val TAB = "\t"

    val UP = ESC + "[A"
    val DOWN = ESC + "[B"
    val RIGHT = ESC + "[C"
    val LEFT = ESC + "[D"

    val HOME = ESC + "[H"
    val END = ESC + "[F"
    val PAGE_UP = ESC + "[5~"
    val PAGE_DOWN = ESC + "[6~"
    val INSERT = ESC + "[2~"
    val DELETE = ESC + "[3~"

    val F1 = ESC + "OP"
    val F2 = ESC + "OQ"
    val F3 = ESC + "OR"
    val F4 = ESC + "OS"
    val F5 = ESC + "[15~"
    val F6 = ESC + "[17~"
    val F7 = ESC + "[18~"
    val F8 = ESC + "[19~"
    val F9 = ESC + "[20~"
    val F10 = ESC + "[21~"
    val F11 = ESC + "[23~"
    val F12 = ESC + "[24~"

    val FUNCTION_KEYS = listOf(F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12)

    /**
     * Control modifier. `Ctrl+A` is ASCII 1, `Ctrl+Z` is ASCII 26... which is exactly
     * how the shell expects to receive them.
     */
    fun ctrl(letter: Char): String =
        ((letter.uppercaseChar().code - 'A'.code) + 1).toChar().toString()

    fun alt(letter: Char): String = ESC + letter

    /**
     * Handles a key event coming from a hardware keyboard.
     * Returns null when [code] should be typed verbatim.
     */
    fun fromKeyCode(code: Int, ctrlDown: Boolean, shiftDown: Boolean): String? = null
}

/** Special keys offered on the virtual keyboard toolbar. */
enum class SpecialKey(val label: String, val sequence: String) {
    ESC("ESC", TerminalKeys.ESC),
    TAB("TAB", TerminalKeys.TAB),
    CTRL("CTRL", ""),
    ALT("ALT", ""),
    HOME("HOME", TerminalKeys.HOME),
    END("END", TerminalKeys.END),
    PG_UP("PGUP", TerminalKeys.PAGE_UP),
    PG_DN("PGDN", TerminalKeys.PAGE_DOWN),
    INSERT("INS", TerminalKeys.INSERT),
    DELETE("DEL", TerminalKeys.DELETE)
}

/** Mapping used when the same keys are forwarded over RFB instead. */
enum class VncSpecialKey(val label: String, val keysym: Int) {
    ESC("ESC", 0xFF1B),
    TAB("TAB", 0xFF09),
    HOME("HOME", 0xFF50),
    END("END", 0xFF57),
    PG_UP("PGUP", 0xFF55),
    PG_DN("PGDN", 0xFF56),
    INSERT("INS", 0xFF63),
    DELETE("DEL", 0xFFFF),
    SUPER("SUPER", 0xFFEB)
}
