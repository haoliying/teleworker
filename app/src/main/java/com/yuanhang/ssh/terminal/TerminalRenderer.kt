package com.yuanhang.ssh.terminal

import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.min

/**
 * Draws a [TerminalBuffer] onto a native canvas.
 *
 * Text is emitted as contiguous runs sharing identical attributes rather than one
 * `drawText` per character, which keeps a full 120x40 repaint to a few hundred calls.
 */
class TerminalRenderer {

    companion object {
        /** Default background used to clear the canvas. */
        const val BG_INDEX = TextStyle.INDEX_BACKGROUND
        const val FG_INDEX = TextStyle.INDEX_FOREGROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val italicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    var charWidth: Float = 8f
        private set
    var lineHeight: Float = 16f
        private set
    var fontSizePx: Float = 14f
        private set

    /** Recompute glyph metrics after a font size change. Returns the new char width. */
    fun setFontSize(sizePx: Float): Pair<Float, Float> {
        fontSizePx = sizePx
        textPaint.textSize = sizePx
        boldPaint.textSize = sizePx
        italicPaint.textSize = sizePx
        charWidth = measureMonospaceWidth(sizePx)
        lineHeight = (sizePx * 1.25f)
        return charWidth to lineHeight
    }

    private fun measureMonospaceWidth(sizePx: Float): Float {
        val p = Paint(textPaint)
        p.textSize = sizePx
        return p.measureText("M")
    }

    /**
     * Render the visible screen.
     *
     * @param canvas          native canvas to draw into (Compose's `drawContext.canvas.nativeCanvas`)
     * @param buffer          screen contents
     * @param contentWidth    width available, used to fill leftover background
     * @param contentHeight   height available
     * @param cursorVisible   whether to paint the block cursor
     */
    fun draw(
        canvas: android.graphics.Canvas,
        buffer: TerminalBuffer,
        contentWidth: Float,
        contentHeight: Float,
        cursorVisible: Boolean,
        defBg: Int = ColorPalette.defaultBackground(),
        defFg: Int = ColorPalette.defaultForeground()
    ) {
        val cols = buffer.screenCols
        val rows = buffer.screenRows

        canvas.drawColor(defBg)
        // Fill any area taller/wider than the character grid too.
        if (contentHeight > rows * lineHeight) {
            canvas.drawRect(
                0f, rows * lineHeight, contentWidth, contentHeight,
                bgPaint.apply { color = defBg }
            )
        }

        for (row in 0 until rows) {
            val line = buffer.getScreenRow(row)
            // Defensive: never read past the row's own length, whatever screenCols says.
            val lineCols = minOf(cols, line.chars.size, line.styles.size)
            if (lineCols <= 0) continue

            val top = row * lineHeight
            val baseline = top + lineHeight - (lineHeight - fontSizePx) / 2f

            var col = 0
            while (col < lineCols) {
                val style = line.styles[col]
                val ch = line.chars[col]

                if (ch == ' ' && styleBitsDefault(style)) {
                    col++
                    continue
                }

                // Group the run.
                var end = col + 1
                while (end < lineCols && line.styles[end] == style) end++

                val (fgIdx, bgIdx) = resolveColors(style)
                val fg = ColorPalette.COLORS.getOrElse(fgIdx) { defFg }
                val bg = ColorPalette.COLORS.getOrElse(bgIdx) { defBg }

                val left = col * charWidth
                val right = end * charWidth

                if (bg != defBg) {
                    bgPaint.color = bg
                    canvas.drawRect(left, top, right, top + lineHeight, bgPaint)
                }

                if (TextStyle.hasEffect(style, TextStyle.EFFECT_INVISIBLE)) {
                    col = end
                    continue
                }

                val paint = when {
                    TextStyle.hasEffect(style, TextStyle.EFFECT_BOLD) -> boldPaint
                    TextStyle.hasEffect(style, TextStyle.EFFECT_ITALIC) -> italicPaint
                    else -> textPaint
                }
                val prevFlags = paint.flags
                if (TextStyle.hasEffect(style, TextStyle.EFFECT_UNDERLINE)) {
                    paint.flags = prevFlags or Paint.UNDERLINE_TEXT_FLAG
                }
                if (TextStyle.hasEffect(style, TextStyle.EFFECT_STRIKETHROUGH)) {
                    paint.flags = paint.flags or Paint.STRIKE_THRU_TEXT_FLAG
                }
                paint.color = fg

                val text = String(line.chars, col, end - col)
                canvas.drawText(text, 0, text.length, left, baseline, paint)
                paint.flags = prevFlags

                col = end
            }
        }

        if (cursorVisible && buffer.cursorRow in 0 until rows) {
            cursorPaint.color = defFg
            cursorPaint.alpha = 190
            val cx = buffer.cursorCol * charWidth
            val cy = buffer.cursorRow * lineHeight
            canvas.drawRect(cx, cy, cx + charWidth, cy + lineHeight, cursorPaint)
        }
    }

    /** Blanks with the default style need no painted run at all. */
    private fun styleBitsDefault(style: Long): Boolean =
        TextStyle.effects(style) == 0 &&
            TextStyle.foregroundIndex(style) == FG_INDEX &&
            TextStyle.backgroundIndex(style) == BG_INDEX

    /** Applies reverse-video by swapping the two colour channels. */
    private fun resolveColors(style: Long): Pair<Int, Int> {
        val fg = TextStyle.foregroundIndex(style)
        val bg = TextStyle.backgroundIndex(style)
        return if (TextStyle.hasEffect(style, TextStyle.EFFECT_REVERSE)) bg to fg else fg to bg
    }
}
