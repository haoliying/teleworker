package com.yuanhang.ssh.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.graphics.vector.ImageVector
import com.yuanhang.ssh.data.HostType

/** Icon used for a host type, everywhere it is shown (picker, list, connect button). */
fun HostType.icon(): ImageVector = when (this) {
    HostType.SSH -> Icons.Default.Terminal
    HostType.SFTP -> Icons.Default.Folder
    HostType.VNC -> Icons.Default.Tv
    HostType.RDP -> Icons.Default.DesktopWindows
}

/** One-line description shown on the type picker. */
fun HostType.blurb(): String = when (this) {
    HostType.SSH -> "字符终端，可再用 SFTP 传文件"
    HostType.SFTP -> "只做文件传输，不开终端"
    HostType.VNC -> "VNC 远程桌面，手机直连"
    HostType.RDP -> "Windows 远程桌面，手机直连"
}

/** Label for the card's primary action. */
fun HostType.actionLabel(): String = when (this) {
    HostType.SSH -> "连接"
    HostType.SFTP -> "文件"
    HostType.VNC -> "桌面"
    HostType.RDP -> "连接"
}

/** Short badge text; the list already shows the address, so this is just the protocol. */
fun HostType.badge(): String = when (this) {
    HostType.SSH -> "SSH"
    HostType.SFTP -> "SFTP"
    HostType.VNC -> "VNC"
    HostType.RDP -> "RDP"
}
