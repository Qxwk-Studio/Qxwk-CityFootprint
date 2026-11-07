package top.qxwkstudio.travel.data

/** 未处理的同步冲突；由同步页弹窗让用户选择。 */
data class SyncConflict(
    val entityType: String,
    val uuid: String,
    val serverRevision: Int,
    val localJson: String?,
    val remoteJson: String,
)
