package top.qxwkstudio.travel.data

import androidx.room.withTransaction
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.CheckinSource
import top.qxwkstudio.travel.data.db.CheckinEntity
import top.qxwkstudio.travel.logic.City
import top.qxwkstudio.travel.logic.DatePrecision
import top.qxwkstudio.travel.logic.Limits
import java.util.UUID

/**
 * 打卡写入。
 *
 * 派生打卡的增删**不在这里**：它们由 [TripRepo] 在行程写入的同一个事务里重算 ——
 * 两处都能改派生数据的话，迟早出现「编辑页删了行程、打卡还在」这种只在某个入口才复现的 bug。
 * 这里只管**手动打卡**（`source = 手动`）的增删改。
 */
object CheckinRepo {

    /**
     * 手动记一条打卡，返回 id。
     *
     * [checkinAt] 由调用方按精度补好（只记得年 → 当年 1 月 1 日，只记得年月 → 当月 1 日，
     * 记不清 → 当年 1 月 1 日占位）。仓库这边不做日期推算：怎么补是表单的口径（见 DatePrecision），
     * 混进数据层就没法解释了。
     */
    suspend fun createManual(
        db: AppDatabase,
        city: City,
        checkinAt: Long,
        datePrecision: Int = DatePrecision.DAY,
        note: String? = null,
        companions: String? = null,
        rating: Int? = null,
        photoPath: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Long {
        require(note.orEmpty().length <= Limits.NOTE) { "备注不能超过 ${Limits.NOTE} 字" }
        require(datePrecision in DatePrecision.YEAR..DatePrecision.UNKNOWN) { "日期精度取值非法：$datePrecision" }
        require(rating == null || rating in 1..5) { "评分需在 1–5 之间" }

        val id = db.withTransaction {
            db.checkins().insert(
                CheckinEntity(
                    uuid = UUID.randomUUID().toString(),
                    cityName = city.name,
                    cityCode = city.adcode,
                    province = city.province,
                    source = CheckinSource.MANUAL,
                    tripUuid = null,
                    checkinAt = checkinAt,
                    datePrecision = datePrecision,
                    note = note,
                    companions = companions,
                    rating = rating,
                    photoPath = photoPath,
                    seqNo = db.checkins().nextManualSeqNo(),
                    modifiedAt = now,
                )
            )
        }
        DataVersion.bump()
        return id
    }

    /** 改手动打卡。派生打卡不该走这里（它们没有可编辑的字段，要改就去改行程）。 */
    suspend fun updateManual(
        db: AppDatabase,
        checkin: CheckinEntity,
        now: Long = System.currentTimeMillis(),
    ) {
        require(checkin.source == CheckinSource.MANUAL) { "派生打卡不能单独编辑，请修改对应的行程" }
        db.checkins().update(checkin.copy(modifiedAt = now))
    }

    /** 软删一条打卡。派生打卡的删除走 [TripRepo.delete]/[TripRepo.update] 的重算。 */
    suspend fun delete(db: AppDatabase, id: Long, now: Long = System.currentTimeMillis()) {
        db.checkins().softDelete(id, now)
        DataVersion.bump()
    }

    /** 全部活着的打卡，按时间倒序（行程页「打卡」分段）。 */
    suspend fun list(db: AppDatabase): List<CheckinEntity> = db.checkins().list()

    /** 按时间正序（成就判定与统计用）。 */
    suspend fun listChronological(db: AppDatabase): List<CheckinEntity> = db.checkins().listChronological()

    /** 某座城市的打卡（地图城市面板）。 */
    suspend fun listForCity(db: AppDatabase, cityName: String): List<CheckinEntity> =
        db.checkins().listForCity(cityName)
}
