package top.qxwkstudio.travel.logic

import java.util.Locale

/** 金额显示：分转元；整数元不带小数，有角分时固定两位，避免列表里 12.5 / 12.50 混用。 */
object Money {
    fun yuan(cents: Int): String =
        if (cents % 100 == 0) {
            (cents / 100).toString()
        } else {
            String.format(Locale.US, "%.2f", cents / 100.0)
        }
}
