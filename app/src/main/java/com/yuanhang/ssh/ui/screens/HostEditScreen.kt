package com.yuanhang.ssh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.yuanhang.ssh.data.AuthType
import com.yuanhang.ssh.data.HostEntity
import com.yuanhang.ssh.data.HostType
import com.yuanhang.ssh.viewmodel.AppViewModel

/**
 * First step of creating a host: pick which protocol it is for.
 *
 * SSH / SFTP / VNC / RDP need different credentials and open different screens, so they
 * are separate entries rather than four sections of one long form.
 */
@Composable
fun HostTypePickerScreen(
    onPick: (HostType) -> Unit,
    onBack: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("返回")
                }
                Text("选择主机类型", style = MaterialTheme.typography.titleMedium)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "一台主机只属于一种协议，保存后直接打开对应界面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HostType.entries.forEach { type ->
                    TypeCard(type = type, onPick = { onPick(type) })
                }
            }
        }
    }
}

@Composable
private fun TypeCard(type: HostType, onPick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(type.icon(), contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(type.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    type.blurb(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "${type.defaultPort}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Create / edit form for a remembered host.
 *
 * Only the fields relevant to the host's [HostType] are shown. Passwords, key passphrases
 * and desktop passwords are held in memory here and sealed with the Keystore key inside
 * [AppViewModel.saveHost] before they reach the database.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    viewModel: AppViewModel,
    hostId: Long,
    typeArg: String?,
    onSaveAndConnect: (Long, HostType) -> Unit,
    onBack: () -> Unit
) {
    val hosts by viewModel.hosts.collectAsState()
    val keys by viewModel.keys.collectAsState()

    val existing = remember(hosts, hostId) { hosts.find { it.id == hostId } }
    val type = remember(existing, typeArg) {
        existing?.let { HostType.of(it.type) } ?: HostType.of(typeArg)
    }

    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var address by remember(existing) { mutableStateOf(existing?.host ?: "") }
    var port by remember(existing, type) {
        mutableStateOf(existing?.port?.toString() ?: type.defaultPort.toString())
    }
    var username by remember(existing) { mutableStateOf(existing?.username ?: "") }
    var note by remember(existing) { mutableStateOf(existing?.note ?: "") }

    var useKey by remember(existing) {
        mutableStateOf(existing?.authType == AuthType.KEY.name)
    }
    // Secrets are never pre-filled from storage – the user retypes them.
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var selectedKeyId by remember(existing) { mutableStateOf(existing?.keyId) }

    var startupCommand by remember(existing) { mutableStateOf(existing?.startupCommand ?: "") }
    var remotePath by remember(existing) { mutableStateOf(existing?.remotePath ?: "") }

    var desktopPassword by remember { mutableStateOf("") }
    var rdpDomain by remember(existing) { mutableStateOf(existing?.rdpDomain ?: "") }

    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun commit() {
        error = when {
            address.isBlank() -> "请填写主机地址"
            type.usesSshAuth && username.isBlank() -> "请填写用户名"
            type.usesSshAuth && useKey && selectedKeyId == null -> "请选择一个私钥"
            else -> null
        }
        if (error != null || saving) return

        saving = true
        val entity = HostEntity(
            id = hostId,
            type = type.name,
            name = name.ifBlank { address }.ifBlank { type.label },
            host = address.trim(),
            port = port.toIntOrNull() ?: type.defaultPort,
            username = username.trim(),
            authType = if (type.usesSshAuth && useKey) AuthType.KEY.name else AuthType.PASSWORD.name,
            passwordEnc = existing?.passwordEnc,
            keyId = if (type.usesSshAuth && useKey) selectedKeyId else null,
            passphraseEnc = existing?.passphraseEnc,
            remotePath = remotePath.trim(),
            startupCommand = startupCommand,
            note = note,
            vncPasswordEnc = existing?.vncPasswordEnc,
            rdpPasswordEnc = existing?.rdpPasswordEnc,
            rdpDomain = rdpDomain.trim(),
            rdpWidth = existing?.rdpWidth ?: 1280,
            rdpHeight = existing?.rdpHeight ?: 800,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        // Wait for the row to actually land before navigating anywhere.
        scope.launch {
            try {
                val rowId = viewModel.saveHost(
                    entity,
                    plainPassword = password.ifBlank { null },
                    plainPassphrase = passphrase.ifBlank { null },
                    plainVncPassword = if (type == HostType.VNC) desktopPassword.ifBlank { null } else null,
                    plainRdpPassword = if (type == HostType.RDP) desktopPassword.ifBlank { null } else null
                )
                onSaveAndConnect(rowId, type)
            } catch (t: Throwable) {
                error = "保存失败：${t.message ?: "未知错误"}"
            } finally {
                saving = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("返回")
                }
                Text(
                    text = "${type.label}${if (hostId == 0L) " · 新增" else " · 编辑"}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.weight(1f))
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("留空则使用地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("主机地址 / IP") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (type.usesSshAuth) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Text("认证方式", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !useKey,
                            onClick = { useKey = false },
                            label = { Text("密码") }
                        )
                        FilterChip(
                            selected = useKey,
                            onClick = { useKey = true },
                            label = { Text("私钥") }
                        )
                    }

                    if (!useKey) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("密码${if (existing != null) "（留空则保持不变）" else ""}") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    } else {
                        Text("选择私钥", style = MaterialTheme.typography.bodyMedium)
                        if (keys.isEmpty()) {
                            Text(
                                text = "还没有导入私钥，请先到密钥管理添加",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                keys.forEach { key ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = selectedKeyId == key.id,
                                            onCheckedChange = {
                                                selectedKeyId = if (it) key.id else null
                                            }
                                        )
                                        Text("${key.alias} (${key.type})")
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("私钥口令（可空）") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                when (type) {
                    HostType.SSH -> {
                        OutlinedTextField(
                            value = startupCommand,
                            onValueChange = { startupCommand = it },
                            label = { Text("登录后自动执行（可空）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    HostType.SFTP -> {
                        OutlinedTextField(
                            value = remotePath,
                            onValueChange = { remotePath = it },
                            label = { Text("初始远程目录（可空）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    HostType.VNC -> {
                        OutlinedTextField(
                            value = desktopPassword,
                            onValueChange = { desktopPassword = it },
                            label = { Text("VNC 口令${if (existing != null) "（留空则保持不变）" else ""}") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    HostType.RDP -> {
                        OutlinedTextField(
                            value = rdpDomain,
                            onValueChange = { rdpDomain = it },
                            label = { Text("Windows 域（可空）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("用户名") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = desktopPassword,
                            onValueChange = { desktopPassword = it },
                            label = { Text("密码${if (existing != null) "（留空则保持不变）" else ""}") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { commit() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving
                ) {
                    Text(if (saving) "保存中…" else "保存并连接")
                }
            }
        }
    }
}
