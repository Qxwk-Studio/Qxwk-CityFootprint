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

    /**
     * 收集仍被引用的照片路径：活着的打卡 + 活着的行程（**全量 items**）。软删的行不算引用。
     *
     * 有行程的时间线解析不了时返回 **null**（而不是空集合）：调用方必须据此跳过清扫 ——
     * 拿空集合去清扫等于把全目录照片删光，宁可留垃圾也不能误删。
     */
    suspend fun referenced(db: AppDatabase): Set<String>? = buildSet {
        // 用「含墓碑」的全量：软删记录里的 photoPath 仍写在时间线/实体里，
        // 删除推送失败或远端复活后它就是唯一引用，按活着的数据收集会把它删掉
        db.checkins().allForSync().forEach { checkin ->
            checkin.photoPath?.split(",")?.filter { it.isNotBlank() }?.forEach(::add)
        }
        db.trips().allForSync().forEach { trip ->
            // 用 items 而不是 segments：第一个到访城市之前的交通/出发照片不属于任何城市段，
            // 按 segments 收集会把它们当孤儿删掉（时间线 JSON 里的路径还在 → 打开即破图）。
            val timeline = Timeline.decode(trip.timeline) ?: return null
            timeline.items.forEach { item ->
                item.photoPath?.split(",")?.filter { it.isNotBlank() }?.forEach(::add)
            }
        }
    }

    suspend fun sweep(context: Context, db: AppDatabase): Pair<Int, Long> {
        // 解析失败就不清扫：见 referenced 的注释
        val refs = referenced(db) ?: return 0 to 0L
        return PhotoFiles.cleanupUnreferenced(context, refs)
    }

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
