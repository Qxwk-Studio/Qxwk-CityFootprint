package top.qxwkstudio.travel.ui.trips

/**
 * 城市节点配色：编辑页与详情页共用，保证同一趟行程两处颜色一致。
 *
 * 先按城市项 id 散列挑色；本次行程内尽量不重复，色板用尽后至少保证相邻城市不同。
 */
internal object CityColors {

    private val palette = intArrayOf(
        0xFF2563EB.toInt(), // 蓝
        0xFFDC2626.toInt(), // 红
        0xFF059669.toInt(), // 绿
        0xFFD97706.toInt(), // 琥珀
        0xFF7C3AED.toInt(), // 紫
        0xFF0891B2.toInt(), // 青
        0xFFDB2777.toInt(), // 粉
        0xFF65A30D.toInt(), // 青柠
    )

    fun assign(cityIds: List<String>): Map<String, Int> {
        val used = mutableSetOf<Int>()
        var previous = -1
        val out = LinkedHashMap<String, Int>()
        cityIds.forEach { id ->
            var index = kotlin.math.abs(id.hashCode()) % palette.size
            var guard = 0
            while (index == previous || (index in used && used.size < palette.size)) {
                index = (index + 1) % palette.size
                guard++
                if (guard >= palette.size) break
            }
            if (index == previous) index = (index + 1) % palette.size
            used += index
            previous = index
            out[id] = palette[index]
        }
        return out
    }
}
