package com.yuanhang.ssh.ui.screens

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yuanhang.ssh.ssh.ShellSession
import com.yuanhang.ssh.terminal.ui.TerminalInputSink
import com.yuanhang.ssh.terminal.ui.TerminalInputView
import com.yuanhang.ssh.terminal.ui.TerminalView
import com.yuanhang.ssh.ui.components.FunctionKeyRow
import com.yuanhang.ssh.ui.components.SpecialKey
import com.yuanhang.ssh.ui.components.SpecialKeyRow
import com.yuanhang.ssh.ui.components.TerminalKeys
import com.yuanhang.ssh.viewmodel.AppViewModel
import kotlinx.coroutines.delay

/**
 * The character interface: a VT100 screen bound to a remote shell.
 *
 * Keystrokes are collected by [TerminalInputSink], a focus holder that speaks the
 * InputConnection protocol directly to the IME, and forwarded verbatim to the server.
 */
@Composable
fun TerminalScreen(
    viewModel: AppViewModel,
    hostId: Long,
    onOpenSftp: () -> Unit,
    onOpenVnc: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    val hosts by viewModel.hosts.collectAsState()
    val host = remember(hosts, hostId) { hosts.find { it.id == hostId } }
    val scheme by viewModel.colorScheme.collectAsState()
    val defaultFont by viewModel.defaultFontSize.collectAsState()

    var session by remember { mutableStateOf<ShellSession?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var fontSizeSp by remember(host, defaultFont) {
        mutableIntStateOf(host?.fontSize ?: defaultFont)
    }

    var ctrlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    var inputView by remember { mutableStateOf<TerminalInputView?>(null) }
    val context = LocalContext.current

    // Bring the session up, then repaint whenever the emulator reports new output.
    LaunchedEffect(hostId) {
        viewModel.ensureShell(hostId)
            .onSuccess { session = it }
            .onFailure { error = it.message }
    }

    LaunchedEffect(session) {
        val live = session ?: return@LaunchedEffect
        live.invalidations.collect { version++ }
    }

    /**
     * The soft keyboard only types into a focused view, and tapping a toolbar or
     * function-key button steals that focus. Everything that can move focus calls this
     * to hand it straight back to the input sink.
     */
    fun regainFocus() {
        val view = inputView
        if (view != null) {
            view.requestFocus()
            view.post {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    // Wait until the input sink actually exists before asking for the keyboard; opening it
    // any earlier leaves it attached to nothing and every keystroke is swallowed.
    LaunchedEffect(inputView) {
        if (inputView == null) return@LaunchedEffect
        delay(150)
        regainFocus()
    }

    // A colour-scheme change has to reach an idle screen too, not just the next frame.
    LaunchedEffect(scheme) { version++ }

    val onBackground = scheme.background
    val onForeground = scheme.foreground

    Surface(modifier = Modifier.fillMaxSize(), color = onBackground) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(onBackground)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = onForeground)
                }
                Text(
                    text = host?.name ?: "终端",
                    color = onForeground,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { if (fontSizeSp > AppViewModel.MIN_FONT_SIZE) fontSizeSp-- }) {
                    Icon(Icons.Default.Remove, contentDescription = "缩小字体", tint = onForeground)
                }
                IconButton(onClick = { if (fontSizeSp < AppViewModel.MAX_FONT_SIZE) fontSizeSp++ }) {
                    Icon(Icons.Default.Add, contentDescription = "放大字体", tint = onForeground)
                }
                IconButton(onClick = onOpenSftp) {
                    Icon(Icons.Default.Terminal, contentDescription = "SFTP", tint = onForeground)
                }
                IconButton(onClick = onOpenVnc) {
                    Icon(Icons.Default.Tv, contentDescription = "远程桌面", tint = onForeground)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置", tint = onForeground)
                }
                IconButton(onClick = {
                    session = null
                    error = null
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "重连", tint = onForeground)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val live = session
                if (live != null) {
                    TerminalView(
                        buffer = live.terminal,
                        version = version,
                        fontSizeSp = fontSizeSp,
                        colorScheme = scheme,
                        modifier = Modifier.fillMaxSize(),
                        onViewportChanged = { rows, cols -> live.resize(rows, cols) },
                        onTap = { regainFocus() }
                    )
                } else if (error != null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("连接失败：$error", color = onForeground)
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material3.Button(onClick = { error = null }) {
                            Text("重试")
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = onForeground)
                    }
                }
            }

            // Invisible input sink for the soft keyboard.
            val live = session
            if (live != null) {
                TerminalInputSink(
                    focusRequester = focusRequester,
                    modifierState = { ctrlDown to altDown },
                    onReady = { inputView = it },
                    onCommitText = { text ->
                        live.write(text)
                        ctrlDown = false
                        altDown = false
                    },
                    onDelete = { count -> repeat(count) { live.write(TerminalInputView.KEY_DELETE) } },
                    onSequence = { live.write(it) },
                    modifier = Modifier.size(1.dp)
                )

                SpecialKeyRow(
                    modifier = Modifier.background(onBackground),
                    onForeground = onForeground,
                    ctrlActive = ctrlDown,
                    altActive = altDown,
                    onKey = { key ->
                        when (key) {
                            SpecialKey.CTRL -> ctrlDown = !ctrlDown
                            SpecialKey.ALT -> altDown = !altDown
                            SpecialKey.TAB -> live.write(TerminalKeys.TAB)
                            SpecialKey.ESC -> live.write(TerminalKeys.ESC)
                            else -> live.write(key.sequence)
                        }
                        regainFocus()
                    },
                    onArrow = {
                        live.write(it)
                        regainFocus()
                    },
                    onFunctionKey = { index ->
                        live.write(TerminalKeys.FUNCTION_KEYS[index - 1])
                        regainFocus()
                    }
                )
                FunctionKeyRow(
                    modifier = Modifier
                        .background(onBackground)
                        .imePadding(),
                    onForeground = onForeground,
                    onFunctionKey = { index ->
                        live.write(TerminalKeys.FUNCTION_KEYS[index - 1])
                        regainFocus()
                    }
                )
            }
        }
    }
}
