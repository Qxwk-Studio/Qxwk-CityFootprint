package top.qxwkstudio.travel.data

import androidx.room.withTransaction
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.CheckinSource
import top.qxwkstudio.travel.data.db.CheckinEntity
import top.qxwkstudio.travel.data.db.TripEntity
import top.qxwkstudio.travel.data.db.TripState
import top.qxwkstudio.travel.logic.City
import top.qxwkstudio.travel.logic.DatePrecision
import top.qxwkstudio.travel.logic.Limits
import top.qxwkstudio.travel.logic.Timeline
import top.qxwkstudio.travel.logic.deriveCheckins
import java.util.UUID

/**
 * 行程写入。**所有写操作都在一个事务里**，并顺手重算派生打卡 ——
 * 「行程改了但打卡没跟上」是 2.0 最难看的一类不一致：地图上少点亮一座城、
 * 成就也算少了，而用户明明刚存了一条足迹。
 *
 * 城市数据以 [CityLookup] 形式传入而不是在这里 `Store`/`Context` 满天飞：
 * 派生打卡的规则于是能在 JVM 单测里直接跑（见 logic/DerivedCheckin）。
 */
typealias CityLookup = (String) -> City?

/** 一条要写入的行程（新增与编辑共用）。[timeline] 必须已通过校验，仓库这边仍会再拦一道。 */
data class TripInput(
    val title: String,
    val note: String?,
    val timeline: Timeline,
)

object TripRepo {

    /**
     * 新增一条行程，返回落库后的 id。
     *
     * 编号（`#编号`）在这里分配：取当前最大值 +1，**不复用** —— 删掉 #7 之后新建的仍是 #8，
     * 因为编号是给人看的稳定标识，不是列表下标。
     *
     * 校验不过直接抛 [IllegalArgumentException]：表单那边本来就会先校验，走到这里说明是代码缺陷，
     * 不该悄悄存一条坏数据。
     */
    suspend fun create(
        db: AppDatabase,
        input: TripInput,
        lookup: CityLookup,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val id = db.withTransaction {
            validate(input)?.let { throw IllegalArgumentException(it) }

            val trip = TripEntity(
                uuid = UUID.randomUUID().toString(),
                seqNo = db.trips().nextSeqNo(),
                title = input.title,
                note = input.note,
                timeline = input.timeline.encode(),
                startTime = input.timeline.startTime,
                endTime = input.timeline.endTime,
                totalCostCents = input.timeline.totalCostCents,
                tripState = TripState.SAVED,
                modifiedAt = now,
            )
            val newId = db.trips().insert(trip)
            recomputeDerived(db, trip.copy(id = newId), lookup, now)
            newId
        }
        // 事务成功之后才记版本：事务回滚时不该让别的页面白刷一次
        DataVersion.bump()
        return id
    }

