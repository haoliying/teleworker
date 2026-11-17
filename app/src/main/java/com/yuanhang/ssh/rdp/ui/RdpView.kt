package com.yuanhang.ssh.rdp.ui

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
import com.yuanhang.ssh.rdp.RdpClient
import com.yuanhang.ssh.vnc.ui.remotePointFromTouch
import kotlin.math.max
import kotlin.math.min

/** Pointer button mask bits, matching the RFB layout the VNC canvas already uses. */
const val RDP_BUTTON_LEFT = 1
const val RDP_BUTTON_MIDDLE = 2
const val RDP_BUTTON_RIGHT = 4

/**
 * Renders the remote desktop and turns gestures into RDP pointer events.
 *
 * A Windows desktop is far larger than a phone screen, so the picture is fit-scaled and
 * letterboxed, then pinch/pan on top of that. Taps map back through the same transform
 * (shared with the VNC canvas) so a touch lands on the pixel the user aimed at.
 */
@Composable
fun RdpCanvas(
    client: RdpClient?,
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
                        session.sendPointerEvent(x, y, RDP_BUTTON_LEFT)
                        session.sendPointerEvent(x, y, 0)
                        onInteract()
                    },
                    onLongPress = { position ->
                        val session = client ?: return@detectTapGestures
                        val (x, y) = remotePointFromTouch(
                            position, size.width, size.height,
                            session.width, session.height, gestureScale, gestureOffset
                        )
                        session.sendPointerEvent(x, y, RDP_BUTTON_RIGHT)
                        session.sendPointerEvent(x, y, 0)
                        onInteract()
                    }
                )
            }
            .pointerInput(client) {
                detectDragGestures { change, _ ->
                    val session = client ?: return@detectDragGestures
                    val (x, y) = remotePointFromTouch(
                        change.position, size.width, size.height,
                        session.width, session.height, gestureScale, gestureOffset
                    )
                    session.sendPointerEvent(x, y, RDP_BUTTON_LEFT)
                    session.sendPointerEvent(x, y, 0)
                    change.consume()
                    onInteract()
                }
            }
    ) {
        val session = client
        drawRect(Color(0xFF0B1524))
        if (session == null) return@Canvas
        if (session.width <= 0 || session.height <= 0) return@Canvas

        if (version < 0) Unit   // touching `version` forces a redraw on new frames

        val bitmap = session.framebuffer()

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
