package top.qxwkstudio.travel.ui.common

import android.content.Context

/** 把 data/logic 层抛出的中文校验错误翻成英文；未命中就原样返回。 */
object ValidationText {

    fun localize(context: Context, message: String): String {
        if (context.resources.configuration.locales[0].language != "en") return message
        val patterns = listOf(
            Regex("^备注不能超过 (\\d+) 字$") to "Note cannot exceed $1 characters",
            Regex("^标题不能超过 (\\d+) 字$") to "Title cannot exceed $1 characters",
            Regex("^请填写标题$") to "Enter a title",
            Regex("^评分需在 1–10 之间$") to "Rating must be between 1 and 10",
            Regex("^总花费不能为负$") to "Total cost cannot be negative",
            Regex("^停留天数至少 1 天$") to "Days stayed must be at least 1",
            Regex("^日期精度不合法：(.+)$") to "Invalid date precision: $1",
            Regex("^行程不存在：(.+)$") to "Trip not found: $1",
            Regex("^派生打卡不能单独编辑，请修改对应的行程$") to "Derived check-ins cannot be edited directly; edit the trip instead",
        )
        patterns.forEach { (regex, replacement) ->
            val match = regex.matchEntire(message) ?: return@forEach
            return regex.replace(message, replacement)
        }
        return message
    }
}
