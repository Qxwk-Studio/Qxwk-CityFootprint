package top.qxwkstudio.travel.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * 行程读写。
 *
 * **落库的唯一入口**：起止时间与总花销是 `trips.timeline` 的冗余列（见 TripEntity 的说明），
 * 三处必须一起写。谁要是绕过这里手拼 SQL，就会造出「列表排序用的是旧时间」这种只在特定操作后
 * 才出现的不一致。所以这里只提供 `upsert`，由调用方把推导好的值一起传进来。
 */
@Dao
interface TripDao {

    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    /** 新增或更新：id 为 0 视为新增，返回落库后的 id。 */
    suspend fun upsert(trip: TripEntity): Long =
        if (trip.id == 0L) insert(trip) else { update(trip); trip.id }

    @Query("SELECT * FROM trips WHERE id = :id AND deleted_at IS NULL")
    suspend fun byId(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE uuid = :uuid AND deleted_at IS NULL")
    suspend fun byUuid(uuid: String): TripEntity?

    /** 行程列表：按首个节点时间倒序（最新在上），与「行程」页的排序口径一致。 */
    @Query("SELECT * FROM trips WHERE deleted_at IS NULL ORDER BY start_time DESC, id DESC")
    suspend fun list(): List<TripEntity>

    @Query("SELECT * FROM trips")
    suspend fun allForSync(): List<TripEntity>

    @Query("DELETE FROM trips")
    suspend fun deleteAll()

    @Query("SELECT * FROM trips WHERE uuid = :uuid LIMIT 1")
    suspend fun byUuidAny(uuid: String): TripEntity?

    /** 下一个 `#编号`：取最大值 +1，**不复用**（删掉的行程不回收编号）。 */
    @Query("SELECT COALESCE(MAX(seq_no), 0) + 1 FROM trips")
    suspend fun nextSeqNo(): Int

    /** 软删。派生打卡由调用方一并重算（见 data/CheckinRepo）。 */
    @Query("UPDATE trips SET deleted_at = :at, modified_at = :at WHERE id = :id")
    suspend fun softDelete(id: Long, at: Long)
}

/**
 * 打卡读写。
 *
 * 派生打卡的重算是「物理删除 + 重新插入」：唯一索引 `(trip_uuid, city_name, source)` 不允许
 * 软删的旧行与新的行同时存在（SQLite 的唯一索引不认 `deleted_at`），而派生打卡本来就没有历史价值 ——
 * 它随时可以从行程时间线重新算出来。
 */
@Dao
interface CheckinDao {

    @Insert
    suspend fun insert(checkin: CheckinEntity): Long

    @Insert
    suspend fun insertAll(checkins: List<CheckinEntity>)

    @Update
    suspend fun update(checkin: CheckinEntity)

    /** 新增或更新：id 为 0 视为新增。 */
    suspend fun upsert(checkin: CheckinEntity): Long =
        if (checkin.id == 0L) insert(checkin) else { update(checkin); checkin.id }

    @Query("SELECT * FROM city_checkins WHERE id = :id AND deleted_at IS NULL")
    suspend fun byId(id: Long): CheckinEntity?

    /**
     * 未来的同步只取**手动打卡**：派生打卡是行程的产物，每台设备本地重算即可，
     * 同步它只会在两台设备上各算出一套重复数据。
     */
    @Query("SELECT * FROM city_checkins WHERE source = ${CheckinSource.MANUAL} AND deleted_at IS NULL")
    suspend fun syncableCheckins(): List<CheckinEntity>

    @Query("SELECT * FROM city_checkins WHERE source = ${CheckinSource.MANUAL}")
    suspend fun manualAllForSync(): List<CheckinEntity>

    @Query("SELECT * FROM city_checkins WHERE uuid = :uuid LIMIT 1")
    suspend fun byUuidAny(uuid: String): CheckinEntity?
    /**
     * 该行程的全部派生打卡，**含已软删的墓碑**（重算时按城市段序号复用行，避免与唯一索引冲突）。
     */
    @Query("SELECT * FROM city_checkins WHERE trip_uuid = :tripUuid AND source = ${CheckinSource.DERIVED}")
    suspend fun derivedForTrip(tripUuid: String): List<CheckinEntity>

    /**
     * 重算前把旧派生打卡打成墓碑（不再物理删除）：同步功能需要「删除」也能传播，
     * 物理删掉的话别的设备永远不知道这条被删了。
     */
    @Query("UPDATE city_checkins SET deleted_at = :at, modified_at = :at WHERE trip_uuid = :tripUuid AND source = ${CheckinSource.DERIVED}")
    suspend fun softDeleteDerivedForTrip(tripUuid: String, at: Long)

    /** 全部活着的打卡，按时间倒序（行程页的「打卡」分段用）。 */
    @Query("SELECT * FROM city_checkins WHERE deleted_at IS NULL ORDER BY checkin_at DESC, created_at DESC, id DESC")
    suspend fun list(): List<CheckinEntity>

    /** 全部活着的打卡，按时间正序（成就判定与统计用；顺序稳定才好回溯解锁日期）。 */
    @Query("SELECT * FROM city_checkins WHERE deleted_at IS NULL ORDER BY checkin_at ASC, created_at ASC, id ASC")
    suspend fun listChronological(): List<CheckinEntity>

    /** 某座城市的打卡（地图城市面板用），按时间倒序。 */
    @Query(
        "SELECT * FROM city_checkins WHERE city_name = :cityName AND deleted_at IS NULL " +
            "ORDER BY checkin_at DESC, created_at DESC, id DESC"
    )
    suspend fun listForCity(cityName: String): List<CheckinEntity>

    /** 去重城市名：统计与地图点亮都只要这个集合。 */
    @Query("SELECT DISTINCT city_name FROM city_checkins WHERE deleted_at IS NULL")
    suspend fun distinctCities(): List<String>

    /** 手动打卡的下一个 `#编号`（与行程各自一套序列）。派生打卡不显示编号，取 0 即可。 */
    @Query("SELECT COALESCE(MAX(seq_no), 0) + 1 FROM city_checkins WHERE source = 1")
    suspend fun nextManualSeqNo(): Int

    @Query("UPDATE city_checkins SET deleted_at = :at, modified_at = :at WHERE id = :id")
    suspend fun softDelete(id: Long, at: Long)

    /** 清空全部打卡（设置里的「清空数据」用，2.0 首发可能不摆入口，方法先留着）。 */
    @Query("DELETE FROM city_checkins")
    suspend fun deleteAll()
}

/** 成就状态读写。 */
@Dao
interface AchievementDao {

    /** 整体替换：每次重算把 54 条全部写一遍，省掉「哪些变了」的比对。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<AchievementStateEntity>)

    @Query("DELETE FROM achievement_states")
    suspend fun deleteAll()

    @Query("SELECT * FROM achievement_states")
    suspend fun list(): List<AchievementStateEntity>

    @Query("SELECT * FROM achievement_states WHERE code = :code")
    suspend fun byCode(code: String): AchievementStateEntity?
}

/** 设备级设置读写。 */
@Dao
interface SettingsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSettingEntity)

    @Query("SELECT value FROM app_settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM app_settings")
    suspend fun list(): List<AppSettingEntity>

    @Query("DELETE FROM app_settings WHERE key = :key")
    suspend fun remove(key: String)
}
