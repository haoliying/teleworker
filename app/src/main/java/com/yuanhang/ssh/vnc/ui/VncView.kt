package com.yuanhang.ssh.vnc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.yuanhang.ssh.vnc.VncClient
import kotlin.math.max
import kotlin.math.min

/** RFB button mask bits – see RFB 3.8 §5.5.1. */
const val VNC_BUTTON_LEFT = 1
const val VNC_BUTTON_MIDDLE = 2
const val VNC_BUTTON_RIGHT = 4

/**
 * Shows the remote desktop and turns gestures into pointer events.
 *
 * The picture is letterboxed to fit the viewport; pinch and pan let the user reach
 * regions of a desktop that are larger than the phone screen.
 */
@Composable
fun VncCanvas(
    client: VncClient?,
    version: Int,
    modifier: Modifier = Modifier,
    onInteract: () -> Unit = {}
) {
    var gestureScale by remember { mutableFloatStateOf(1f) }
    var gestureOffset by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = modifier
            .pointerInput(client) {
                detectTransformGestures { _, pan, zoom, _ ->
                    gestureScale = (gestureScale * zoom).coerceIn(0.2f, 6f)
                    gestureOffset += pan
                    onInteract()
                }
            }
            .pointerInput(client) {
                detectTapGestures(
                    onTap = { position ->
                        val session = client ?: return@detectTapGestures
                        val (x, y) = remotePointFromTouch(
                            position, size.width, size.height,
                            session.width, session.height, gestureScale, gestureOffset
                        )
                        session.sendPointerEvent(x, y, VNC_BUTTON_LEFT)
                        session.sendPointerEvent(x, y, 0)
                        onInteract()
                    },
                    onLongPress = { position ->
                        val session = client ?: return@detectTapGestures
                        val (x, y) = remotePointFromTouch(
                            position, size.width, size.height,
                            session.width, session.height, gestureScale, gestureOffset
                        )
                        session.sendPointerEvent(x, y, VNC_BUTTON_RIGHT)
                        session.sendPointerEvent(x, y, 0)
                        onInteract()
                    }
                )
            }
            .pointerInput(client) {
                detectDragGestures { change, dragAmount ->
                    val session = client ?: return@detectDragGestures
                    val (x, y) = remotePointFromTouch(
                        change.position, size.width, size.height,
                        session.width, session.height, gestureScale, gestureOffset
                    )
                    session.sendPointerEvent(x, y, VNC_BUTTON_LEFT)
                    session.sendPointerEvent(x, y, 0)
                    change.consume()
                    onInteract()
                }
            }
    ) {
        val session = client
        val bitmap = session?.framebuffer()

        drawRect(Color(0xFF0B1524))
        if (session == null || bitmap == null) return@Canvas
        // A zero-sized desktop would make the fit scale infinite and the transform NaN.
        if (session.width <= 0 || session.height <= 0) return@Canvas

        if (version < 0) Unit   // touching `version` forces a redraw on new frames

        val baseScale = min(
            size.width / session.width.toFloat(),
            size.height / session.height.toFloat()
        )
        val effective = (baseScale * gestureScale).coerceAtMost(6f)
        val destW = session.width * effective
        val destH = session.height * effective
        val dx = (size.width - destW) / 2f + gestureOffset.x
        val dy = (size.height - destH) / 2f + gestureOffset.y

        withTransform({
            translate(dx, dy)
            scale(effective, effective, Offset.Zero)
        }) {
            drawImage(
                image = bitmap.asImageBitmap(),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(max(1, session.width), max(1, session.height))
            )
        }
    }
}

/**
 * Maps a screen touch to remote desktop coordinates, undoing the letterbox transform.
 */
fun remotePointFromTouch(
    touch: Offset,
    viewportWidth: Int,
    viewportHeight: Int,
    remoteWidth: Int,
    remoteHeight: Int,
    gestureScale: Float,
    gestureOffset: Offset
): Pair<Int, Int> {
    if (viewportWidth <= 0 || viewportHeight <= 0 || remoteWidth <= 0 || remoteHeight <= 0) {
        return 0 to 0
    }
    val baseScale = min(
        viewportWidth / remoteWidth.toFloat(),
        viewportHeight / remoteHeight.toFloat()
    )
    val effective = baseScale * gestureScale
    val destW = remoteWidth * effective
    val destH = remoteHeight * effective
    val dx = (viewportWidth - destW) / 2f + gestureOffset.x
    val dy = (viewportHeight - destH) / 2f + gestureOffset.y

    val rx = ((touch.x - dx) / effective).toInt().coerceIn(0, remoteWidth - 1)
    val ry = ((touch.y - dy) / effective).toInt().coerceIn(0, remoteHeight - 1)
    return rx to ry
}
