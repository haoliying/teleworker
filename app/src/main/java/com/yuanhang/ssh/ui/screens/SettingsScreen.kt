package com.yuanhang.ssh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuanhang.ssh.terminal.TerminalColorScheme
import com.yuanhang.ssh.terminal.TerminalColorSchemes
import com.yuanhang.ssh.viewmodel.AppViewModel

/**
 * Appearance settings for the terminal: colour scheme and default font size.
 *
 * Changes apply immediately, including to an already-connected session – the scheme is
 * written into the palette's default slots and the screen is repainted on the next frame.
 */
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val scheme by viewModel.colorScheme.collectAsState()
    val fontSize by viewModel.defaultFontSize.collectAsState()
    val context = LocalContext.current
    var crashLog by remember { mutableStateOf(viewModel.readCrashLog()) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Text("设置", style = MaterialTheme.typography.titleMedium)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {

                Text("终端配色", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "只切换默认前景色/背景色，不影响 ls、vim 等程序的彩色输出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                TerminalColorSchemes.ALL.forEach { item ->
                    SchemeRow(
                        scheme = item,
                        selected = item.id == scheme.id,
                        onSelect = { viewModel.setColorScheme(item.id) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text("默认字号", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "新建主机未单独设置字号时使用。终端界面右上角可单独调整当前会话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.setDefaultFontSize(fontSize - 1) },
                            enabled = fontSize > AppViewModel.MIN_FONT_SIZE
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "减小")
                        }
                        Text(
                            text = "$fontSize sp",
                            modifier = Modifier.width(72.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        IconButton(
                            onClick = { viewModel.setDefaultFontSize(fontSize + 1) },
                            enabled = fontSize < AppViewModel.MAX_FONT_SIZE
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "增大")
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "AaBb",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = fontSize.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text("崩溃日志", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "应用闪退时会在这里留下堆栈，复制出来即可定位问题。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                CrashLogCard(
                    text = crashLog,
                    onRefresh = { crashLog = viewModel.readCrashLog() },
                    onClear = { viewModel.clearCrashLog(); crashLog = "" },
                    onCopy = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crashLog))
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun CrashLogCard(
    text: String,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRefresh) { Text("刷新") }
                TextButton(onClick = onCopy, enabled = text.isNotBlank()) { Text("复制") }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onClear, enabled = text.isNotBlank()) { Text("清空") }
            }
            Text(
                text = text.ifBlank { "暂无崩溃记录" },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun SchemeRow(
    scheme: TerminalColorScheme,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors()
            )
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(scheme.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "#${(scheme.backgroundArgb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Live preview: a few "prompt" cells in the scheme's own colours.
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(scheme.background)
                    .border(1.dp, Color(0x33000000), RoundedCornerShape(6.dp))
                    .padding(6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "user@host:~$",
                        color = scheme.foreground,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                    Text(
                        text = "ls -al",
                        color = scheme.foreground,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
    }
}
