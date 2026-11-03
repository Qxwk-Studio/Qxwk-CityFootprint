package top.qxwkstudio.travel.data

import android.content.Context
import java.io.File

/**
 * 离线矢量底图（Protomaps 中国区 z0–6，打包在 `assets/basemap/cn-z0-6.pmtiles`）。
 *
 * **为什么要先拷到 filesDir**：MapLibre 的 PMTiles 读取在原生层，官方只认
 * `pmtiles://https://…` 与 `pmtiles://file://…` 两种 URL（见 2.0 工程口径文档 §7.1），
 * **不支持 `asset://`**。所以首次进地图页时把 5.8 MB 的资产拷到应用私有目录，之后直接用绝对路径。
 *
 * 判「要不要再拷」用文件大小而不是存在性：升级时 asset 变了（重新抽的包）而旧文件还在，
 * 只判存在就会一直读旧底图。大小不一致才重拷，代价只是多比较一次 stat。
 *
 * 版权：Protomaps 底图是 ODbL，地图上必须留 `© OpenStreetMap` 署名（见 MapFragment 的版权行）。
 */
object BasemapStore {

    /** 资产路径（相对 assets/）。单测 AssetPathsTest 校验它存在。 */
    internal const val ASSET = "basemap/cn-z0-6.pmtiles"
    private const val DIR = "basemap"
    private const val NAME = "cn-z0-6.pmtiles"

    /** 确保本地有一份最新的底图，返回它的绝对路径。**只能在 IO 线程调用**（要读 5.8 MB 资产）。 */
    fun ensure(context: Context): String {
        val app = context.applicationContext
        val dir = File(app.filesDir, DIR).apply { mkdirs() }
        val target = File(dir, NAME)
        val assetSize = runCatching { app.assets.openFd(ASSET).use { it.length } }.getOrNull()
        if (target.exists() && assetSize != null && target.length() == assetSize) {
            return target.absolutePath
        }
        app.assets.open(ASSET).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target.absolutePath
    }

    /** 给 style 用的 URL：`pmtiles://file://<绝对路径>`（三个斜杠是前缀 + file:// 拼出来的）。 */
    fun url(path: String): String = "pmtiles://file://$path"
}
