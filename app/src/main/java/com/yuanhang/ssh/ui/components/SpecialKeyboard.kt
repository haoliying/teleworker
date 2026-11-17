package com.yuanhang.ssh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The row of keys a real keyboard would provide: arrows, modifiers and function keys.
 * It sits directly above the soft input method.
 */
@Composable
fun SpecialKeyRow(
    modifier: Modifier = Modifier,
    onForeground: Color = Color.Unspecified,
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    onKey: (SpecialKey) -> Unit,
    onArrow: (String) -> Unit,
    onFunctionKey: (Int) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        listOf(SpecialKey.ESC, SpecialKey.TAB, SpecialKey.CTRL, SpecialKey.ALT).forEach { key ->
            val active = when (key) {
                SpecialKey.CTRL -> ctrlActive
                SpecialKey.ALT -> altActive
                else -> false
            }
            SpecialButton(key.label, onForeground, active) { onKey(key) }
        }
        SpecialButton("▲", onForeground) { onArrow(TerminalKeys.UP) }
        SpecialButton("▼", onForeground) { onArrow(TerminalKeys.DOWN) }
        SpecialButton("◀", onForeground) { onArrow(TerminalKeys.LEFT) }
        SpecialButton("▶", onForeground) { onArrow(TerminalKeys.RIGHT) }
    }
}

@Composable
fun FunctionKeyRow(
    modifier: Modifier = Modifier,
    onForeground: Color = Color.Unspecified,
    onFunctionKey: (Int) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        for (i in 1..12) {
            SpecialButton("F$i", onForeground) { onFunctionKey(i) }
        }
    }
}

@Composable
private fun SpecialButton(
    text: String,
    onForeground: Color = Color.Unspecified,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val colors = if (onForeground == Color.Unspecified) {
        ButtonDefaults.filledTonalButtonColors()
    } else {
        ButtonDefaults.filledTonalButtonColors(contentColor = onForeground)
    }

    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 1.dp),
        colors = colors,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                active -> Color(0xFFFFB300)
                onForeground == Color.Unspecified -> androidx.compose.material3.LocalContentColor.current
                else -> onForeground
            }
        )
    }
}
