package top.qxwkstudio.travel.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用**真实数据**跑一遍判定：`assets/data/city_boundaries.geojson`（370 个市，4.6 MB）
 * + `assets/cities.json`（384 城）。
 *
 * 与 [CityLocatorTest] 的分工：那边用合成数据钉规则（射线法、洞、兜底、阈值），
 * 这边钉**数据管线**——边界文件确实转成了 WGS-84、解析器吃得下真实结构、城市名对得上城市表。
 * 这一层如果错了，规则再对也没用：症状是「所有城市都判错 500 米」或「一座城都判不出来」。
 *
 * 用相对路径读源码里的 assets（JVM 单测的工作目录就是 app/），不经过 Android 的 AssetManager，
 * 所以不需要设备。文件缺失时跳过（比如只 checkout 了部分文件），不让 CI 因数据缺失而红。
 */
class CityLocatorRealDataTest {

    private val boundariesFile = File("src/main/assets/data/city_boundaries.geojson")
    private val citiesFile = File("src/main/assets/cities.json")

    private val cities: List<City> by lazy { loadCities() }
    private val boundaries: String by lazy { boundariesFile.readText() }

    private fun loadCities(): List<City> {
        val root = Json.parseToJsonElement(citiesFile.readText()).jsonObject
        return root["cities"]!!.jsonArray.map { element ->
            val o = element.jsonObject
            City(
                name = o["name"]!!.jsonPrimitive.content,
                province = o["province"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                lat = o["lat"]!!.jsonPrimitive.doubleOrNull!!,
                lng = o["lng"]!!.jsonPrimitive.doubleOrNull!!,
                adcode = o["adcode"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    private fun locator(): CityLocator = CityLocator.build(cities, boundaries)

    @Test
    fun `真实边界能解析出三百多个面`() {
        assumeTrue(boundariesFile.exists())
        val l = locator()
        // 370 个有 adcode 的城市，MultiPolygon 会拆成多个面，所以面数只多不少
        assertTrue("解析出的面数=${l.shapeCount}", l.shapeCount >= 370)
    }

    @Test
    fun `天安门判成北京 说明边界确实转到了WGS84`() {
        assumeTrue(boundariesFile.exists())
        // 没转坐标的话这里会偏 500 多米，落在边界外、退化成一个「最近的城」——两种结果城市名可能相同，
        // 所以同时断言「不是兜底命中」：那才是「落在面里」的证据
        val hit = locator().locate(39.9087, 116.3975)
        assertNotNull(hit)
        assertEquals("北京", hit!!.city.name)
        assertTrue("应当落在多边形内部，而不是靠最近城市兜底", !hit.byCenterFallback)
    }

    @Test
    fun `外滩判成上海`() {
        assumeTrue(boundariesFile.exists())
        val hit = locator().locate(31.2397, 121.4900)!!
        assertEquals("上海", hit.city.name)
        assertTrue(!hit.byCenterFallback)
    }

    @Test
    fun `台湾岛内一律判成台湾`() {
        assumeTrue(boundariesFile.exists())
        // DataV 把整岛给了 710000：台北 101 也只能判成「台湾」，台北市本身没有自己的边界
        val hit = locator().locate(25.0340, 121.5654)!!
        assertEquals("台湾", hit.city.name)
    }

    @Test
    fun `境外远海返回null`() {
        assumeTrue(boundariesFile.exists())
        // 太平洋中部：离任何城市都远于 50 km，宁可不判也不要硬凑一个边境城市
        assertNull(locator().locate(28.2, -177.4))
    }
}
