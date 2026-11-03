package top.qxwkstudio.travel.data

import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地数据的版本号：**写操作自增，页面拿它判断要不要重读**。
 *
 * 为什么需要它：五个 tab 是 add + hide/show，Fragment 之间互不知道对方写了数据；
 * 主页与行程页在别的 tab 显示时仍是 RESUMED，切回来不会触发 onResume。
 * 没有这个号的话，「记完一条打卡 → 切到主页」看到的还是旧统计，只能杀进程。
 *
 * 做法与 1.x 的 `Store.dataVersion` 一个意思（那边还兼职作废报文缓存，这里只管本地库）。
 * 只在**写成功后**自增：读操作不该让别人的页面白刷一次。
 *
 * 只存在内存里：进程重启本来就要重新加载，落盘反而多一份会过期的状态。
 */
object DataVersion {

    private val counter = AtomicInteger(0)

    val current: Int get() = counter.get()

    fun bump() {
        counter.incrementAndGet()
    }
}
