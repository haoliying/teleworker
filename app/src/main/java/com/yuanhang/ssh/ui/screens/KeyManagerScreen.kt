package com.yuanhang.ssh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import com.yuanhang.ssh.viewmodel.AppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where private keys are imported and removed.
 *
 * Key material is pasted as PEM (OpenSSH and PKCS#8 both work thanks to JSch's parser)
 * and immediately sealed before being written to the database.
 */
@Composable
fun KeyManagerScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val keys by viewModel.keys.collectAsState()

    var alias by remember { mutableStateOf("") }
    var material by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    val formatter = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Text("密钥管理", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (expanded) "收起" else "导入")
                }
            }

            if (expanded) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = alias,
                            onValueChange = { alias = it },
                            label = { Text("别名") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = material,
                            onValueChange = { material = it },
                            label = { Text("私钥内容（PEM）") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 8
                        )
                        Button(
                            onClick = {
                                val type = when {
                                    material.contains("OPENSSH PRIVATE KEY") -> "OPENSSH"
                                    material.contains("EC PRIVATE KEY") -> "ECDSA"
                                    material.contains("RSA PRIVATE KEY") -> "RSA"
                                    else -> "PEM"
                                }
                                viewModel.saveKey(
                                    alias = alias.ifBlank { "key-${formatter.format(Date())}" },
                                    type = type,
                                    material = material
                                )
                                alias = ""
                                material = ""
                                expanded = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = material.isNotBlank()
                        ) {
                            Text("保存密钥")
                        }
                    }
                }
            }

            if (keys.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("还没有导入任何私钥")
                    Text(
                        "支持 RSA / ED25519 / ECDSA，OpenSSH 新格式也可以直接粘贴",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(keys, key = { it.id }) { key ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(key.alias, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${key.type} · 导入于 ${formatter.format(Date(key.createdAt))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { viewModel.deleteKey(key.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
