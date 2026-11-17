package com.yuanhang.ssh.ui.screens

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
import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yuanhang.ssh.rdp.RdpClient
import com.yuanhang.ssh.rdp.ui.RdpCanvas
import com.yuanhang.ssh.terminal.ui.TerminalInputSink
import com.yuanhang.ssh.terminal.ui.TerminalInputView
import com.yuanhang.ssh.viewmodel.AppViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** RDP hardware scan codes. Extended keys are flagged, not prefixed with 0xE0. */
private object Sc {
    const val ESC = 0x01
    const val TAB = 0x0F
    const val BACKSPACE = 0x0E
    const val ENTER = 0x1C
    const val CTRL = 0x1D
    const val ALT = 0x38
    const val LWIN = 0x5B
    const val UP = 0x48
    const val DOWN = 0x50
    const val LEFT = 0x4B
    const val RIGHT = 0x4D
    const val DELETE = 0x53
    const val HOME = 0x47
    const val END = 0x4F
}

/**
 * Microsoft Remote Desktop, dialled directly.
 *
 * The phone connects straight to the Windows box – no SSH session, no port forward – so
 * the configured address has to be reachable from the phone, and the server has to accept
 * non-NLA logons because only TLS security is implemented.
 */
@Composable
fun RdpScreen(
    viewModel: AppViewModel,
    hostId: Long,
    onBack: () -> Unit
) {
    val hosts by viewModel.hosts.collectAsState()
    val host = remember(hosts, hostId) { hosts.find { it.id == hostId } }

    var client by remember { mutableStateOf<RdpClient?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var disconnected by remember { mutableStateOf<String?>(null) }

    var domain by remember(host) { mutableStateOf(host?.rdpDomain ?: "") }
    var username by remember(host) { mutableStateOf(host?.username ?: "") }
    // A saved RDP password is opened once and pre-filled, so a remembered desktop really
    // is one tap away.
    var password by remember(host) {
        mutableStateOf(host?.rdpPasswordEnc?.let { viewModel.unsealSecret(it) } ?: "")
    }
    var connecting by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    var inputView by remember { mutableStateOf<TerminalInputView?>(null) }

    val listener = remember(hostId) {
        object : RdpClient.Listener {
            override fun onFrameInvalidated() { version++ }
            override fun onDesktopSizeChanged(width: Int, height: Int) { version++ }
            override fun onDisconnected(reason: String?) {
                disconnected = reason ?: "已断开"
            }
        }
    }

    fun connect() {
        connecting = true
        error = null
        disconnected = null
        viewModel.viewModelScope.launch {
            viewModel.ensureRdp(hostId, domain, username, password, listener)
                .onSuccess { client = it }
                .onFailure { error = it.message ?: "连接失败" }
            connecting = false
        }
    }

    val live = client
    val failure = error ?: disconnected

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B1524)) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121E30))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Text(host?.name ?: "远程桌面", color = Color.White)
                Spacer(modifier = Modifier.weight(1f))
                if (live != null) {
                    IconButton(onClick = { client = null; connect() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "重连", tint = Color.White)
                    }
                }
            }

            if (live == null) {
                CredentialPanel(
                    subtitle = host?.let { "直连 ${it.host}:${it.port}；服务器需允许非 NLA 连接" }
                        ?: "直连 Windows 远程桌面；服务器需允许非 NLA 连接",
                    domain = domain,
                    onDomainChange = { domain = it },
                    username = username,
                    onUsernameChange = { username = it },
                    password = password,
                    onPasswordChange = { password = it },
                    connecting = connecting,
                    failure = failure,
                    onConnect = { connect() },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    RdpCanvas(client = live, version = version, modifier = Modifier.fillMaxSize())
                }

                // Soft keyboard sink: the same custom InputConnection used by the terminal,
                // which avoids the IME restart/flicker a plain TextField causes.
                TerminalInputSink(
                    focusRequester = focusRequester,
                    onReady = { inputView = it },
                    onCommitText = { text ->
                        for (ch in text) {
                            when (ch) {
                                '\r', '\n' -> live.tapScancode(Sc.ENTER)
                                '\t' -> live.tapScancode(Sc.TAB)
                                else -> live.sendUnicode(ch)
                            }
                        }
                    },
                    onDelete = { count ->
                        repeat(count.coerceAtMost(16)) { live.tapScancode(Sc.BACKSPACE) }
                    },
                    onSequence = { seq -> sendSequence(live, seq) },
                    modifier = Modifier.size(1.dp)
                )

                LaunchedEffect(inputView) {
                    if (inputView == null) return@LaunchedEffect
                    delay(150)
                    inputView?.requestFocus()
                }

                RdpKeyRow(
                    onCtrlAltDel = {
                        live.sendScancode(Sc.CTRL, true)
                        live.sendScancode(Sc.ALT, true)
                        live.sendScancode(Sc.DELETE, true, extended = true)
                        live.sendScancode(Sc.DELETE, false, extended = true)
                        live.sendScancode(Sc.ALT, false)
                        live.sendScancode(Sc.CTRL, false)
                    },
                    onWin = { live.tapScancode(Sc.LWIN, extended = true) },
                    onKey = { sc, ext -> live.tapScancode(sc, ext) }
                )
            }
        }
    }
}

