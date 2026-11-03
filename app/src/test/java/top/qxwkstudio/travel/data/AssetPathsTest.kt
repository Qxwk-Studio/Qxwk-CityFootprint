package top.qxwkstudio.travel.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * assets 路径的存在性校验。
 *
 * 起因：`CityBoundaries` 的资产路径漏了 `data/` 一级，`assets.open` 一直抛
 * FileNotFoundException，被 `runCatching` 吞成「空边界」—— 表现是地图上没有城市面、
 * 定位判城退化成最近城市中心（也能给出城市，所以长时间没人发现）。
 *
 * 这里直接对仓库里的文件做断言，路径常量一改错就会红。JVM 单测的工作目录是 `app/`。
 */
class AssetPathsTest {

    private fun exists(path: String): Boolean = File("src/main/assets/$path").exists()

    @Test
    fun `城市边界资产路径正确`() {
        assertTrue(
            "src/main/assets/${CityBoundaries.ASSET} 不存在（路径常量与文件不一致）",
            exists(CityBoundaries.ASSET),
        )
    }

    @Test
    fun `离线底图资产路径正确`() {
        assertTrue(
            "src/main/assets/${BasemapStore.ASSET} 不存在（路径常量与文件不一致）",
            exists(BasemapStore.ASSET),
        )
    }

    @Test
    fun `城市表资产在 assets 根目录`() {
        // CityStore 用的是 "cities.json"（根目录），与上面两个不同 —— 别顺手也加 data/
        assertTrue("src/main/assets/cities.json 不存在", exists("cities.json"))
    }
}
