package top.qxwkstudio.travel.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.logic.Timeline

/**
 * 照片引用清扫。
 *
 * **为什么不是「删记录时删文件」**：城市段的照片会复制给派生打卡（同一路径两处引用），
 * 删其中一条就删文件会把另一处弄成破图；而且事务回滚后文件也不该已经没了。
 *
 * 这里只做一件事：把所有**活着**的记录引用的路径收集起来，目录里剩下的就是孤儿。
 * 幂等、与删除动作解耦，崩溃留下的垃圾下次启动也会收掉。
 */
object PhotoJanitor {

    /** 收集仍被引用的照片路径：活着的打卡 + 活着的行程（城市段）。软删的行不算引用。 */
    suspend fun referenced(db: AppDatabase): Set<String> = buildSet {
        db.checkins().list().forEach { checkin ->
            checkin.photoPath?.split(",")?.filter { it.isNotBlank() }?.forEach(::add)
        }
        db.trips().list().forEach { trip ->
            Timeline.decode(trip.timeline)?.segments?.forEach { segment ->
                segment.photoPath?.split(",")?.filter { it.isNotBlank() }?.forEach(::add)
                segment.events.forEach { event ->
                    event.photoPath?.split(",")?.filter { it.isNotBlank() }?.forEach(::add)
                }
            }
        }
    }

    suspend fun sweep(context: Context, db: AppDatabase): Pair<Int, Long> =
        PhotoFiles.cleanupUnreferenced(context, referenced(db))

    /** 后台跑一次（启动、删除成功后调用）；不关心结果。 */
    fun sweepAsync(context: Context) {
        val app = context.applicationContext
        Thread {
            runCatching { runBlocking { sweep(app, AppDatabase.get(app)) } }
        }.apply { name = "photo-janitor"; isDaemon = true }.start()
    }

    /** 纯函数：给定目录里的文件与引用集合，挑出应删的。单测直接喂字符串。 */
    fun unreferenced(files: List<String>, referenced: Set<String>): List<String> =
        files.filter { it !in referenced }
}
