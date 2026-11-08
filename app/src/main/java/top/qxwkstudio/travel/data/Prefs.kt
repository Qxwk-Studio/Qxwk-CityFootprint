package top.qxwkstudio.travel.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.AppSettingEntity

/** 外观（浅色 / 深色 / 跟随系统）。[value] 是落库字符串，不存 ordinal。 */
enum class Appearance(val value: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
}

/**
 * 设备级偏好（外观 / 主题色）。**权威存储在 `app_settings` 表，SharedPreferences 只当启动缓存**。
 *
 * 为什么还要缓存：Activity 在 `setContentView` 之前就要应用主题与夜间模式，那一刻只能同步拿值；
 * 而启动时**不能在主线程开 Room**（上一版为了同步读开了 `allowMainThreadQueries`，那是坏味道）。
 * 所以写入走「内存 + SP + 异步落库」，启动只读 SP；后台再把库里的值同步回来，两边自愈。
 */
object Prefs {

    private const val SP_NAME = "city_footprint"
    private const val SP_APPEARANCE = "appearance"
    private const val SP_ACCENT = "accent_key"
    private const val SP_MIGRATED = "prefs_migrated"

    private const val KEY_APPEARANCE = "appearance"
    private const val KEY_ACCENT = "accent_key"
    private const val KEY_NETWORK = "network_allowed"
    private const val KEY_LANGUAGE = "language_tag"
    private const val KEY_SYNC_TOKEN = "sync_token"
    private const val KEY_SYNC_NICKNAME = "sync_nickname"
    private const val KEY_SYNC_UUID = "sync_uuid"
    private const val KEY_SYNC_AVATAR = "sync_avatar"
    private const val KEY_SYNC_COLOR = "sync_color"
    private const val KEY_SYNC_ENABLED = "sync_enabled"
    private const val KEY_SYNC_REVISIONS = "sync_revisions"
    private const val KEY_SYNC_SYNCED = "sync_synced"
    private const val KEY_SYNC_CURSOR = "sync_cursor"
    private const val KEY_SYNC_LAST_AT = "sync_last_at"
    private const val KEY_SYNC_LAST_PUSHED = "sync_last_pushed"
    private const val KEY_SYNC_LAST_PULLED = "sync_last_pulled"
    private const val KEY_SYNC_LAST_CONFLICTS = "sync_last_conflicts"
    private const val KEY_SYNC_LAST_OK = "sync_last_ok"
    private const val KEY_SYNC_LAST_ERROR = "sync_last_error"
    private const val KEY_SYNC_CONFLICTS = "sync_conflicts"
    private const val KEY_SYNC_SERVER = "sync_server"
    private const val KEY_LEGAL_ACCEPTED = "legal_accepted"
    const val DEFAULT_SYNC_SERVER = ""

