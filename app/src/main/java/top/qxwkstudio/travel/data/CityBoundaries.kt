package top.qxwkstudio.travel.data

import android.content.Context
import top.qxwkstudio.travel.logic.CityLocator

/**
 * 城市边界（`assets/data/city_boundaries.geojson`，4.6 MB）的读取与内存缓存。
 *
 * 为什么整段解析：边界文件是**生成物**、结构固定，一次解析成扁平数组之后命中判定只要一次
 * 线性扫描；反过来做成按需流式读取，每次定位都要重新扫文件，手机上代价更大。
 * 解析一次约几十毫秒、几十 MB 临时内存，进程内缓住即可（与 [CityStore] 同一套办法）。
 *
 * 解析失败（文件被裁掉、格式变了）时**不崩**：[CityLocator] 会退化成「只有最近城市兜底」，
 * 定位仍然能用，只是判不准边界 —— 边界本来就是附加信息（与地图页的降级口径一致）。
 */
object CityBoundaries {

    /**
     * 资产路径（相对 assets/）。**必须带 data/ 前缀** —— 第一条实现漏了它，
     * 于是 assets.open 一直抛 FileNotFoundException，被 runCatching 吞成「空边界」：
     * 地图上没有城市面、定位判城退化成「最近城市中心」兜底（也能给出城市，所以一直没人发现）。
     * 单测 AssetPathsTest 会校验这个路径真能对上仓库里的文件。
     */
    internal const val ASSET = "data/city_boundaries.geojson"

    @Volatile
    private var cache: CityLocator? = null

    fun locator(context: Context): CityLocator {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: load(context).also { cache = it }
        }
    }

    /** 上一次读到的边界资产字符数；-1 表示还没读。诊断用。 */
    @Volatile
    var lastAssetChars: Int = -1
        private set

    /** 资产读取失败的原因（成功为 null）。诊断用。 */
    @Volatile
    var lastAssetError: String? = null
        private set

    private fun load(context: Context): CityLocator {
        // applicationContext：缓存里不该拽住任何 Activity
        val app = context.applicationContext
        val boundaries = try {
            app.assets.open(ASSET).bufferedReader().use { it.readText() }.also {
                lastAssetChars = it.length
                lastAssetError = null
            }
        } catch (t: Throwable) {
            lastAssetError = t.javaClass.simpleName + ": " + (t.message ?: "")
            lastAssetChars = 0
            ""
        }
        return CityLocator.build(CityStore.all(app), boundaries)
    }
}
