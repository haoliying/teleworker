package com.yuanhang.ssh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuanhang.ssh.ui.navigation.AppNav
import com.yuanhang.ssh.ui.theme.YuanhangSSHTheme
import com.yuanhang.ssh.viewmodel.AppViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            YuanhangSSHTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val viewModel: AppViewModel = viewModel()

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            AppNav(viewModel)
        }
    }
}
