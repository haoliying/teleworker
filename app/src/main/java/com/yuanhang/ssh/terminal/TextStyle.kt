package com.yuanhang.ssh.terminal

/**
 * Packed text attributes for a single character cell.
 *
 * Layout of the [Long] value:
 *  bits 0..8   foreground colour index (0..255 palette, or special DEFAULT_* indices)
 *  bits 9..17  background colour index
 *  bits 18..25 effect flags
 *
 * Each colour channel needs **9 bits**, not 8: the palette has 256 real colours plus the
 * two "theme default" sentinels (256/257). Packing them into 8 bits silently truncated
 * 256 -> 0 (black) and 257 -> 1 (red), which painted every blank cell red.
 */
object TextStyle {

    /** Bits per colour channel. Must be wide enough to hold [INDEX_BACKGROUND]. */
    private const val CHANNEL_BITS = 9
    private const val CHANNEL_MASK = (1 shl CHANNEL_BITS) - 1   // 0x1FF
    private const val EFFECT_SHIFT = CHANNEL_BITS * 2           // 18

    /** Palette index meaning "use the theme default foreground". */
    const val INDEX_FOREGROUND = 256

    /** Palette index meaning "use the theme default background". */
    const val INDEX_BACKGROUND = 257

    /** Meaning "leave this channel untouched" (used by SGR 38/48 sub-parameters only). */
    const val INDEX_UNCHANGED = -1

    const val EFFECT_BOLD = 1 shl 0
    const val EFFECT_DIM = 1 shl 1
    const val EFFECT_ITALIC = 1 shl 2
    const val EFFECT_UNDERLINE = 1 shl 3
    const val EFFECT_BLINK = 1 shl 4
    const val EFFECT_REVERSE = 1 shl 5
    const val EFFECT_INVISIBLE = 1 shl 6
    const val EFFECT_STRIKETHROUGH = 1 shl 7

    /** Default style: theme colours, no effects. */
    const val NORMAL: Long =
        INDEX_FOREGROUND.toLong() or (INDEX_BACKGROUND.toLong() shl CHANNEL_BITS)

    fun encode(fgIndex: Int, bgIndex: Int, effects: Int): Long =
        (fgIndex.toLong() and CHANNEL_MASK.toLong()) or
            ((bgIndex.toLong() and CHANNEL_MASK.toLong()) shl CHANNEL_BITS) or
            ((effects.toLong() and 0xFFL) shl EFFECT_SHIFT)

    fun foregroundIndex(encoded: Long): Int = (encoded and CHANNEL_MASK.toLong()).toInt()

    fun backgroundIndex(encoded: Long): Int =
        ((encoded shr CHANNEL_BITS) and CHANNEL_MASK.toLong()).toInt()

    fun effects(encoded: Long): Int = ((encoded shr EFFECT_SHIFT) and 0xFFL).toInt()

    fun hasEffect(encoded: Long, effect: Int): Boolean = effects(encoded) and effect != 0

    fun withForeground(encoded: Long, fgIndex: Int): Long =
        (encoded and (CHANNEL_MASK.toLong() or (0xFFL shl EFFECT_SHIFT)).inv()) or
            (fgIndex.toLong() and CHANNEL_MASK.toLong())

    fun withBackground(encoded: Long, bgIndex: Int): Long =
        (encoded and ((CHANNEL_MASK.toLong() shl CHANNEL_BITS) or (0xFFL shl EFFECT_SHIFT)).inv()) or
            ((bgIndex.toLong() and CHANNEL_MASK.toLong()) shl CHANNEL_BITS)

    fun withEffects(encoded: Long, effects: Int): Long =
        (encoded and (0xFFL shl EFFECT_SHIFT).inv()) or
            ((effects.toLong() and 0xFFL) shl EFFECT_SHIFT)

    /** Apply effect bits additively, as CSI sequence does. */
    fun orEffects(encoded: Long, effect: Int): Long =
        encoded or ((effect.toLong() and 0xFFL) shl EFFECT_SHIFT)

    /** Remove effect bits. */
    fun andNotEffects(encoded: Long, effect: Int): Long =
        encoded and (((effect.toLong() and 0xFFL) shl EFFECT_SHIFT)).inv()
}

/**
 * Standard xterm 256-colour palette (RGB packed as 0xAARRGGBB).
 * The first 16 colours are intentionally bright enough to read on a phone screen.
 */
object ColorPalette {

    val COLORS: IntArray = IntArray(258).also { arr ->

        // Standard + high intensity colours (0..15)
        val base = intArrayOf(
            0xFF000000.toInt(), // 0 black
            0xFFCD3131.toInt(), // 1 red
            0xFF0DBC79.toInt(), // 2 green
            0xFFE5E510.toInt(), // 3 yellow
            0xFF2472C8.toInt(), // 4 blue
            0xFFBC3FBC.toInt(), // 5 magenta
            0xFF11A8CD.toInt(), // 6 cyan
            0xFFE5E5E5.toInt(), // 7 white
            0xFF666666.toInt(), // 8 bright black
            0xFFF14C4C.toInt(), // 9 bright red
            0xFF23D18B.toInt(), // 10 bright green
            0xFFF5F543.toInt(), // 11 bright yellow
            0xFF3B8EEA.toInt(), // 12 bright blue
            0xFFD670D6.toInt(), // 13 bright magenta
            0xFF29B8DB.toInt(), // 14 bright cyan
            0xFFF5F5F5.toInt()  // 15 bright white
        )
        System.arraycopy(base, 0, arr, 0, base.size)

        // 6x6x6 colour cube (16..231)
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        var index = 16
        for (r in 0..5) {
            for (g in 0..5) {
                for (b in 0..5) {
                    arr[index++] = pack(levels[r], levels[g], levels[b])
                }
            }
        }

        // Grey ramp (232..255)
        var level = 8
        while (level <= 238) {
            arr[index++] = pack(level, level, level)
            level += 10
        }

        // Write the two "theme default" slots directly here. Calling setDefaults()
        // would touch the COLORS field, which is still null at this point (the
        // .also lambda runs before the field is assigned), and the very first
        // access – AppViewModel bringing the saved scheme up – would throw
        // ExceptionInInitializerError and crash the app on launch.
        arr[TextStyle.INDEX_FOREGROUND] = 0xFFE6EDF3.toInt()
        arr[TextStyle.INDEX_BACKGROUND] = 0xFF0D1117.toInt()
    }

    /**
     * Overwrite the two "theme default" slots.
     *
     * Everything that asks for the default foreground/background (blank cells, cursor,
     * SGR 39/49) reads these entries, so switching a colour scheme is a two-slot write.
     */
    @Synchronized
    fun setDefaults(foreground: Int, background: Int) {
        COLORS[TextStyle.INDEX_FOREGROUND] = foreground
        COLORS[TextStyle.INDEX_BACKGROUND] = background
    }

    @Synchronized
    fun defaultForeground(): Int = COLORS[TextStyle.INDEX_FOREGROUND]

    @Synchronized
    fun defaultBackground(): Int = COLORS[TextStyle.INDEX_BACKGROUND]

    private fun pack(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
