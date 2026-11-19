package top.qxwkstudio.travel.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.room.InvalidationTracker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.db.AppDatabase

/**
 * 自动同步调度：进程启动、行程/打卡变更后做一次延迟合并同步。
 * 只在「同步开启 + 已保存凭证」时运行；手动同步也走这里，避免两路并发。
 */
internal object SyncScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var appContext: Context? = null
    private var debounce: Job? = null

    @Volatile private var ignoreUntil = 0L

    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val db = AppDatabase.get(app)
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer("trips", "city_checkins") {
            override fun onInvalidated(tables: Set<String>) {
                request(2500)
            }
        })
        request(600)
    }

    fun request(delayMs: Long = 2500) {
        val app = appContext ?: return
        if (!Prefs.legalAccepted || !Prefs.networkAllowed || !Prefs.syncEnabled ||
            Prefs.syncToken.isBlank() || Prefs.syncServer.isBlank()) return
        // 同步刚结束的冷却窗口：不是丢弃这次编辑，而是等窗口结束再排一次，
        // 否则同步结束后几秒内的改动会既不进队也不补发
        val wait = (ignoreUntil - System.currentTimeMillis()).coerceAtLeast(0L)
        debounce?.cancel()
        debounce = scope.launch {
            if (wait > 0) delay(wait)
            delay(delayMs)
            if (System.currentTimeMillis() < ignoreUntil) return@launch
            runNow(app, force = false)
        }
    }

    fun cancel() {
        debounce?.cancel()
        debounce = null
    }

    suspend fun runNow(context: Context, force: Boolean): Result<SyncEngine.Report>? {
        if (!Prefs.legalAccepted) return null
        // 网络开关是无条件闸门：force 只跳过「节流/开关未开」的等待，不能绕过用户关掉的网络
        if (!Prefs.networkAllowed) return null
        if (!force && (!Prefs.syncEnabled || Prefs.syncToken.isBlank())) return null
        if (Prefs.syncToken.isBlank() || Prefs.syncServer.isBlank()) return null
        return mutex.withLock {
            ignoreUntil = System.currentTimeMillis() + 5000
            var last: Exception? = null
            try {
                for (attempt in 0..2) {
                    try {
                        val report = SyncEngine.sync(context, AppDatabase.get(context), Prefs.syncToken)
                        Prefs.saveSyncResult(context, report.pushed, report.pulled, report.conflicts, true)
                        SyncEvents.notifyChanged()
                        return@withLock Result.success(report)
                    } catch (e: Exception) {
                        last = e
                        if (classify(e) == "auth") {
                            // 只清 token，保留同步开关、数据和本地凭证，用户可直接一键重登。
                            Prefs.clearSyncSession(context)
                            Prefs.saveSyncResult(context, 0, 0, 0, false, "auth")
                            cancel()
                            return@withLock Result.failure(e)
                        }
                        if (attempt < 2) delay(1000L * (attempt + 1))
                    }
                }
                val error = classify(last ?: IllegalStateException("sync failed"))
                Prefs.saveSyncResult(context, 0, 0, 0, false, error)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, R.string.sync_retry_failed, Toast.LENGTH_LONG).show()
                }
                Result.failure(last ?: IllegalStateException("sync failed"))
            } finally {
                ignoreUntil = System.currentTimeMillis() + 3000
            }
        }
    }

    private fun classify(error: Throwable): String {
        val msg = error.message.orEmpty()
        return when {
            msg.contains("401") || msg.contains("unauthorized") || msg.contains("登录") -> "auth"
            msg.contains("网络") || msg.contains("timeout") || msg.contains("Failed to connect") ||
                msg.contains("Unable to resolve") || msg.contains("UnknownHost") -> "network"
            msg.contains("服务器地址") || msg.contains("server") || msg.contains("HTTP 4") -> "server"
            else -> "unknown"
        }
    }
}
