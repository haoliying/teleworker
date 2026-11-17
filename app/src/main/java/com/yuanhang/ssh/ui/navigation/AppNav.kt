package com.yuanhang.ssh.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yuanhang.ssh.data.HostEntity
import com.yuanhang.ssh.data.HostType
import com.yuanhang.ssh.ui.screens.HostEditScreen
import com.yuanhang.ssh.ui.screens.HostListScreen
import com.yuanhang.ssh.ui.screens.HostTypePickerScreen
import com.yuanhang.ssh.ui.screens.KeyManagerScreen
import com.yuanhang.ssh.ui.screens.RdpScreen
import com.yuanhang.ssh.ui.screens.SettingsScreen
import com.yuanhang.ssh.ui.screens.SftpScreen
import com.yuanhang.ssh.ui.screens.TerminalScreen
import com.yuanhang.ssh.ui.screens.VncScreen
import com.yuanhang.ssh.viewmodel.AppViewModel

@Composable
fun AppNav(viewModel: AppViewModel) {
    val nav = rememberNavController()
    val connectState by viewModel.connectState.collectAsState()

    NavHost(navController = nav, startDestination = Routes.HOSTS) {

        composable(Routes.HOSTS) {
            HostListScreen(
                viewModel = viewModel,
                // The host's own type decides which screen "connect" opens.
                onOpen = { host -> nav.navigate(destinationFor(host)) },
                onEdit = { nav.navigate(Routes.editHost(it)) },
                onCreate = { nav.navigate(Routes.NEW_HOST) },
                onManageKeys = { nav.navigate(Routes.KEYS) },
                onSettings = { nav.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.NEW_HOST) {
            HostTypePickerScreen(
                onPick = { type -> nav.navigate(Routes.newHost(type.name)) },
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = Routes.EDIT_HOST,
            arguments = listOf(
                navArgument(Routes.ARG_HOST_ID) {
                    type = NavType.LongType
                    defaultValue = 0L
                },
                navArgument(Routes.ARG_TYPE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStack ->
            val hostId = backStack.arguments?.getLong(Routes.ARG_HOST_ID) ?: 0L
            val type = backStack.arguments?.getString(Routes.ARG_TYPE)
            HostEditScreen(
                viewModel = viewModel,
                hostId = hostId,
                typeArg = type,
                // Save and open the screen this host is for. The form is dropped from the
                // stack so backing out returns to the list, not to a stale form.
                onSaveAndConnect = { id, hostType ->
                    nav.navigate(destinationFor(hostType, id)) {
                        popUpTo(Routes.HOSTS) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = Routes.TERMINAL,
            arguments = listOf(navArgument(Routes.ARG_HOST_ID) { type = NavType.LongType })
        ) { backStack ->
            val hostId = backStack.arguments?.getLong(Routes.ARG_HOST_ID) ?: 0L
            TerminalScreen(
                viewModel = viewModel,
                hostId = hostId,
                onOpenSftp = { nav.navigate(Routes.sftp(hostId)) },
                onOpenVnc = { nav.navigate(Routes.vnc(hostId)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = Routes.SFTP,
            arguments = listOf(navArgument(Routes.ARG_HOST_ID) { type = NavType.LongType })
        ) { backStack ->
            val hostId = backStack.arguments?.getLong(Routes.ARG_HOST_ID) ?: 0L
            SftpScreen(
                viewModel = viewModel,
                hostId = hostId,
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = Routes.VNC,
            arguments = listOf(navArgument(Routes.ARG_HOST_ID) { type = NavType.LongType })
        ) { backStack ->
            val hostId = backStack.arguments?.getLong(Routes.ARG_HOST_ID) ?: 0L
            VncScreen(
                viewModel = viewModel,
                hostId = hostId,
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = Routes.RDP,
            arguments = listOf(navArgument(Routes.ARG_HOST_ID) { type = NavType.LongType })
        ) { backStack ->
            val hostId = backStack.arguments?.getLong(Routes.ARG_HOST_ID) ?: 0L
            RdpScreen(
                viewModel = viewModel,
                hostId = hostId,
                onBack = { nav.popBackStack() }
            )
        }

        composable(Routes.KEYS) {
            KeyManagerScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack() }
            )
        }
    }

    com.yuanhang.ssh.ui.components.ConnectionBanner(state = connectState)
}

/** Which screen a host of this type should open on. */
private fun destinationFor(type: HostType, hostId: Long): String = when (type) {
    HostType.SSH -> Routes.terminal(hostId)
    HostType.SFTP -> Routes.sftp(hostId)
    HostType.VNC -> Routes.vnc(hostId)
    HostType.RDP -> Routes.rdp(hostId)
}

private fun destinationFor(host: HostEntity): String =
    destinationFor(HostType.of(host.type), host.id)
