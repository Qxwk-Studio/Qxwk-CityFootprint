package top.qxwkstudio.travel.ui.common

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import top.qxwkstudio.travel.R
import java.util.Locale

/**
 * 切换应用语言后退出进程。
 *
 * 只写 Prefs，不自动重启；Toast 按「目标语言」生成，而不是当前页面语言 —— 否则从英文切中文时
 * 提示仍然是英文。600ms 后结束进程，用户重新打开后由 LocalizedActivity 应用新语言。
 */
object AppRestart {

    fun exitForLanguageChange(context: Context, targetTag: String) {
        val locale = if (targetTag.isBlank()) {
            Resources.getSystem().configuration.locales[0]
        } else {
            Locale.forLanguageTag(targetTag)
        }
        val localized = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(locale) },
        )
        Toast.makeText(localized, localized.getString(R.string.language_restart_hint), Toast.LENGTH_LONG).show()
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 600L)
    }
}
