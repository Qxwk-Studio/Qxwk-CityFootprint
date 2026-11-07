package top.qxwkstudio.travel.ui.mine

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.LocalData
import top.qxwkstudio.travel.data.Prefs
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.SyncConflict
import top.qxwkstudio.travel.data.SyncEngine
import top.qxwkstudio.travel.data.SyncEvents
import top.qxwkstudio.travel.data.SyncScheduler
import top.qxwkstudio.travel.data.SyncApi
import top.qxwkstudio.travel.databinding.ActivitySyncBinding
import top.qxwkstudio.travel.ui.common.LocalizedActivity
import top.qxwkstudio.travel.ui.common.Palette
import top.qxwkstudio.travel.ui.common.applyEdgeToEdge
import top.qxwkstudio.travel.ui.common.notify
import top.qxwkstudio.travel.ui.common.runIo

/** 可选同步：开关、通行证登录、服务器信息。数据 push/pull 后续接入。 */
class SyncActivity : LocalizedActivity() {

    private lateinit var binding: ActivitySyncBinding
    private var showingConflict = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        binding = ActivitySyncBinding.inflate(layoutInflater)
        applyEdgeToEdge(binding.topBar.root, binding.scroll)
        setContentView(binding.root)

        binding.topBar.title.setText(R.string.sync_title)
        binding.topBar.btnBack.visibility = View.VISIBLE
        binding.topBar.btnBack.setOnClickListener { finish() }

        binding.inputServer.setText(Prefs.syncServer)
        binding.btnFetchServer.setOnClickListener { fetchServer() }
        binding.btnLogin.setOnClickListener { login() }
        binding.btnLogout.setOnClickListener { confirmLogout() }
        binding.btnSyncNow.setOnClickListener { syncNow() }

