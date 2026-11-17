package com.yuanhang.ssh.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuanhang.ssh.ssh.RemoteEntry
import com.yuanhang.ssh.ssh.SftpSession
import androidx.lifecycle.viewModelScope
import com.yuanhang.ssh.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Graphical file manager over SFTP.
 *
 * Uploads are picked with the system document picker and streamed straight through the
 * SSH connection; downloads land in this app's private Downloads directory so no broad
 * storage permission is needed.
 */
@Composable
fun SftpScreen(
    viewModel: AppViewModel,
    hostId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val hosts by viewModel.hosts.collectAsState()
    val host = remember(hosts, hostId) { hosts.find { it.id == hostId } }

    var session by remember { mutableStateOf<SftpSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf(host?.remotePath?.takeIf { it.isNotBlank() } ?: ".") }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(-1) }
    var message by remember { mutableStateOf<String?>(null) }
    var newFolderName by remember { mutableStateOf("") }

    LaunchedEffect(hostId) {
        launch {
            viewModel.ensureSftp(hostId)
                .onSuccess { session = it }
                .onFailure { error = it.message }
        }
    }

    suspend fun refreshRemote(at: String = path) {
        val live = session ?: return
        busy = true
        runCatching { entries = live.list(at); path = at; error = null }
            .onFailure { error = it.message }
        busy = false
    }

    LaunchedEffect(session) {
        val live = session ?: return@LaunchedEffect
        val start = host?.remotePath?.takeIf { it.isNotBlank() } ?: live.home()
        withContext(Dispatchers.IO) { refreshRemote(start) }
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val live = session ?: return@rememberLauncherForActivityResult
        viewModel.viewModelScope.launch {
            progress = 0
            val temp = copyToCache(context, uri)
            val result = withContext(Dispatchers.IO) {
                live.upload(temp, path) { progress = it }
            }
            progress = -1
            message = result.fold(
                onSuccess = { "已上传 ${temp.name}" },
                onFailure = { "上传失败：${it.message}" }
            )
            refreshRemote()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Text(host?.name ?: "SFTP", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    viewModel.viewModelScope.launch { refreshRemote() }
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }

            message?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            error?.let {
                Text(
                    text = "错误：$it",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (progress >= 0) {
                LinearProgressIndicator(
                    progress = progress / 100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("新目录名") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = {
                    val live = session ?: return@IconButton
                    val name = newFolderName.trim()
                    if (name.isEmpty()) return@IconButton
                    viewModel.viewModelScope.launch {
                        withContext(Dispatchers.IO) { live.mkdir(path, name) }
                        newFolderName = ""
                        refreshRemote()
                    }
                }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "新建目录")
                }
                IconButton(onClick = { pickFile.launch("*/*") }) {
                    Icon(Icons.Default.Upload, contentDescription = "上传")
                }
            }

            if (entries.isEmpty() && !busy) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("该目录为空")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val live = session
                    if (live != null) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "↑ 上级目录",
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }
                    }
                    items(entries, key = { it.path }) { entry ->
                        RemoteRow(
                            entry = entry,
                            onOpen = {
                                if (entry.isDirectory) {
                                    viewModel.viewModelScope.launch { refreshRemote(entry.path) }
                                }
                            },
                            onDownload = {
                                val liveTwo = session ?: return@RemoteRow
                                viewModel.viewModelScope.launch {
                                    progress = 0
                                    val dir = File(
                                        context.getExternalFilesDir(null),
                                        "downloads"
                                    ).apply { mkdirs() }
                                    val result = withContext(Dispatchers.IO) {
                                        liveTwo.download(entry, dir) { progress = it }
                                    }
                                    progress = -1
                                    message = result.fold(
                                        onSuccess = { "已下载到 ${it.absolutePath}" },
                                        onFailure = { "下载失败：${it.message}" }
                                    )
                                }
                            },
                            onDelete = {
                                val liveTwo = session ?: return@RemoteRow
                                viewModel.viewModelScope.launch {
                                    withContext(Dispatchers.IO) {
                                        liveTwo.delete(entry.path, entry.isDirectory)
                                    }
                                    refreshRemote()
                                }
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RemoteRow(
    entry: RemoteEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.filename + if (entry.isDirectory) "/" else "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (entry.isDirectory) "目录" else formatSize(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!entry.isDirectory) {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = "下载")
                }
            } else {
                Button(onClick = onOpen) { Text("打开") }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
    if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/** Copy a document into our cache so JSch can read it with a plain file path. */
private fun copyToCache(context: Context, uri: Uri): File {
    val dir = File(context.cacheDir, "uploads").apply { mkdirs() }
    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
    val out = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
    }
    return out
}
