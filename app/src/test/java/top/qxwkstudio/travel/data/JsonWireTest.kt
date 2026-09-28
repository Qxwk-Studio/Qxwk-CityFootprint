package top.qxwkstudio.travel.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qxwkstudio.travel.logic.Me
import top.qxwkstudio.travel.logic.Visit
import top.qxwkstudio.travel.logic.VisitDraft

/**
 * 报文契约测试：钉住 `data/Json.kt` 那两个开关（ignoreUnknownKeys / coerceInputValues）
 * 与 `logic/Models.kt` 的 @SerialName / IntBooleanSerializer。
 *
 * 为什么值得单开一个测试：这些东西**改了不会编译报错、也不会崩**，
 * 只会让请求体少发 / 多发一个字段，或把布尔发成 `true` —— 症状是「上线后某个功能悄悄不生效」，
 * 而那正是最难查的一类。期望值全部来自后端 worker.js 的取字段方式，不是「我觉得应该这样」。
 */
class JsonWireTest {

    // ── 请求体（VisitDraft 直接序列化发出去）──

    @Test
    fun `草稿请求体的字段名与后端一致`() {
        val draft = VisitDraft(city = "北京", lat = 39.9, lng = 116.4, note = "看升旗")
        val obj = json.parseToJsonElement(json.encodeToString(VisitDraft.serializer(), draft)) as JsonObject

        assertEquals("北京", obj["city"]!!.jsonPrimitive.content)
        assertEquals("看升旗", obj["note"]!!.jsonPrimitive.content)
        assertTrue("后端按 visit_date 取字段，改名或发成 visitDate 就丢日期", obj.containsKey("visit_date"))
        assertTrue("后端按 transport 取字段（worker.js pickTransports），名字要对上", obj.containsKey("transport"))
    }

    @Test
    fun `出行方式按 code 数组发出`() {
        // 后端 pickTransports 拿 TRANSPORTS 白名单去过滤数组里的每个字符串；
        // 发成 "plane,train" 这种逗号串的话，白名单一条都认不出，出行方式会静默丢光
        val draft = VisitDraft(city = "北京", lat = 39.9, lng = 116.4, transport = listOf("plane", "hsr"))
        val obj = json.parseToJsonElement(json.encodeToString(VisitDraft.serializer(), draft)) as JsonObject

        assertEquals(listOf("plane", "hsr"), obj["transport"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `日期为 null 时显式发出 而不是省略`() {
        // explicitNulls 默认 true：后端 PUT 是整体替换，显式 null 才能把已有日期清空
        val draft = VisitDraft(city = "北京", lat = 39.9, lng = 116.4)
        val obj = json.parseToJsonElement(json.encodeToString(VisitDraft.serializer(), draft)) as JsonObject

        assertTrue(obj.containsKey("visit_date"))
        assertEquals(JsonNull, obj["visit_date"])
    }

    @Test
    fun `私有标记发成 1 与 0 而不是布尔`() {
        // 后端 D1 没有布尔类型：is_private 存的就是 0/1
        val public = VisitDraft(city = "上海", lat = 31.2, lng = 121.5)
        val private = VisitDraft(city = "上海", lat = 31.2, lng = 121.5, isPrivate = true)

        val publicObj = json.parseToJsonElement(json.encodeToString(VisitDraft.serializer(), public)) as JsonObject
        val privateObj = json.parseToJsonElement(json.encodeToString(VisitDraft.serializer(), private)) as JsonObject

        assertEquals("0", publicObj["is_private"]!!.jsonPrimitive.content)
        assertEquals("1", privateObj["is_private"]!!.jsonPrimitive.content)
        assertFalse("is_private 不能是字符串", privateObj["is_private"]!!.jsonPrimitive.isString)
    }

    // ── 解析后端返回（蛇形字段 + 0/1）──

    @Test
    fun `解析足迹的蛇形字段`() {
        val visit = json.decodeFromString(
            Visit.serializer(),
            """{"id":7,"city":"广州","lat":23.1,"lng":113.2,"visit_date":"2024-08","note":"早茶","is_private":1,"transport":["train","walk"]}""",
        )

        assertEquals(7L, visit.id)
        assertEquals("2024-08", visit.visitDate)
        assertTrue(visit.isPrivate)
        assertEquals(listOf("train", "walk"), visit.transport)
    }

    @Test
    fun `后端没带 transport 时退回空列表`() {
        // 老数据 / 后端还没上这一版时，不能因为缺字段就崩，也不能显示一个假的出行方式
        val visit = json.decodeFromString(
            Visit.serializer(),
            """{"id":3,"city":"深圳","lat":22.5,"lng":114.0,"unknown":"x"}""",
        )

        assertEquals("深圳", visit.city)
        assertEquals("", visit.note)
        assertFalse(visit.isPrivate)
        assertNull(visit.visitDate)
        assertTrue(visit.transport.isEmpty())
    }

    @Test
    fun `显式 null 的字段退回默认值 而不是抛异常`() {
        // coerceInputValues：值为 JSON null 时用属性默认值兜底
        val visit = json.decodeFromString(
            Visit.serializer(),
            """{"id":1,"city":"杭州","lat":30.2,"lng":120.1,"visit_date":null,"is_private":null}""",
        )

        assertNull(visit.visitDate)
        assertFalse(visit.isPrivate)
    }

    @Test
    fun `Me 解析蛇形的 is_admin 与 created_at`() {
        val me = json.decodeFromString(
            Me.serializer(),
            """{"userId":1,"nickname":"阿姜","color":"#4285F4","is_admin":true,"created_at":"2024-01-01T00:00:00Z","avatar":null}""",
        )

        assertTrue(me.isAdmin)
        assertEquals("2024-01-01T00:00:00Z", me.createdAt)
        assertNull(me.avatar)
    }
}
