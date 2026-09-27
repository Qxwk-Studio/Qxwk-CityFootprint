package top.qxwkstudio.travel.data

import kotlinx.serialization.json.Json

/**
 * 全局共用的 Json 实例（构造一次即可，别在每个请求里新建）。
 *
 * 三个开关都是为了对齐旧 org.json 那套的语义，别顺手关掉：
 *  - **ignoreUnknownKeys**：通行证是另一个仓库（Qxwk-Account）在维护，返回的字段比我们用的多，
 *    严格模式下多一个键就直接抛异常 —— 对方加个字段就把我们登录搞崩，接受不了；
 *  - **coerceInputValues**：字段缺失或值为 JSON null 时退回属性默认值，
 *    与旧的 `optString(...).orEmpty()` / `optInt(...)` / `?: 0.0` 是同一个语义。
 *    （所以 logic/Models.kt 里每个属性都写了默认值，那不是随手加的。）
 *  - **encodeDefaults**：**必须显式打开**。它的默认值是 false，即「值等于属性默认值时就不写这个键」。
 *    而 VisitDraft 的 `visit_date` 默认就是 null，于是「清空日期」时该键会被整个省掉 ——
 *    这正是旧的手写 `bodyOf` **每次都把六个字段全写上**（`json.put("visit_date", ... ?: NULL)`）的原因：
 *    后端 PUT 是整体替换（worker.js 的 SET 里带着 visit_date = ?），显式 null 才让这件事在报文里看得见。
 *    契约测试见 src/test/.../data/JsonWireTest.kt。
 *
 * explicitNulls 保持默认的 true（与 encodeDefaults 一起才发得出 `"visit_date":null`）。
 */
internal val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}
