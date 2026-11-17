package com.yuanhang.ssh.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuanhang.ssh.viewmodel.ConnectState

/** Small overlay telling the user where a connection attempt got to. */
@Composable
fun ConnectionBanner(state: ConnectState) {
    val visible = state is ConnectState.Connecting || state is ConnectState.Failed
    AnimatedVisibility(visible = visible) {
        val text = when (state) {
            ConnectState.Connecting -> "正在连接…"
            is ConnectState.Failed -> state.message
            else -> ""
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (state is ConnectState.Failed) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                }
            )
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