    @Volatile private var appearanceValue = Appearance.SYSTEM
    @Volatile private var accentValue = ""
    @Volatile private var networkValue = false
    @Volatile private var languageValue = ""
    @Volatile private var syncTokenValue = ""
    @Volatile private var syncNicknameValue = ""
    @Volatile private var syncUuidValue = ""
    @Volatile private var syncAvatarValue = ""
    @Volatile private var syncColorValue = ""
    @Volatile private var syncEnabledValue = false
    @Volatile private var syncRevisions = JSONObject()
    @Volatile private var syncSynced = JSONObject()
    @Volatile private var syncCursorValue = 0L
    @Volatile private var syncLastAtValue = 0L
    @Volatile private var syncServerValue = DEFAULT_SYNC_SERVER
    @Volatile private var legalAcceptedValue = false
    @Volatile private var syncLastPushedValue = 0
    @Volatile private var syncLastPulledValue = 0
    @Volatile private var syncLastConflictsValue = 0
    @Volatile private var syncLastOkValue = false
    @Volatile private var syncLastErrorValue = ""

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val sp = app.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        appearanceValue = Appearance.entries.firstOrNull { it.value == sp.getString(SP_APPEARANCE, null) }
            ?: Appearance.SYSTEM
        accentValue = sp.getString(SP_ACCENT, null).orEmpty()
        networkValue = sp.getBoolean(KEY_NETWORK, false)
        languageValue = sp.getString(KEY_LANGUAGE, null).orEmpty()
        syncTokenValue = sp.getString(KEY_SYNC_TOKEN, null).orEmpty()
        syncNicknameValue = sp.getString(KEY_SYNC_NICKNAME, null).orEmpty()
        syncUuidValue = sp.getString(KEY_SYNC_UUID, null).orEmpty()
        syncAvatarValue = sp.getString(KEY_SYNC_AVATAR, null).orEmpty()
        syncColorValue = sp.getString(KEY_SYNC_COLOR, null).orEmpty()
        syncEnabledValue = sp.getBoolean(KEY_SYNC_ENABLED, false)
        syncRevisions = runCatching { JSONObject(sp.getString(KEY_SYNC_REVISIONS, "{}").orEmpty()) }.getOrElse { JSONObject() }
        syncSynced = runCatching { JSONObject(sp.getString(KEY_SYNC_SYNCED, "{}").orEmpty()) }.getOrElse { JSONObject() }
        syncCursorValue = sp.getLong(KEY_SYNC_CURSOR, 0L)
        syncLastAtValue = sp.getLong(KEY_SYNC_LAST_AT, 0L)
        syncServerValue = sp.getString(KEY_SYNC_SERVER, DEFAULT_SYNC_SERVER).orEmpty().ifBlank { DEFAULT_SYNC_SERVER }
        legalAcceptedValue = sp.getBoolean(KEY_LEGAL_ACCEPTED, false)
        syncLastPushedValue = sp.getInt(KEY_SYNC_LAST_PUSHED, 0)
        syncLastPulledValue = sp.getInt(KEY_SYNC_LAST_PULLED, 0)
        syncLastConflictsValue = sp.getInt(KEY_SYNC_LAST_CONFLICTS, 0)
        syncLastOkValue = sp.getBoolean(KEY_SYNC_LAST_OK, false)
        syncLastErrorValue = sp.getString(KEY_SYNC_LAST_ERROR, "").orEmpty()

