package top.qxwkstudio.travel.ui.common

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatActivity
import top.qxwkstudio.travel.data.Prefs
import java.util.Locale

/**
 * 所有 Activity 的基类：在 attachBaseContext 阶段就套上目标语言。
 *
 * 不能只靠 AppCompat 的 per-app locale：它会把上一次显式选择的语言持久化，
 * 选「跟随系统」后仍可能沿用旧的英文资源。这里对「跟随系统」直接取系统 Resources 的 Locale，
 * 对显式语言取 Prefs 里的标签，保证每次都从 Context 层覆盖。
 */
open class LocalizedActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val tag = Prefs.languageTag
        val locale = if (tag.isBlank()) {
            Resources.getSystem().configuration.locales[0]
        } else {
            Locale.forLanguageTag(tag)
        }
        val config = Configuration(newBase.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
}
