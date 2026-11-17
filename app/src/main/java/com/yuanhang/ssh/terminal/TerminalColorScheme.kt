package com.yuanhang.ssh.terminal

import androidx.compose.ui.graphics.Color

/**
 * A terminal colour scheme: the two colours every unstyled cell falls back to.
 *
 * Only the default foreground/background are swapped; the 256-colour palette stays put so
 * that `ls --color`, `vim` syntax highlighting and friends keep working in every scheme.
 */
data class TerminalColorScheme(
    val id: String,
    val label: String,
    /** Packed ARGB, as [android.graphics.Color] expects. */
    val backgroundArgb: Int,
    val foregroundArgb: Int
) {
    val background: Color get() = Color(backgroundArgb)
    val foreground: Color get() = Color(foregroundArgb)

    fun apply() = ColorPalette.setDefaults(foregroundArgb, backgroundArgb)
}

object TerminalColorSchemes {

    val CLASSIC = TerminalColorScheme(
        id = "classic",
        label = "经典黑",
        backgroundArgb = 0xFF000000.toInt(),
        foregroundArgb = 0xFFCCCCCC.toInt()
    )

    val MIDNIGHT = TerminalColorScheme(
        id = "midnight",
        label = "深夜蓝",
        backgroundArgb = 0xFF0D1117.toInt(),
        foregroundArgb = 0xFFE6EDF3.toInt()
    )

    val MONOKAI = TerminalColorScheme(
        id = "monokai",
        label = "Monokai",
        backgroundArgb = 0xFF272822.toInt(),
        foregroundArgb = 0xFFF8F8F2.toInt()
    )

    val DRACULA = TerminalColorScheme(
        id = "dracula",
        label = "Dracula",
        backgroundArgb = 0xFF282A36.toInt(),
        foregroundArgb = 0xFFF8F8F2.toInt()
    )

    val SOLARIZED_DARK = TerminalColorScheme(
        id = "solarized_dark",
        label = "Solarized Dark",
        backgroundArgb = 0xFF002B36.toInt(),
        foregroundArgb = 0xFF839496.toInt()
    )

    val MATRIX = TerminalColorScheme(
        id = "matrix",
        label = "复古绿",
        backgroundArgb = 0xFF000000.toInt(),
        foregroundArgb = 0xFF33FF33.toInt()
    )

    val PAPER = TerminalColorScheme(
        id = "paper",
        label = "亮色纸白",
        backgroundArgb = 0xFFF5F5F5.toInt(),
        foregroundArgb = 0xFF1F2328.toInt()
    )

    val ALL: List<TerminalColorScheme> = listOf(
        CLASSIC, MIDNIGHT, MONOKAI, DRACULA, SOLARIZED_DARK, MATRIX, PAPER
    )

    val DEFAULT = CLASSIC

    fun byId(id: String?): TerminalColorScheme =
        ALL.find { it.id == id } ?: DEFAULT
}
