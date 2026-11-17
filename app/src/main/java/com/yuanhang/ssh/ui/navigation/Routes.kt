package com.yuanhang.ssh.ui.navigation

object Routes {
    const val HOSTS = "hosts"

    /** Picker shown before creating a host – SSH / SFTP / VNC / RDP are separate entries. */
    const val NEW_HOST = "hosts/new"

    const val EDIT_HOST = "hosts/edit/{hostId}?type={type}"
    const val TERMINAL = "terminal/{hostId}"
    const val SFTP = "sftp/{hostId}"
    const val VNC = "vnc/{hostId}"
    const val RDP = "rdp/{hostId}"
    const val KEYS = "keys"
    const val SETTINGS = "settings"
    const val SESSIONS = "sessions"

    fun editHost(hostId: Long) = "hosts/edit/$hostId"
    fun newHost(type: String) = "hosts/edit/0?type=$type"
    fun terminal(hostId: Long) = "terminal/$hostId"
    fun sftp(hostId: Long) = "sftp/$hostId"
    fun vnc(hostId: Long) = "vnc/$hostId"
    fun rdp(hostId: Long) = "rdp/$hostId"

    const val ARG_HOST_ID = "hostId"
    const val ARG_TYPE = "type"
}
