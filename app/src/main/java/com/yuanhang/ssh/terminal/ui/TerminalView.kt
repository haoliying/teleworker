package com.yuanhang.ssh.terminal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuanhang.ssh.terminal.TerminalBuffer
import com.yuanhang.ssh.terminal.TerminalRenderer
import kotlinx.coroutines.delay

/** Guards against absurd viewport sizes (foldables, freeform windows) allocating huge buffers. */
private const val MAX_TERM_ROWS = 512
private const val MAX_TERM_COLS = 512

/**
 * Compose bridge for [TerminalRenderer].
 *
 * Recomposes whenever [version] changes, which is how shell output (which arrives on a
 * background thread) reaches the screen.
 *
 * The viewport size is reported from [Modifier.onSizeChanged] rather than from inside the
 * draw lambda: resizing resizes the terminal buffer and sends a PTY size change to the
 * server, and doing either during the draw phase caused a redraw/resize feedback loop that
 * killed the app when a foldable opened or the screen rotated.
 */
@Composable
fun TerminalView(
    buffer: TerminalBuffer,
    version: Int,
    fontSizeSp: Int,
    modifier: Modifier = Modifier,
    colorScheme: com.yuanhang.ssh.terminal.TerminalColorScheme =
        com.yuanhang.ssh.terminal.TerminalColorSchemes.DEFAULT,
    onViewportChanged: (rows: Int, cols: Int) -> Unit = { _, _ -> },
    onTap: () -> Unit = {}
) {
    val renderer = remember { TerminalRenderer() }
    val density = LocalDensity.current

    // Per-instance cache: a top-level static would leak across terminal instances and
    // leave a freshly created renderer stuck on its default glyph metrics.
    var configured by remember { mutableStateOf<Pair<Int, Float>?>(null) }
    val applied = configured
    if (applied == null || applied.first != fontSizeSp || applied.second != density.density) {
        renderer.setFontSize(with(density) { fontSizeSp.sp.toPx() })
        configured = fontSizeSp to density.density
    }

    var viewport by remember { mutableStateOf(IntSize.Zero) }

    // Debounced: a foldable unfolding reports dozens of intermediate sizes. Because the
    // effect is keyed on the viewport, each change restarts the coroutine and only the
    // final settled size ever reaches the server.
    LaunchedEffect(viewport, fontSizeSp, density.density) {
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        delay(120)
        val charWidth = renderer.charWidth
        val lineHeight = renderer.lineHeight
        if (charWidth <= 0f || lineHeight <= 0f) return@LaunchedEffect
        val rows = (viewport.height / lineHeight).toInt().coerceIn(1, MAX_TERM_ROWS)
        val cols = (viewport.width / charWidth).toInt().coerceIn(1, MAX_TERM_COLS)
        onViewportChanged(rows, cols)
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            }
    ) {
        // Touching `version` is what invalidates the drawing when the buffer changes.
        if (version < 0) Unit

        renderer.draw(
            canvas = drawContext.canvas.nativeCanvas,
            buffer = buffer,
            contentWidth = size.width,
            contentHeight = size.height,
            cursorVisible = true,
            defBg = colorScheme.backgroundArgb,
            defFg = colorScheme.foregroundArgb
        )
    }
}

/**
 * The hidden text field that lets the soft keyboard talk to the shell.
 *
 * It is deliberately zero-sized: the user never sees it, but it holds input focus so
 * keystrokes arrive as `onValueChange` deltas.
 */
@Composable
fun rememberTerminalMetrics(fontSizeSp: Int): Pair<Double, Double> {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var metrics by remember(fontSizeSp) {
        mutableStateOf(measureMike(density, fontSizeSp))
    }
    return metrics
}

private fun measureMike(density: Density, fontSizeSp: Int): Pair<Double, Double> {
    val paint = android.graphics.Paint().apply {
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = with(density) { fontSizeSp.sp.toPx() }
    }
    val w = paint.measureText("M").toDouble()
    return (w / with(density) { 1.dp.toPx() }).let { it to it * 1.25 }
}