        // 后台把库里的值同步回来；若库里没有（首次迁库），把 SP 的值写进库
        Thread {
            runCatching {
                val dao = AppDatabase.get(app).settings()
                val savedAppearance = runBlocking { dao.get(KEY_APPEARANCE) }
                val savedAccent = runBlocking { dao.get(KEY_ACCENT) }
                // SP 是最近一次写入的同步副本：以它为准，并把库里的旧值纠正过来，
                // 避免「刚切完外观、下次启动又被旧 DB 值覆盖」。
                val spAppearance = sp.getString(SP_APPEARANCE, null)
                if (spAppearance == null && savedAppearance != null) {
                    appearanceValue = Appearance.entries.firstOrNull { it.value == savedAppearance }
                        ?: Appearance.SYSTEM
                    sp.edit().putString(SP_APPEARANCE, savedAppearance).apply()
                } else if (savedAppearance != appearanceValue.value) {
                    runBlocking { dao.put(AppSettingEntity(KEY_APPEARANCE, appearanceValue.value)) }
                }
                if (savedAccent != null) {
                    accentValue = savedAccent
                    sp.edit().putString(SP_ACCENT, savedAccent).apply()
                } else if (accentValue.isNotEmpty()) {
                    runBlocking { dao.put(AppSettingEntity(KEY_ACCENT, accentValue)) }
                }
                sp.edit().putBoolean(SP_MIGRATED, true).apply()
            }
        }.apply { name = "prefs-sync"; isDaemon = true }.start()
    }

    val appearance: Appearance get() = appearanceValue

    val accentKey: String get() = accentValue

    /** 可选「网络」开关：默认关闭，只影响检查更新这类主动联网。 */
    val networkAllowed: Boolean get() = networkValue

    /** "" = 跟随系统，否则是 BCP-47 语言标签（目前只有 zh-CN）。 */
    val languageTag: String get() = languageValue

    /** 可选同步：Token 只在 SP 中保存，不进入 app_settings / 导出包。 */
    val syncToken: String get() = syncTokenValue
    val syncNickname: String get() = syncNicknameValue
    val syncUuid: String get() = syncUuidValue
    val syncAvatar: String get() = syncAvatarValue
    val syncColor: String get() = syncColorValue
    val syncEnabled: Boolean get() = syncEnabledValue
    val syncCursor: Long get() = syncCursorValue
    val syncLastAt: Long get() = syncLastAtValue
    val syncServer: String get() = syncServerValue
    val legalAccepted: Boolean get() = legalAcceptedValue
    val syncLastPushed: Int get() = syncLastPushedValue
    val syncLastPulled: Int get() = syncLastPulledValue
    val syncLastConflicts: Int get() = syncLastConflictsValue
    val syncLastOk: Boolean get() = syncLastOkValue
    val syncLastError: String get() = syncLastErrorValue

    fun syncRevision(type: String, uuid: String): Int =
        syncRevisions.optInt("$type:$uuid", 0)

    fun syncSyncedAt(type: String, uuid: String): Long =
        syncSynced.optLong("$type:$uuid", 0L)

    fun saveSyncEntity(type: String, uuid: String, revision: Int, syncedAt: Long) {
        syncRevisions.put("$type:$uuid", revision)
        syncSynced.put("$type:$uuid", syncedAt)
        val sp = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_SYNC_REVISIONS, syncRevisions.toString())
            .putString(KEY_SYNC_SYNCED, syncSynced.toString())
            .commit()
    }

    fun requireLegalAccepted() {
        check(legalAcceptedValue) { "请先阅读并同意用户协议和免责声明" }
    }

    fun saveLegalAccepted(context: Context) {
        legalAcceptedValue = true
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LEGAL_ACCEPTED, true).commit()
    }

    fun saveSyncServer(context: Context, server: String) {
        syncServerValue = server.trim().trimEnd('/')
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SYNC_SERVER, syncServerValue).commit()
    }

    /** 切换/退出账号时清空同步元数据，避免把旧账号的本地数据推到新账号。 */
    fun syncConflicts(): List<SyncConflict> {
        val sp = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_SYNC_CONFLICTS, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                SyncConflict(
                    entityType = o.optString("entity_type"),
                    uuid = o.optString("uuid"),
                    serverRevision = o.optInt("server_revision"),
                    localJson = o.optString("local").ifBlank { null },
                    remoteJson = o.optString("remote"),
                )
            }
        }.getOrElse { emptyList() }
    }

    fun saveSyncConflicts(conflicts: List<SyncConflict>) {
        val array = JSONArray()
        conflicts.forEach { c ->
            array.put(JSONObject().apply {
                put("entity_type", c.entityType)
                put("uuid", c.uuid)
                put("server_revision", c.serverRevision)
                put("local", c.localJson.orEmpty())
                put("remote", c.remoteJson)
            })
        }
        appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SYNC_CONFLICTS, array.toString()).commit()
    }

    fun clearSyncData(context: Context) {
        syncRevisions = JSONObject()
        syncSynced = JSONObject()
        syncCursorValue = 0L
        syncLastAtValue = 0L
        syncLastPushedValue = 0
        syncLastPulledValue = 0
        syncLastConflictsValue = 0
        syncLastOkValue = false
        syncLastErrorValue = ""
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SYNC_REVISIONS)
            .remove(KEY_SYNC_SYNCED)
            .remove(KEY_SYNC_CURSOR)
            .remove(KEY_SYNC_LAST_AT)
            .remove(KEY_SYNC_LAST_PUSHED)
            .remove(KEY_SYNC_LAST_PULLED)
            .remove(KEY_SYNC_LAST_CONFLICTS)
            .remove(KEY_SYNC_LAST_OK)
            .remove(KEY_SYNC_LAST_ERROR)
            .remove(KEY_SYNC_CONFLICTS)
            .commit()
    }

    fun saveSyncResult(context: Context, pushed: Int, pulled: Int, conflicts: Int, ok: Boolean, error: String = "") {
        syncLastPushedValue = pushed
        syncLastPulledValue = pulled
        syncLastConflictsValue = conflicts
        syncLastOkValue = ok
        syncLastErrorValue = if (ok) "" else error
        syncLastAtValue = System.currentTimeMillis()
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SYNC_LAST_PUSHED, pushed)
            .putInt(KEY_SYNC_LAST_PULLED, pulled)
            .putInt(KEY_SYNC_LAST_CONFLICTS, conflicts)
            .putBoolean(KEY_SYNC_LAST_OK, ok)
            .putString(KEY_SYNC_LAST_ERROR, if (ok) "" else error)
            .putLong(KEY_SYNC_LAST_AT, syncLastAtValue)
            .commit()
    }

    fun saveSyncCursor(context: Context, cursor: Long) {
        syncCursorValue = cursor
        syncLastAtValue = System.currentTimeMillis()
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_SYNC_CURSOR, cursor).putLong(KEY_SYNC_LAST_AT, syncLastAtValue).commit()
    }

    private lateinit var appContext: Context


    fun saveAppearance(context: Context, appearance: Appearance) {
        appearanceValue = appearance
        write(context, KEY_APPEARANCE, appearance.value)
    }


    fun saveSyncSession(
        context: Context,
        token: String,
        uuid: String,
        nickname: String,
        avatar: String? = null,
        color: String = "",
    ) {
        val avatarValue = avatar.orEmpty()
        val profileChanged = syncUuidValue != uuid ||
            syncNicknameValue != nickname ||
            syncAvatarValue != avatarValue ||
            syncColorValue != color
        syncTokenValue = token
        syncUuidValue = uuid
        syncNicknameValue = nickname
        syncAvatarValue = avatarValue
        syncColorValue = color
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SYNC_TOKEN, token)
            .putString(KEY_SYNC_UUID, uuid)
            .putString(KEY_SYNC_NICKNAME, nickname)
            .putString(KEY_SYNC_AVATAR, avatar.orEmpty())
            .putString(KEY_SYNC_COLOR, color)
            .commit()
        if (profileChanged) DataVersion.bump()
    }

    fun clearSyncSession(context: Context) {
        val hadSession = syncTokenValue.isNotBlank()
        syncTokenValue = ""
        syncUuidValue = ""
        syncAvatarValue = ""
        syncColorValue = ""
        syncNicknameValue = ""
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SYNC_TOKEN)
            .remove(KEY_SYNC_UUID)
            .remove(KEY_SYNC_AVATAR)
            .remove(KEY_SYNC_COLOR)
            .remove(KEY_SYNC_NICKNAME)
            .commit()
        if (hadSession) DataVersion.bump()
    }

    fun saveSyncEnabled(context: Context, enabled: Boolean) {
        syncEnabledValue = enabled
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SYNC_ENABLED, enabled).commit()
    }

    fun saveLanguage(context: Context, tag: String) {
        languageValue = tag
        context.applicationContext
            .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, tag).commit()
    }

    fun saveNetworkAllowed(context: Context, allowed: Boolean) {
        networkValue = allowed
        context.applicationContext
            .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NETWORK, allowed).apply()
    }

    fun saveAccentKey(context: Context, key: String) {
        accentValue = key
        write(context, KEY_ACCENT, key)
    }

    /** 内存 → SP（启动可读）→ 后台落库（权威存储）。 */
    private fun write(context: Context, key: String, value: String) {
        val app = context.applicationContext
        val sp = app.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        if (key == KEY_APPEARANCE) sp.edit().putString(SP_APPEARANCE, value).commit()
        if (key == KEY_ACCENT) sp.edit().putString(SP_ACCENT, value).commit()

        Thread {
            runCatching {
                runBlocking { AppDatabase.get(app).settings().put(AppSettingEntity(key, value)) }
            }
        }.apply { name = "prefs-write"; isDaemon = true }.start()
    }
}