    /**
     * 整体替换一条行程（编辑器保存的语义就是「整条重写」，不是字段级 patch）。
     *
     * 换时间线之后派生打卡要重算：删掉这座城、新增那座城、改时间，三种情况都会发生。
     * [TripEntity.uuid] 保持不变 —— 派生打卡与以后的分享都靠它认这条行程。
     */
    suspend fun update(
        db: AppDatabase,
        id: Long,
        input: TripInput,
        lookup: CityLookup,
        now: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val existing = db.trips().byId(id) ?: throw IllegalArgumentException("行程不存在：$id")
            validate(input)?.let { throw IllegalArgumentException(it) }

            val updated = existing.copy(
                title = input.title,
                note = input.note,
                timeline = input.timeline.encode(),
                startTime = input.timeline.startTime,
                endTime = input.timeline.endTime,
                totalCostCents = input.timeline.totalCostCents,
                modifiedAt = now,
            )
            db.trips().update(updated)
            recomputeDerived(db, updated, lookup, now)
        }
        DataVersion.bump()
    }

    /**
     * 删除一条行程：软删行程本身（留痕），但**物理删**它的派生打卡。
     *
     * 理由与 CheckinDao.softDeleteDerivedForTrip 相同：派生打卡挂着唯一索引，软删的行会一直占着键；
     * 而且它随时能从时间线重算，没有留痕价值。手动打卡不受影响 —— 它们与行程没有归属关系。
     */
    suspend fun delete(db: AppDatabase, id: Long, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val existing = db.trips().byId(id) ?: return@withTransaction
            db.trips().softDelete(id, now)
            db.checkins().softDeleteDerivedForTrip(existing.uuid, now)
        }
        DataVersion.bump()
    }

    /** 行程列表（已按首个节点时间倒序）。时间线要不要解析由调用方决定 —— 列表只需要标题与时间。 */
    suspend fun list(db: AppDatabase): List<TripEntity> = db.trips().list()

    /** 取单条行程；时间线解不出来时返回 null（历史数据损坏时按「不可用」处理，不让页面崩）。 */
    suspend fun byId(db: AppDatabase, id: Long): Pair<TripEntity, Timeline>? {
        val entity = db.trips().byId(id) ?: return null
        val timeline = Timeline.decode(entity.timeline) ?: return null
        return entity to timeline
    }

    /**
     * 重算派生打卡：把旧派生行打成墓碑，再按时间线更新/插入。
     *
     * 两个为「未来同步」做的决定：
     *  1. 不再物理删除 —— 删除要能通过墓碑传播到别的设备；
     *  2. uuid 由 (行程 uuid, 城市段序号) 确定性生成 —— 同一段在任何设备/任何一次重算
     *     都是同一个身份，不会每次同步都变成「新记录」。v3（MD5）足够，不需要随机。
     */
    private suspend fun recomputeDerived(
        db: AppDatabase,
        trip: TripEntity,
        lookup: CityLookup,
        now: Long,
    ) {
        // 先按城市段序号记下旧行（含墓碑），再全部打成墓碑
        val existing = db.checkins().derivedForTrip(trip.uuid).associateBy { it.segmentIndex }
        db.checkins().softDeleteDerivedForTrip(trip.uuid, now)

        val timeline = Timeline.decode(trip.timeline) ?: return
        val derived = deriveCheckins(timeline, lookup)
        derived.forEach { checkin ->
            val uuid = derivedUuid(trip.uuid, checkin.segmentIndex)
            val old = existing[checkin.segmentIndex]
            if (old != null) {
                db.checkins().update(
                    old.copy(
                        uuid = uuid,
                        cityName = checkin.cityName,
                        cityCode = checkin.cityCode,
                        province = checkin.province,
                        checkinAt = checkin.checkinAt,
                        datePrecision = DatePrecision.DAY,
                        companions = checkin.companions,
                        rating = checkin.rating,
                        photoPath = checkin.photoPath,
                        note = checkin.note,
                        amountCents = checkin.amountCents,
                        stayDays = checkin.stayDays,
                        createdAt = now,
                        seqNo = 0,
                        modifiedAt = now,
                        deletedAt = null,
                    ),
                )
            } else {
                db.checkins().insert(
                    CheckinEntity(
                        uuid = uuid,
                        cityName = checkin.cityName,
                        cityCode = checkin.cityCode,
                        province = checkin.province,
                        source = CheckinSource.DERIVED,
                        tripUuid = trip.uuid,
                        checkinAt = checkin.checkinAt,
                        datePrecision = DatePrecision.DAY,
                        companions = checkin.companions,
                        rating = checkin.rating,
                        photoPath = checkin.photoPath,
                        note = checkin.note,
                        segmentIndex = checkin.segmentIndex,
                        amountCents = checkin.amountCents,
                        stayDays = checkin.stayDays,
                        createdAt = now,
                        seqNo = 0,
                        modifiedAt = now,
                    ),
                )
            }
        }
    }

    /** 派生打卡的稳定身份：同一行程的同一城市段永远得到同一个 uuid。 */
    private fun derivedUuid(tripUuid: String, segmentIndex: Int): String =
        UUID.nameUUIDFromBytes("derived:$tripUuid:$segmentIndex".toByteArray()).toString()
    /** 与表单同一套校验，再补上标题（时间线管不到它）。 */
    private fun validate(input: TripInput): String? {
        if (input.title.isBlank()) return "请填写标题"
        if (input.title.length > Limits.SHORT) return "标题不能超过 ${Limits.SHORT} 字"
        val note = input.note.orEmpty()
        if (note.length > Limits.NOTE) return "备注不能超过 ${Limits.NOTE} 字"
        return input.timeline.validate()
    }
}
