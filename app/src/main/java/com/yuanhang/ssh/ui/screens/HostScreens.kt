package com.yuanhang.ssh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuanhang.ssh.data.AuthType
import com.yuanhang.ssh.data.HostEntity
import com.yuanhang.ssh.data.HostType
import com.yuanhang.ssh.viewmodel.AppViewModel

/**
 * Home screen: every remembered server, with one-tap connect.
 *
 * The large "连接" action on each card is the fast path the user asks for most often,
 * while per-host menus expose SFTP, VNC, editing and deletion.
 */
@Composable
fun HostListScreen(
    viewModel: AppViewModel,
    onOpen: (HostEntity) -> Unit,
    onEdit: (Long) -> Unit,
    onCreate: () -> Unit,
    onManageKeys: () -> Unit,
    onSettings: () -> Unit
) {
    val hosts by viewModel.hosts.collectAsState()
    var query by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            HeaderRow(onManageKeys = onManageKeys, onSettings = onSettings)

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索名称、地址、用户名…") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                singleLine = true
            )

            val visible = remember(hosts, query) {
                val q = query.trim()
                if (q.isEmpty()) hosts else hosts.filter {
                    it.name.contains(q, true) ||
                        it.host.contains(q, true) ||
                        it.username.contains(q, true) ||
                        it.note.contains(q, true)
                }
            }

            // On tablets, foldables and landscape the list reads better as a grid.
            val wideScreen = LocalConfiguration.current.screenWidthDp >= 600
            val hostCard: @Composable (HostEntity) -> Unit = { host ->
                HostCard(
                    host = host,
                    onOpen = { onOpen(host) },
                    onSftp = { onOpen(host.copy(type = HostType.SFTP.name)) },
                    onEdit = { onEdit(host.id) },
                    onDelete = { viewModel.deleteHost(host.id) }
                )
            }

            if (visible.isEmpty()) {
                EmptyState(hosts.isEmpty(), onCreate = onCreate)
            } else if (wideScreen) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 340.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(visible, key = { it.id }) { host -> hostCard(host) }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(visible, key = { it.id }) { host -> hostCard(host) }
                }
            }
        }

        FloatingActionButton(
            onClick = onCreate,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "新增主机")
        }
        }
    }
}

@Composable
private fun HeaderRow(onManageKeys: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Teleworker",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "SSH 终端 · SFTP · VNC · RDP",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onManageKeys) {
            Icon(Icons.Default.Key, contentDescription = "密钥管理")
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "设置")
        }
    }
}

@Composable
private fun EmptyState(noHostsAtAll: Boolean, onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (noHostsAtAll) "还没有保存任何服务器" else "没有匹配的服务器",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (noHostsAtAll) "添加第一台主机，之后即可一键连接" else "试试其它关键词",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (noHostsAtAll) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("新增主机")
            }
        }
    }
}

@Composable
private fun HostCard(
    host: HostEntity,
    onOpen: () -> Unit,
    onSftp: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val type = HostType.of(host.type)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = host.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        // VNC entries have no account, so "user@host" would read oddly.
                        text = if (host.username.isBlank()) {
                            "${host.host}:${host.port}"
                        } else {
                            "${host.username}@${host.host}:${host.port}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TypeBadge(type)
                Spacer(modifier = Modifier.width(6.dp))
                AuthBadge(host)
            }

            if (host.note.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = host.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpen) {
                    Icon(type.icon(), contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(type.actionLabel())
                }
                // An SSH host always carries SFTP with it; nothing else needs the extra entry.
                if (type == HostType.SSH) {
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = onSftp) {
                        Icon(HostType.SFTP.icon(), contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("文件")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

@Composable
private fun TypeBadge(type: HostType) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = type.badge(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun AuthBadge(host: HostEntity) {
    // Desktop protocols have no SSH credentials, so the badge would be meaningless.
    if (!HostType.of(host.type).usesSshAuth) return
    val (label, container, content) = if (host.authType == AuthType.KEY.name) {
        Triple("密钥", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    } else {
        Triple("密码", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    }

    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clickable { /* badge is informational */ }
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content
        )
    }
}

@Composable
private fun SettingsShortcutIcon() {
    Icon(Icons.Default.SettingsRemote, contentDescription = null)
}
