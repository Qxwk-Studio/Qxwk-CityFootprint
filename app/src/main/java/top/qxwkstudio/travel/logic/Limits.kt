package top.qxwkstudio.travel.logic

/**
 * 文本长度上限（2.0 设计口径）：短文本 **23**、备注 **233**。
 *
 * 放在一处而不是各自散在表单里：标题、交通方式、活动名、地点名共用 23 这一档，
 * 打卡备注与足迹备注共用 233 这一档 —— 哪天要改，改这里一处，别在两个 Activity 里各改一遍再对不上。
 *
 * 计数口径是**字符数**（`String.length`，即 UTF-16 code unit）：emoji 算 2 个。
 * 这与输入框 `maxLength` 的行为一致，界面上的「0/233」与这里的校验必须用同一个数法。
 */
object Limits {
    /** 短文本：足迹标题、交通方式、活动名称、地点名称。 */
    const val SHORT = 23

    /** 长文本：打卡备注、足迹备注。 */
    const val NOTE = 233
}
