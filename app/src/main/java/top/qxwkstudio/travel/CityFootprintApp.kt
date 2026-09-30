package top.qxwkstudio.travel

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import top.qxwkstudio.travel.data.Appearance
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.Store

/**
 * 进程入口。两件事：
 *
 * 1. 在任何一个 Activity 起来之前，把用户选的外观（浅色 / 深色 / 跟随系统，见 `Store.appearance`）
 *    交给 AppCompat。**必须在进程最早处做**：`AppCompatDelegate.setDefaultNightMode` 是进程级静态值，
 *    晚于第一个 Activity 的 onCreate 设置，那个页面会先按系统默认（跟随系统）渲染一帧再被重建 ——
 *    有设置项的人每次冷启动都会看到一次闪烁。为什么不写在 MainActivity.onCreate（它也是 LAUNCHER）：
 *    进程被杀后从最近任务回来时，系统可能直接重建编辑页 / 城市选择页 / 网页页，那几条路径根本不经过
 *    MainActivity，外观就丢了。
 * 2. 后台预热城市表，见 [preloadCities]。
 *
 * 清单里的 `<application android:name=".CityFootprintApp">` 是这层生效的前提，删掉那一行本类就不再被调用。
 */
class CityFootprintApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 读一次本机偏好（SharedPreferences 首读要落一次磁盘，就这么点开销），随后交给 AppCompat
        applyAppearance(Store(this).appearance)
        preloadCities()
    }

    /**
     * 后台预热城市表（assets/cities.json，四百多条）。
     *
     * 为什么不在页面上按需读：主页与统计页首帧要在**主线程**用它查省份
     * （见 ui/VisitsFragment.renderOverview 与 ui/StatsFragment.render —— 两者都跑在协程的 done 回调里，
     * 而 done 一定在主线程）。不预热，那一帧就得在主线程读 asset 再反序列化整份文件。
     * 冷启动到首帧之间隔着 /api/me 与 /api/my-visits 两次往返，这点时间足够后台把表解出来。
     *
     * 起裸 Thread 而不是协程：这里没有现成的 scope（ui/appScope 是给退出登录那一次用的），
     * 而 CityStore.all 内部有 synchronized 兜底 —— 万一主线程先到，它等的是同一次解析，不会解两遍。
     * daemon：预热没跑完也不该拖着进程不退。
     */
    private fun preloadCities() {
        Thread { CityStore.all(this) }
            .apply { name = "cities-preload"; isDaemon = true }
            .start()
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