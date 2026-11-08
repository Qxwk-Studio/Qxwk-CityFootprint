package top.qxwkstudio.travel.data

/** 同步判定的纯函数，便于单测；网络与 Room 不在这里。 */
internal object SyncRules {
    /** 本地修改时间晚于上次同步时间才需要推送；墓碑删除同样用 modified_at。 */
    fun shouldPush(modifiedAt: Long, syncedAt: Long): Boolean = modifiedAt > syncedAt

    /** 游标只前进，不回退。 */
    fun nextCursor(current: Long, seq: Long): Long = maxOf(current, seq)

    /** 本地修改比远端新时不要用远端覆盖；force 用于用户明确选择“使用远端”。 */
    fun shouldApplyRemote(localModified: Long, remoteModified: Long, force: Boolean = false): Boolean =
        force || localModified <= remoteModified

    fun isConflict(status: String): Boolean = status == "conflict"
}
