package top.qxwkstudio.travel.data

import androidx.room.withTransaction
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.CheckinSource

/** 按行程页卡片的当前顺序，重新分配本地展示编号。 */
object RenumberRepo {

    suspend fun renumber(db: AppDatabase): Int {
        Prefs.requireLegalAccepted()
        val changed = db.withTransaction {
            var count = 0
            // 行程列表：start_time DESC, id DESC，与卡片顺序一致；最下面一张为 #1。
            val trips = db.trips().list()
            trips.forEachIndexed { index, trip ->
                val next = trips.size - index
                if (trip.seqNo != next) {
                    db.trips().update(trip.copy(seqNo = next))
                    count++
                }
            }
            // 手动打卡：checkin_at DESC；派生打卡不参与自己的编号，最下面一张为 #1。
            val manual = db.checkins().list().filter { it.source == CheckinSource.MANUAL }
            manual.forEachIndexed { index, checkin ->
                val next = manual.size - index
                if (checkin.seqNo != next) {
                    db.checkins().update(checkin.copy(seqNo = next))
                    count++
                }
            }
            count
        }
        DataVersion.bump()
        SyncEvents.notifyChanged()
        return changed
    }
}