        render()
        loadServer()
        validateSession()
    }

    override fun onResume() {
        super.onResume()
        render()
        showNextConflict()
    }

    private fun render() {
        val loggedIn = Prefs.syncToken.isNotBlank()
        binding.textLastSync.text = if (Prefs.syncLastAt > 0L) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(Prefs.syncLastAt))
        } else {
            getString(R.string.sync_never)
        }
        binding.textLastDetail.visibility = if (Prefs.syncLastAt > 0L) View.VISIBLE else View.GONE
        if (Prefs.syncLastAt > 0L) {
            binding.textLastDetail.text = when {
                !Prefs.syncLastOk -> getString(
                    when (Prefs.syncLastError) {
                        "auth" -> R.string.sync_error_auth
                        "network" -> R.string.sync_error_network
                        "server" -> R.string.sync_error_server
                        else -> R.string.sync_error_unknown
                    },
                )
                Prefs.syncLastPushed == 0 && Prefs.syncLastPulled == 0 && Prefs.syncLastConflicts == 0 ->
                    getString(R.string.sync_last_no_change)
                else -> getString(
                    R.string.sync_last_detail,
                    Prefs.syncLastPushed,
                    Prefs.syncLastPulled,
                    Prefs.syncLastConflicts,
                )
            }
        }
        binding.groupLogin.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.groupAccount.visibility = if (loggedIn) View.VISIBLE else View.GONE
        binding.btnSyncNow.isEnabled = loggedIn && Prefs.syncEnabled
        if (loggedIn) {
            binding.textSyncUser.text = Prefs.syncNickname.ifBlank { getString(R.string.sync_logged_in) }
            binding.textSyncUuid.text = getString(R.string.sync_uuid, Prefs.syncUuid)
        }
    }

    private fun fetchServer() {
        val server = normalizedServer() ?: run {
            notify(R.string.sync_server_invalid)
            return
        }
        Prefs.saveSyncServer(this, server)
        binding.btnFetchServer.isEnabled = false
        binding.btnFetchServer.setText(R.string.sync_fetching)
        lifecycleScope.runIo({ SyncApi.server(server) }) { result ->
            binding.btnFetchServer.isEnabled = true
            binding.btnFetchServer.setText(R.string.sync_server_fetch)
            val info = result.getOrNull()?.getOrNull()
            if (info == null) {
                binding.textServerName.setText(R.string.sync_server_fetch_failed)
                binding.textPhotoSupport.text = "—"
            } else {
                binding.textServerName.text = info.serverName.ifBlank { "City Footprint" }
                binding.textPhotoSupport.setText(
                    if (info.supportsPhotoUpload) R.string.sync_photo_yes else R.string.sync_photo_no,
                )
            }
        }
    }

    private fun loadServer() {
        val server = normalizedServer()
        if (server == null) {
            binding.textServerName.text = "—"
            binding.textPhotoSupport.text = "—"
            return
        }
        lifecycleScope.runIo({ SyncApi.server(server) }) { result ->
            val info = result.getOrNull()?.getOrNull()
            if (info == null) {
                binding.textServerName.setText(R.string.sync_network_failed)
                binding.textPhotoSupport.text = "—"
            } else {
                binding.textServerName.text = info.serverName.ifBlank { "City Footprint" }
                binding.textPhotoSupport.setText(
                    if (info.supportsPhotoUpload) R.string.sync_photo_yes else R.string.sync_photo_no,
                )
            }
        }
    }

    private fun validateSession() {
        val token = Prefs.syncToken
        if (token.isBlank()) return
        lifecycleScope.runIo({ SyncApi.me(Prefs.syncServer, token) }) { result ->
            val session = result.getOrNull()?.getOrNull()
            if (session == null) {
                Prefs.clearSyncSession(this)
                Prefs.clearSyncData(this)
                Prefs.saveSyncEnabled(this, false)
                SyncScheduler.cancel()
                render()
            } else {
                Prefs.saveSyncSession(this, session.token, session.uuid, session.nickname)
                render()
            }
        }
    }

    private fun login() {
        val server = normalizedServer() ?: run {
            notify(R.string.sync_server_invalid)
            return
        }
        val account = binding.inputAccount.text?.toString()?.trim().orEmpty()
        val password = binding.inputPassword.text?.toString().orEmpty()
        if (account.isBlank() || password.isBlank()) {
            notify(R.string.sync_login_empty)
            return
        }
        Prefs.saveSyncServer(this, server)
        binding.btnLogin.isEnabled = false
        binding.btnLogin.setText(R.string.sync_logging_in)
        lifecycleScope.runIo({ SyncApi.login(server, account, password) }) { result ->
            binding.btnLogin.isEnabled = true
            binding.btnLogin.setText(R.string.sync_credential_save)
            val session = result.getOrNull()?.getOrNull()
            if (session == null) {
                notify(R.string.sync_login_failed, long = true)
                return@runIo
            }
            confirmLogin(session)
        }
    }

    private fun normalizedServer(): String? {
        val raw = binding.inputServer.text?.toString()?.trim().orEmpty()
            .trimEnd('/')
            .removeSuffix("/api")
        if (!raw.startsWith("https://")) return null
        return raw
    }

    private fun syncNow() {
        val server = normalizedServer() ?: run {
            notify(R.string.sync_server_invalid)
            return
        }
        Prefs.saveSyncServer(this, server)
        val token = Prefs.syncToken
        if (token.isBlank()) {
            notify(R.string.sync_login_required)
            return
        }
        binding.btnSyncNow.isEnabled = false
        binding.btnSyncNow.setText(R.string.sync_syncing)
        lifecycleScope.runIo({ SyncScheduler.runNow(this, true) }) { result ->
            binding.btnSyncNow.setText(R.string.sync_now)
            val report = result.getOrNull()?.getOrNull()
            if (report == null) {
                when {
                    Prefs.syncToken.isBlank() -> notify(R.string.sync_login_required, long = true)
                    Prefs.syncServer.isBlank() -> notify(R.string.sync_server_invalid, long = true)
                    else -> notify(R.string.sync_failed, long = true)
                }
            } else {
                notify(getString(R.string.sync_done, report.pushed, report.pulled, report.conflicts), long = true)
            }
            render()
            showNextConflict()
        }
    }

    private fun confirmLogin(session: SyncApi.Session) {
        val different = Prefs.syncUuid.isNotBlank() && Prefs.syncUuid != session.uuid
        if (!different) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_login_confirm_title)
                .setMessage(getString(R.string.sync_login_confirm_message, session.nickname))
                .setPositiveButton(R.string.sync_login) { _, _ -> commitLogin(session, wipeLocal = false) }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_login_confirm_title)
            .setMessage(R.string.sync_login_switch_message)
            .setPositiveButton(R.string.sync_login_keep) { _, _ -> commitLogin(session, wipeLocal = false) }
            .setNeutralButton(R.string.sync_login_wipe) { _, _ -> confirmWipeThenLogin(session) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmWipeThenLogin(session: SyncApi.Session) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.sync_wipe_warning)
            .setPositiveButton(R.string.sync_login_wipe) { _, _ -> commitLogin(session, wipeLocal = true) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun commitLogin(session: SyncApi.Session, wipeLocal: Boolean) {
        lifecycleScope.runIo({
            if (wipeLocal) LocalData.wipe(AppDatabase.get(this))
        }) {
            if (wipeLocal) {
                Prefs.clearSyncData(this)
                notify(R.string.sync_wipe_done, long = true)
            } else if (Prefs.syncUuid.isNotBlank() && Prefs.syncUuid != session.uuid) {
                Prefs.clearSyncData(this)
            }
            SyncScheduler.cancel()
            Prefs.saveSyncSession(this, session.token, session.uuid, session.nickname)
            binding.inputPassword.text?.clear()
            render()
            notify(R.string.sync_login_success)
            SyncScheduler.request(500)
        }
    }

    private fun showNextConflict() {
        if (showingConflict) return
        val conflict = Prefs.syncConflicts().firstOrNull() ?: return
        showingConflict = true
        val local = conflict.localJson?.let { summary(conflict.entityType, it) } ?: getString(R.string.sync_local_deleted)
        val remote = summary(conflict.entityType, conflict.remoteJson)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_conflict_title)
            .setMessage(getString(R.string.sync_conflict_message, local, remote))
            .setPositiveButton(R.string.sync_use_local) { _, _ ->
                lifecycleScope.runIo({
                    SyncEngine.resolveWithLocal(this, AppDatabase.get(this), conflict)
                }) { ok ->
                    showingConflict = false
                    render()
                    if (ok.getOrNull() == true) SyncEvents.notifyChanged()
                    showNextConflict()
                }
            }
            .setNegativeButton(R.string.sync_use_remote) { _, _ ->
                lifecycleScope.runIo({
                    SyncEngine.resolveWithRemote(this, AppDatabase.get(this), conflict)
                }) {
                    showingConflict = false
                    SyncEvents.notifyChanged()
                    render()
                    showNextConflict()
                }
            }
            .setNeutralButton(R.string.sync_later) { _, _ -> showingConflict = false }
            .setOnCancelListener { showingConflict = false }
            .show()
    }

    private fun summary(type: String, raw: String): String {
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return "—"
        val name = if (type == "trip") {
            o["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        } else {
            o["city_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        val at = o["client_modified_at"]?.jsonPrimitive?.contentOrNull
        return if (at != null) name + " · " + at else name
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_logout_confirm)
            .setMessage(R.string.sync_logout_message)
            .setPositiveButton(R.string.sync_logout_keep) { _, _ -> doLogout(wipeLocal = false) }
            .setNeutralButton(R.string.sync_logout_wipe) { _, _ -> doLogout(wipeLocal = true) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun doLogout(wipeLocal: Boolean) {
        SyncScheduler.cancel()
        Prefs.clearSyncSession(this)
        Prefs.clearSyncData(this)
        Prefs.saveSyncEnabled(this, false)
        if (wipeLocal) {
            lifecycleScope.runIo({ LocalData.wipe(AppDatabase.get(this)) }) {
                render()
                notify(R.string.sync_wipe_done, long = true)
            }
        } else {
            render()
        }
    }

    companion object {
        fun intent(context: android.content.Context): Intent =
            Intent(context, SyncActivity::class.java)
    }
}
