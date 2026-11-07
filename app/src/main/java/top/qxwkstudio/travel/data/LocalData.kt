package top.qxwkstudio.travel.data

import androidx.room.withTransaction
import top.qxwkstudio.travel.data.db.AppDatabase

/** 清空用户数据但保留设置（昵称、主题、UUID 等）。 */
internal object LocalData {
    suspend fun wipe(db: AppDatabase) {
        db.withTransaction {
            db.trips().deleteAll()
            db.checkins().deleteAll()
            db.achievements().deleteAll()
        }
    }
}