/** Maps the control sequences produced by the keyboard sink onto RDP scan codes. */
private fun sendSequence(client: RdpClient, seq: String) {
    when (seq) {
        "\r" -> client.tapScancode(Sc.ENTER)
        TerminalInputView.KEY_DELETE -> client.tapScancode(Sc.BACKSPACE)
        "\t" -> client.tapScancode(Sc.TAB)
        "\u001B" -> client.tapScancode(Sc.ESC)
        "\u001B[A" -> client.tapScancode(Sc.UP, extended = true)
        "\u001B[B" -> client.tapScancode(Sc.DOWN, extended = true)
        "\u001B[C" -> client.tapScancode(Sc.RIGHT, extended = true)
        "\u001B[D" -> client.tapScancode(Sc.LEFT, extended = true)
        "\u001B[3~" -> client.tapScancode(Sc.DELETE, extended = true)
        "\u001B[H" -> client.tapScancode(Sc.HOME, extended = true)
        "\u001B[F" -> client.tapScancode(Sc.END, extended = true)
    }
}

@Composable
private fun RdpKeyRow(
    onCtrlAltDel: () -> Unit,
    onWin: () -> Unit,
    onKey: (scancode: Int, extended: Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121E30))
            .imePadding()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Button(
            onClick = onCtrlAltDel,
            modifier = Modifier.padding(horizontal = 1.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
        ) {
            Text("Ctrl+Alt+Del", style = MaterialTheme.typography.labelSmall)
        }
        Button(
            onClick = onWin,
            modifier = Modifier.padding(horizontal = 1.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
        ) {
            Text("Win", style = MaterialTheme.typography.labelSmall)
        }
        listOf(
            "Esc" to (Sc.ESC to false),
            "Tab" to (Sc.TAB to false),
            "▲" to (Sc.UP to true),
            "▼" to (Sc.DOWN to true),
            "◀" to (Sc.LEFT to true),
            "▶" to (Sc.RIGHT to true)
        ).forEach { (label, pair) ->
            Button(
                onClick = { onKey(pair.first, pair.second) },
                modifier = Modifier.padding(horizontal = 1.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun CredentialPanel(
    subtitle: String,
    domain: String,
    onDomainChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    connecting: Boolean,
    failure: String?,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("远程桌面 (RDP)", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9AA9BF)
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = domain,
            onValueChange = onDomainChange,
            label = { Text("域（可空）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("密码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        failure?.let {
            Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(onClick = onConnect, enabled = !connecting) {
            if (connecting) CircularProgressIndicator(modifier = Modifier.size(16.dp))
            else Text("连接")
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
