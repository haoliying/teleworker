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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.yuanhang.ssh.ui.components.VncSpecialKey
import com.yuanhang.ssh.vnc.VncClient
import com.yuanhang.ssh.vnc.ui.VncCanvas
import androidx.lifecycle.viewModelScope
import com.yuanhang.ssh.viewmodel.AppViewModel
import kotlinx.coroutines.launch

/**
 * Remote desktop over plain VNC.
 *
 * The phone dials the VNC server directly – no SSH session, no port forward – so the
 * address configured on the host has to be reachable from the phone itself.
 */
@Composable
fun VncScreen(
    viewModel: AppViewModel,
    hostId: Long,
    onBack: () -> Unit
) {
    val hosts by viewModel.hosts.collectAsState()
    val host = remember(hosts, hostId) { hosts.find { it.id == hostId } }

    var client by remember { mutableStateOf<VncClient?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var disconnected by remember { mutableStateOf<String?>(null) }
    // A saved VNC password is opened once and pre-filled, so a remembered desktop really
    // is one tap away.
    var password by remember(host) {
        mutableStateOf(host?.vncPasswordEnc?.let { viewModel.unsealSecret(it) } ?: "")
    }
    var connecting by remember { mutableStateOf(false) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val listener = remember(hostId) {
        object : VncClient.Listener {
            override fun onFrameInvalidated() { version++ }
            override fun onDesktopSizeChanged(width: Int, height: Int) { version++ }
            override fun onDisconnected(reason: String?) {
                disconnected = reason ?: "已断开"
            }
        }
    }

    fun connect(pwd: String) {
        connecting = true
        error = null
        disconnected = null
        viewModel.viewModelScope.launch {
            viewModel.ensureVnc(hostId, pwd.ifBlank { null }, listener)
                .onSuccess { client = it }
                .onFailure { error = it.message ?: "连接失败" }
            connecting = false
        }
    }

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
                IconButton(onClick = {
                    client = null
                    connect(password)
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "重连", tint = Color.White)
                }
            }

            val live = client
            val failure = error ?: disconnected

            if (live == null) {
                AuthPanel(
                    subtitle = host?.let { "直连 ${it.host}:${it.port}" }
                        ?: "直连 VNC 服务，无需 SSH 隧道",
                    password = password,
                    onPasswordChange = { password = it },
                    connecting = connecting,
                    failure = failure,
                    onConnect = { connect(password) },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    VncCanvas(client = live, version = version, modifier = Modifier.fillMaxSize())
                }

                var fieldValue by remember { mutableStateOf(TextFieldValue(" ")) }
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { next ->
                        val inserted = next.text.removePrefix(" ")
                        if (next.text.isEmpty()) {
                            live.sendKeyEvent(0xFF08, true)
                            live.sendKeyEvent(0xFF08, false)
                        } else if (inserted.isNotEmpty()) {
                            for (ch in inserted) {
                                val keysym = keysymFor(ch)
                                live.sendKeyEvent(keysym, true)
                                live.sendKeyEvent(keysym, false)
                            }
                        }
                        fieldValue = TextFieldValue(" ")
                    },
                    modifier = Modifier
                        .size(1.dp)
                        .focusRequester(focusRequester)
                )

                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                    keyboardController?.hide()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121E30))
                        .imePadding()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    VncSpecialKey.entries.forEach { key ->
                        Button(
                            onClick = {
                                live.sendKeyEvent(key.keysym, true)
                                live.sendKeyEvent(key.keysym, false)
                            },
                            modifier = Modifier.padding(horizontal = 1.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                        ) {
                            Text(key.label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthPanel(
    subtitle: String,
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
        Text(
            text = "远程桌面",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9AA9BF)
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("VNC 口令") },
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
    }
}

/** VNC carries X11 keysyms, not characters – printable ASCII maps one to one. */
private fun keysymFor(ch: Char): Int = when (ch) {
    '\n' -> 0xFF0D
    '\t' -> 0xFF09
    else -> ch.code
}
