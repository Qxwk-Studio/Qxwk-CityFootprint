package top.qxwkstudio.travel.logic

/**
 * 省级行政区简称（用于卡片右侧的半透明水印）。
 *
 * 城市表里的 `province` 是「河北 / 内蒙古 / 北京」这类全称（不带「省/市/自治区」后缀）。
 * 这里给常用省份一个手写映射：**不能简单取首字**——河北简称「冀」、山西「晋」、上海「沪」。
 * 认不出的一律退回首字，保证永远有字可显示。
 */
object Provinces {

    private val ABBR = mapOf(
        "北京" to "京", "天津" to "津", "河北" to "冀", "山西" to "晋", "内蒙古" to "蒙",
        "辽宁" to "辽", "吉林" to "吉", "黑龙江" to "黑", "上海" to "沪", "江苏" to "苏",
        "浙江" to "浙", "安徽" to "皖", "福建" to "闽", "江西" to "赣", "山东" to "鲁",
        "河南" to "豫", "湖北" to "鄂", "湖南" to "湘", "广东" to "粤", "广西" to "桂",
        "海南" to "琼", "重庆" to "渝", "四川" to "川", "贵州" to "贵", "云南" to "云",
        "西藏" to "藏", "陕西" to "陕", "甘肃" to "甘", "青海" to "青", "宁夏" to "宁",
        "新疆" to "新", "台湾" to "台", "香港" to "港", "澳门" to "澳",
    )

    /** 省份简称；空省份返回空串（调用方据此决定要不要画水印）。 */
    fun abbr(province: String?): String {
        val name = province?.trim().orEmpty()
        if (name.isEmpty()) return ""
        // 容忍「河北省 / 北京市」这类带后缀的写法
        val key = name.removeSuffix("省").removeSuffix("市").removeSuffix("自治区")
            .removeSuffix("特别行政区")
        return ABBR[key] ?: key.take(1)
    }
}
