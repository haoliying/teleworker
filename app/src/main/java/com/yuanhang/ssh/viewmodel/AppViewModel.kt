package com.yuanhang.ssh.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuanhang.ssh.App
import com.yuanhang.ssh.data.AuthType
import com.yuanhang.ssh.data.HostEntity
import com.yuanhang.ssh.data.HostRepository
import com.yuanhang.ssh.data.SshKeyEntity
import com.yuanhang.ssh.data.SshKeyRepository
import com.yuanhang.ssh.data.DatabaseProvider
import com.yuanhang.ssh.rdp.RdpClient
import com.yuanhang.ssh.session.SessionManager
import com.yuanhang.ssh.ssh.SftpSession
import com.yuanhang.ssh.ssh.ShellSession
import com.yuanhang.ssh.terminal.TerminalColorScheme
import com.yuanhang.ssh.terminal.TerminalColorSchemes
import com.yuanhang.ssh.util.Crypto
import com.yuanhang.ssh.vnc.VncClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Result of trying to bring a remote session up. */
sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    data object Connected : ConnectState
    data class Failed(val message: String) : ConnectState
}

/**
 * Single view model shared by every screen.
 *
 * It owns host/key CRUD plus the "bring a session up" orchestration; the actual live
 * connections live in [SessionManager] so they survive navigation and rotation.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val db = DatabaseProvider.get(app)
    private val hostRepo = HostRepository(db.hostDao())
    private val keyRepo = SshKeyRepository(db.sshKeyDao())

    private val prefs = app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    init {
        SessionManager.init(app)
    }

    // ------------------------------------------------------------- settings

    /** Terminal colour scheme. Applied to the palette on change so cells repaint. */
    private val _colorScheme = MutableStateFlow(readScheme().also { it.apply() })
    val colorScheme: StateFlow<TerminalColorScheme> = _colorScheme

    /** Font size used when a host has no size of its own. */
    private val _defaultFontSize = MutableStateFlow(
        prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
    )
    val defaultFontSize: StateFlow<Int> = _defaultFontSize

    fun setColorScheme(id: String) {
        val scheme = TerminalColorSchemes.byId(id)
        prefs.edit().putString(KEY_SCHEME, scheme.id).apply()
        scheme.apply()
        _colorScheme.value = scheme
    }

    fun setDefaultFontSize(size: Int) {
        val clamped = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        prefs.edit().putInt(KEY_FONT_SIZE, clamped).apply()
        _defaultFontSize.value = clamped
    }

    // ----------------------------------------------------------- diagnostics

    /**
     * Contents of `filesDir/crash.log`.
     *
     * Surfaced in the settings screen so a crash can be reported without plugging the phone
     * into a computer – which is the only practical way to debug a crash on a foldable.
     */
    fun readCrashLog(): String = App.readCrashLog(getApplication())

    fun clearCrashLog() {
        App.clearCrashLog(getApplication())
    }

    private fun readScheme(): TerminalColorScheme =
        TerminalColorSchemes.byId(prefs.getString(KEY_SCHEME, null))

    companion object {
        private const val PREFS_NAME = "simple_ssh_settings"
        private const val KEY_SCHEME = "color_scheme"
        private const val KEY_FONT_SIZE = "default_font_size"

        const val DEFAULT_FONT_SIZE = 13
        const val MIN_FONT_SIZE = 6
        const val MAX_FONT_SIZE = 32
    }

    // Eagerly, not WhileSubscribed: the list must already be live while the user is on the
    // edit screen, so that returning from a save shows the new row immediately.
    val hosts: StateFlow<List<HostEntity>> =
        hostRepo.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val keys: StateFlow<List<SshKeyEntity>> =
        keyRepo.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val connectState = MutableStateFlow<ConnectState>(ConnectState.Idle)

    // ------------------------------------------------------------ host CRUD

    /**
     * Persists a host and returns its row id.
     *
     * Suspends until the row is actually in the database, so callers can navigate back to
     * the list knowing the new entry is already there. A blank secret means "leave the
     * stored one alone" when editing (secrets are never echoed back into the form).
     */
    suspend fun saveHost(
        host: HostEntity,
        plainPassword: String?,
        plainPassphrase: String?,
        plainVncPassword: String? = null,
        plainRdpPassword: String? = null
    ): Long {
        val existing = if (host.id != 0L) hostRepo.get(host.id) else null
        val sealed = host.copy(
            passwordEnc = when {
                host.authType != AuthType.PASSWORD.name -> null
                !plainPassword.isNullOrEmpty() -> Crypto.seal(plainPassword)
                else -> existing?.passwordEnc
            },
            passphraseEnc = when {
                !plainPassphrase.isNullOrEmpty() -> Crypto.seal(plainPassphrase)
                else -> existing?.passphraseEnc
            },
            vncPasswordEnc = when {
                !plainVncPassword.isNullOrEmpty() -> Crypto.seal(plainVncPassword)
                else -> existing?.vncPasswordEnc
            },
            rdpPasswordEnc = when {
                !plainRdpPassword.isNullOrEmpty() -> Crypto.seal(plainRdpPassword)
                else -> existing?.rdpPasswordEnc
            }
        )
        return hostRepo.save(sealed)
    }

    /** Opens a stored secret so a screen can pre-fill a password field. */
    fun unsealSecret(sealed: String?): String? = Crypto.unseal(sealed)

    fun deleteHost(id: Long) {
        viewModelScope.launch {
            SessionManager.closeAll(id)
            hostRepo.delete(id)
        }
    }

    suspend fun getHost(id: Long): HostEntity? = hostRepo.get(id)

    suspend fun getKey(id: Long?): SshKeyEntity? = id?.let { keyRepo.get(it) }

    // --------------------------------------------------------------- keys

    fun saveKey(alias: String, type: String, material: String) {
        viewModelScope.launch {
            val sealed = Crypto.seal(material) ?: return@launch
            keyRepo.save(SshKeyEntity(alias = alias, type = type, materialEnc = sealed))
        }
    }

    fun deleteKey(id: Long) {
        viewModelScope.launch { keyRepo.delete(id) }
    }

    // ----------------------------------------------------------- sessions

    suspend fun ensureShell(hostId: Long): Result<ShellSession> {
        val existing = SessionManager.getShell(hostId)
        if (existing != null && existing.isConnected) return Result.success(existing)

        connectState.value = ConnectState.Connecting
        val host = hostRepo.get(hostId) ?: return Result.failure(IllegalStateException("主机不存在"))
        val key = getKey(host.keyId)
        val result = SessionManager.openShell(host, key)
        connectState.value = result.fold(
            onSuccess = { ConnectState.Connected },
            onFailure = { ConnectState.Failed(it.message ?: "连接失败") }
        )
        if (result.isSuccess) hostRepo.touch(hostId)
        return result
    }

    suspend fun ensureSftp(hostId: Long): Result<SftpSession> {
        SessionManager.getSftp(hostId)?.let { return Result.success(it) }

        connectState.value = ConnectState.Connecting
        val host = hostRepo.get(hostId) ?: return Result.failure(IllegalStateException("主机不存在"))
        val key = getKey(host.keyId)
        val result = SessionManager.openSftp(host, key)
        connectState.value = result.fold(
            onSuccess = { ConnectState.Connected },
            onFailure = { ConnectState.Failed(it.message ?: "连接失败") }
        )
        return result
    }

    suspend fun ensureVnc(
        hostId: Long,
        password: String?,
        listener: VncClient.Listener
    ): Result<VncClient> {
        SessionManager.getVnc(hostId)?.let { return Result.success(it) }

        connectState.value = ConnectState.Connecting
        val host = hostRepo.get(hostId) ?: return Result.failure(IllegalStateException("主机不存在"))
        // VNC is dialled directly – no SSH session, so no key is needed.
        val result = SessionManager.openVnc(host, password, listener)
        connectState.value = result.fold(
            onSuccess = { ConnectState.Connected },
            onFailure = { ConnectState.Failed(it.message ?: "连接失败") }
        )
        return result
    }

    suspend fun ensureRdp(
        hostId: Long,
        domain: String,
        username: String,
        password: String,
        listener: RdpClient.Listener
    ): Result<RdpClient> {
        SessionManager.getRdp(hostId)?.let { return Result.success(it) }

        connectState.value = ConnectState.Connecting
        val host = hostRepo.get(hostId) ?: return Result.failure(IllegalStateException("主机不存在"))
        // RDP is dialled directly – no SSH session, so no key is needed.
        val result = SessionManager.openRdp(host, domain, username, password, listener)
        connectState.value = result.fold(
            onSuccess = { ConnectState.Connected },
            onFailure = { ConnectState.Failed(it.message ?: "连接失败") }
        )
        return result
    }

    fun closeAllSessions() {
        viewModelScope.launch { SessionManager.closeAll() }
    }
}
