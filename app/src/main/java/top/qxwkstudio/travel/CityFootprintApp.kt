package top.qxwkstudio.travel

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import top.qxwkstudio.travel.data.Appearance
import top.qxwkstudio.travel.data.Store

/**
 * 进程入口。存在的**唯一理由**：在任何一个 Activity 起来之前，把用户选的外观
 * （浅色 / 深色 / 跟随系统，见 `Store.appearance`）交给 AppCompat。
 *
 * 为什么不能在页面里设置：`AppCompatDelegate.setDefaultNightMode` 是进程级静态值，必须在
 * **任何 Activity 的 onCreate 之前**定下来，否则先起来的那个页面会按系统默认（跟随系统）渲染一帧，
 * 再被重建一次 —— 有设置项的人每次冷启动都会看到一次闪烁。
 *
 * 为什么不写在 MainActivity.onCreate（它也是 LAUNCHER）：进程被杀后从最近任务回来时，系统可能直接
 * 重建编辑页 / 城市选择页 / 网页页，那几条路径根本不会经过 MainActivity，外观就丢了。
 *
 * 清单里的 `<application android:name=".CityFootprintApp">` 是这层生效的前提，删掉那一行本类就不再被调用。
 */
class CityFootprintApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 读一次本机偏好（SharedPreferences 首读要落一次磁盘，就这么点开销），随后交给 AppCompat
        applyAppearance(Store(this).appearance)
    }

    companion object {
        /**
         * 把外观偏好落到 AppCompat 的夜间模式上。三档里只有「跟随系统」交给 AppCompat 自己跟
         * （`MODE_NIGHT_FOLLOW_SYSTEM`），另外两档是硬钉死 —— 系统深浅色变化时也不动摇。
         *
         * **调用会重建所有正在显示的 Activity**（AppCompat 在值发生变化时才重建，选了当前那一档等于没动）：
         * 调用点若还挂着弹窗、对话框，先收掉再调，免得它们挂在一个正在销毁的窗口上。
         *
         * 放在伴生对象里而不是散成顶层函数：启动（本类）与「我的」页改设置（ProfileFragment）
         * 两处共用同一份映射，多一份 when 就会多一个「三档里漏掉一档」的机会。
         */
        fun applyAppearance(appearance: Appearance) {
            AppCompatDelegate.setDefaultNightMode(
                when (appearance) {
                    Appearance.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    Appearance.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    Appearance.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                }
            )
        }
    }
}