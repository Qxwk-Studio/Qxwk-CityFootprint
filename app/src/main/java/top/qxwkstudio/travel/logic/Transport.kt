package top.qxwkstudio.travel.logic

/**
 * 出行方式。**三方同一份枚举**：后端白名单 `backend/src/worker.js` 的 `TRANSPORTS`、
 * 网页端 `docs/app.js` 的 `window.TRANSPORTS`，以及这里的 [Transport]。
 * 三处的 **code 集合与顺序**必须一致 —— 顺序决定落库排序，也决定「最常用出行」并列时取哪一种。
 * 改这里就要同步改那两处（与城市表 cities.json 同样的「同源」约束）。
 *
 * 为什么是 enum 而不是一张 List：code 是后端唯一认的东西，写成枚举后拼错的 code 在编译期就过不去；
 * label 供界面展示（带 emoji），与网页端一致。
 * 纯 Kotlin、不 import android.*，所以能直接跑 JVM 单测（见 test/…/logic/TransportTest.kt）。
 */
enum class Transport(val code: String, val label: String) {
    PLANE("plane", "✈️ 飞机"),
    TRAIN("train", "🚆 火车"),
    HSR("hsr", "🚄 高铁"),
    CAR("car", "🚗 自驾"),
    BUS("bus", "🚌 大巴"),
    SHIP("ship", "🚢 轮船"),
    BIKE("bike", "🚲 骑行"),
    WALK("walk", "🥾 徒步"),
    OTHER("other", "🧭 其他"),
    ;

    companion object {
        /** 认不出来的 code 回 null（后端虽然会过滤，但旧数据/坏数据不该让界面崩）。 */
        fun fromCode(code: String): Transport? = entries.firstOrNull { it.code == code }
    }
}

/**
 * code 数组 → 展示标签数组（列表徽章用）。未知 code 直接丢掉，与网页 `transportLabels` 一致。
 * 后端已经按白名单顺序返回（worker.js 的 pickTransports），所以这里保持原顺序、不再重排。
 */
fun transportLabels(codes: List<String>): List<String> = codes.mapNotNull { Transport.fromCode(it)?.label }

/**
 * 「最常用出行」：把每条足迹的 transport 摊平计票，取票数最高的一种。
 * 并列时取 [Transport] 里靠前的（枚举声明顺序 = 网页 TRANSPORTS 顺序，与网页 `topTransportName` 同口径）；
 * 一条都没填回 null，界面显示「—」。
 *
 * 返回的是**去掉 emoji 的纯文字**：卡片标题已经带了 🚆，值里再来一个就重了（网页同处理）。
 */
fun topTransportLabel(visits: List<Visit>): String? {
    val votes = HashMap<String, Int>()
    visits.forEach { visit -> visit.transport.forEach { votes[it] = (votes[it] ?: 0) + 1 } }
    val best = votes.values.maxOrNull() ?: return null
    val top = Transport.entries.firstOrNull { votes[it.code] == best } ?: return null
    // label 形如 "✈️ 飞机"：空格前是 emoji，取空格之后那截
    return top.label.substringAfter(' ')
}