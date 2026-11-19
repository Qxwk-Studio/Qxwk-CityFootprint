package top.qxwkstudio.travel.data

import android.content.Context
import androidx.room.withTransaction
import top.qxwkstudio.travel.data.db.AppDatabase

/** 清空用户数据但保留设置（昵称、主题、UUID 等）。 */
internal object LocalData {

    suspend fun wipe(context: Context, db: AppDatabase) {
        db.withTransaction {
            db.trips().deleteAll()
            db.checkins().deleteAll()
            db.achievements().deleteAll()
        }
        // 表清空后照片就成了孤儿：顺手清扫，否则「换账号」会把上一账号的照片留在私有目录里
        PhotoJanitor.sweep(context.applicationContext, db)
        // 清库也是数据变更：让各页面与同步调度知道要刷新，别拿着旧清单继续跑
        DataVersion.bump()
        SyncEvents.notifyChanged()
    }
}
