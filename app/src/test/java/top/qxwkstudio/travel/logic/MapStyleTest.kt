package top.qxwkstudio.travel.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地图 style 的字段契约。
 *
 * 直接起因：第一版把 PMTiles 归档写进了 `tiles` 数组（那里要的是 `{z}/{x}/{y}` 模板），
 * 真机上一片空白、且没有任何报错。style 写错是「安静失败」的典型，所以用测试把字段钉住。
 */
class MapStyleTest {

    private val url = "pmtiles://file:///data/user/0/top.qxwkstudio.travel.dev/files/basemap/cn-z0-7.pmtiles"

    private val raw = MapStyle.json(
        url,
        MapStyle.Colors(
            background = "#F8FAFC",
            land = "#FFFFFF",
            water = "#DBEAFE",
            road = "#E2E8F0",
        ),
    )

    private val root = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `是合法的 style JSON`() {
        assertEquals(8, root.getValue("version").jsonPrimitive.int)
        assertTrue(root.getValue("layers").jsonArray.isNotEmpty())
    }

    @Test
    fun `PMTiles 源用 url 而不是 tiles 模板`() {
        val source = root.getValue("sources").jsonObject.getValue("basemap").jsonObject
        assertEquals("vector", source.getValue("type").jsonPrimitive.contentOrNull)
        // 这两条是回归点：写成 tiles 会导致底图静默空白
        assertFalse("PMTiles 归档不能写进 tiles 数组", source.containsKey("tiles"))
        assertEquals(url, source.getValue("url").jsonPrimitive.contentOrNull)
    }

    @Test
    fun `图层顺序从底到顶`() {
        val ids = root.getValue("layers").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.contentOrNull }
        assertEquals(listOf("bg", "earth", "landuse", "water", "roads", "boundaries"), ids)
    }

    @Test
    fun `配色写进了 background 层`() {
        val bg = root.getValue("layers").jsonArray.first { it.jsonObject.getValue("id").jsonPrimitive.contentOrNull == "bg" }
        val color = bg.jsonObject.getValue("paint").jsonObject.getValue("background-color").jsonPrimitive.contentOrNull
        assertEquals("#F8FAFC", color)
    }

    @Test
    fun `每层都指向 basemap 源（除背景层）`() {
        root.getValue("layers").jsonArray.drop(1).forEach { layer ->
            assertEquals("basemap", layer.jsonObject.getValue("source").jsonPrimitive.contentOrNull)
            assertTrue(layer.jsonObject.containsKey("source-layer"))
        }
    }
}
