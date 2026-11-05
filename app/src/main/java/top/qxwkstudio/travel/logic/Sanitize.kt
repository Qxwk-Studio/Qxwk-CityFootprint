package top.qxwkstudio.travel.logic

/**
 * 文本入口的统一清洗。
 *
 * 只处理「显示上会造成麻烦」的字符，不改用户语义：
 *  - C0/C1 控制字符、Bidi 覆盖、零宽字符 → 去掉（防止把界面文字方向搞乱或塞进隐形内容）
 *  - 单行字段把换行压成空格；多行字段统一 \n
 *  - 最后按字段上限截断（UI 的 maxLength 只是第一道，粘贴/输入法仍可能绕过）
 */
object Sanitize {

    private val singleLineJunk =
        Regex("[\\u0000-\\u001F\\u007F\\u202A-\\u202E\\u2066-\\u2069\\u200B-\\u200F]")
    private val multiLineJunk =
        Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u202A-\\u202E\\u2066-\\u2069\\u200B-\\u200F]")

    fun line(value: String?, max: Int): String =
        (value ?: "").replace(singleLineJunk, " ").replace(Regex("\\s+"), " ").trim().take(max)

    fun text(value: String?, max: Int): String =
        (value ?: "").replace("\r\n", "\n").replace('\r', '\n')
            .replace(multiLineJunk, "").trim().take(max)
}
