package top.qxwkstudio.travel.ui.trips

/**
 * 行程页多选模式与宿主（主壳）之间的契约。
 *
 * 分工：主壳管「外壳」——顶栏换成返回键 + 统计文案、底栏换成多选功能区、返回键截获；
 * 行程页管「内容」——选择集合、指示器与边框、全选/删除/导出。
 * 两边都不去直接操作对方的视图，否则多选一开就变成两处各改一半的散装状态。
 */
internal interface TripsSelectionHost {

    /** 选择数量变了：刷新顶栏统计，并决定删除/导出是否可用。 */
    fun onTripsSelectionCounts(checkins: Int, trips: Int)
}
