package top.qxwkstudio.travel.logic

/**
 * visit_date 的格式契约。这是**跨端约定**，不是随手写的校验：
 *
 * 后端 backend/src/worker.js 的 POST /api/visits 里，允许的值只有三种形态 ——
 *   null（不填）、"2024"（只记得哪一年）、"2024-08"（记得年月），正则是 ^\d{4}(-\d{2})?$。
 * 客户端必须先做同样的校验：只靠服务端挡的话，用户填错了要等一次网络往返才被告知，
 * 而且失败时输入框内容还在，看起来像「点了保存没反应」。
 *
 * 两条**刻意不去「改进」**的地方：
 *   1. 正则不管月份范围 —— "2024-99" 后端也收。这里照抄，不擅自收紧：
 *      两边判断一旦不一致，就会出现「本地拦下、后端也认」这种最难查的分叉；
 *      真要收紧应该先改后端，再让两端同步。
 *   2. 不做「2024-8」这种补零修正。补零看着友好，却让「用户输入」与「存进库的值」不再相等，
 *      下次编辑时显示的是补零后的形态，用户会以为自己打错了。
 *
 * 展示形态（列表里显示什么）与存储形态分开：库里存 "2024-08"，界面显示 "2024年8月"。
 */
object VisitDate {
    /** 与后端完全一致的正则（见文件头）。 */
    private val PATTERN = Regex("""^\d{4}(-\d{2})?$""")

    /** 空串与纯空白等价于「不填」（后端收到空串会当 falsy 存成 null）。 */
    fun isValid(raw: String?): Boolean {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return true
        return PATTERN.matches(v)
    }

    /** 输入框文本 → 发给后端的值：空白一律发 null，不要发空串（省得后端/两端对「空」的理解不一）。 */
    fun toWire(raw: String?): String? {
        val v = raw?.trim().orEmpty()
        return if (v.isEmpty()) null else v
    }

    /** 列表里怎么显示。空 = 用户没填，显示「未填写」而不是空白（空白看起来像没加载出来）。 */
    fun display(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return "未填写"
        val parts = v.split("-")
        if (parts.size == 1) return "${parts[0]}年"
        val month = parts[1].toIntOrNull() ?: return v
        return "${parts[0]}年${month}月"
    }
}