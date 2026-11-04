package top.qxwkstudio.travel.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import top.qxwkstudio.travel.logic.DatePrecision

/**
 * 2.0 的本地库（SQLite / Room）。**这里是数据的唯一真相**：2.0 是离线单机版，
 * 没有后端可回退，所以表结构一旦定错，代价是用户的数据而不是一次请求。
 *
 * 四张表对应四类东西：
 *  - [TripEntity]：行程（带时间线的复杂记录）
 *  - [CheckinEntity]：打卡（手动打卡 + 由行程派生的关联打卡，同一张表靠 [CheckinSource] 区分）
 *  - [AchievementStateEntity]：成就状态（达成与否可重算，但解锁时间必须存）
 *  - [AppSettingEntity]：设备级设置（外观三态、主色、徽章色、本机 UUID）
 *
 * 三条约定，改表之前先读：
 *  1. **软删**：业务表都有 `deleted_at`（null = 活着）。同步功能还没做，但列现在就留好，
 *     以后加同步时是墓碑信息，不必再改表。
 *  2. **派生数据不落表**：统计（城市数/省份数/次数/花销）与派生打卡都是现算的，
 *     数据量只有几百条，落表只会多三处一致性要维护。
 *  3. **时间一律存 UTC 毫秒**（Long）。展示时按记录时的时区偏移折算，见 logic/Timeline.kt。
 */

/** 行程状态。2.0 首发只有「已保存」；草稿与记录中留给 2.1 的自动记录。 */
object TripState {
    const val DRAFT = 0
    const val RECORDING = 1
    const val SAVED = 2
}

/** 打卡来源：派生（由行程的到访事件生成）还是用户手动记的。 */
object CheckinSource {
    const val DERIVED = 0
    const val MANUAL = 1
}

/**
 * 打卡日期的精度：取值定义在 `logic/DatePrecision.kt`（统计与表单都要用同一组值，
 * 而 data 依赖 logic、logic 不依赖 data，放 logic 两边都能引）。
 * 这一列存的就是那组 int，不要在这里再定义第二套字面值。
 */

/** 同步状态。2.0 全是 [LOCAL_ONLY]，列先留着。 */
object SyncState {
    const val LOCAL_ONLY = 0
    const val PENDING = 1
    const val SYNCED = 2
}

/**
 * 行程。
 *
 * [timeline] 是 logic/Timeline 的 JSON；[startTime] / [endTime] / [totalCostCents] 是从它推导出来
 * **冗余存**的：列表按时间排序、主页算花销，如果每次都要解析 JSON 就白花了这份存储。
 * 代价是改时间线时必须同步更新这三列 —— 写入只走 data/db/TripDao 的 `upsert`，别的地方别直接拼 SQL。
 *
 * [uuid] 客户端生成：给以后的分享/同步当稳定标识（自增 id 换设备就变了）。
 * [seqNo] 是界面上那个 `#编号`，**行程与独立打卡各自一套序列**，不复用、删了留空洞
 * （见 2.0 工程口径文档 口径 18）。
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "seq_no") val seqNo: Int,
    val title: String,
    val note: String? = null,
    val timeline: String,
    @ColumnInfo(name = "start_time") val startTime: Long,
    @ColumnInfo(name = "end_time") val endTime: Long,
    @ColumnInfo(name = "total_cost_cents") val totalCostCents: Int = 0,
    @ColumnInfo(name = "trip_state") val tripState: Int = TripState.SAVED,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.LOCAL_ONLY,
)

/**
 * 打卡。
 *
 * 两张来源共用一张表，靠 [source] 区分：派生打卡由行程的到访事件生成、只读（菜单里只有分享），
 * 手动打卡可增删改。**两者一起**才是成就与统计的数据源（口径：衍生物不另算一套）。
 *
 * [cityName] 必须存中文名而不只是 adcode：成就定义是按中文城市名比对的（54 条里大量「去过 X」）。
 * [tripUuid] 只有派生打卡有值，手动打卡为 null。
 *
 * 唯一索引 `(trip_uuid, city_name, source)` 是**派生打卡重算幂等**的依据：同一条行程的同一座城市
 * 只允许一条派生打卡。注意 SQLite 里 NULL 互不相等，所以手动打卡（trip_uuid 为 null）不会撞这个索引 ——
 * 同一座城市可以有任意多条手动打卡，那正是想要的。
 *
 * 也因此，重算派生打卡时必须**物理删**旧行（见 [CheckinDao.deleteDerivedForTrip]），
 * 不能只打 deleted_at：软删的行还占着唯一索引，再插就冲突。
 */
@Entity(
    tableName = "city_checkins",
    indices = [
        Index(value = ["trip_uuid", "city_name", "source"], unique = true),
        Index(value = ["city_name"]),
        Index(value = ["checkin_at"]),
    ],
)
data class CheckinEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "city_name") val cityName: String,
    @ColumnInfo(name = "city_code") val cityCode: Int? = null,
    val province: String,
    val source: Int = CheckinSource.MANUAL,
    @ColumnInfo(name = "trip_uuid") val tripUuid: String? = null,
    @ColumnInfo(name = "checkin_at") val checkinAt: Long,
    @ColumnInfo(name = "date_precision") val datePrecision: Int = DatePrecision.DAY,
    val note: String? = null,
    /** 同行人 code（[top.qxwkstudio.travel.logic.Companions.code]）；null = 没填。 */
    val companions: String? = null,
    /** 城市评分 1..5；null = 没评。 */
    val rating: Int? = null,
    /** 本地照片路径；null = 没配图。 */
    @ColumnInfo(name = "photo_path") val photoPath: String? = null,
    @ColumnInfo(name = "seq_no") val seqNo: Int,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.LOCAL_ONLY,
)

/**
 * 成就状态。**为什么必须落表**：达成与否能随时重算，但「解锁日期」重算不出来 ——
 * 不存的话，重装或清库之后所有解锁日期都会变成「现在」，成就页那行日期就没意义了。
 *
 * [code] 是成就的稳定标识（1.x 在后端 `achievements.js` 里就有这个字段），界面上的名称可以改，
 * code 不能改 —— 改了等于把所有人的这条成就状态清零。
 */
@Entity(tableName = "achievement_states")
data class AchievementStateEntity(
    @PrimaryKey val code: String,
    @ColumnInfo(name = "unlocked_at") val unlockedAt: Long? = null,
    val progress: Int = 0,
    val target: Int = 1,
)

/**
 * 设备级键值设置（外观三态、主色、徽章色、本机 UUID）。
 *
 * **与「账号数据」无关**：2.0 没有账号，但这条边界仍然有用 —— 以后要是加「导出/清空数据」，
 * 这类偏好同 1.x 的 `Store.clearSession` 一样不该被顺手清掉（口径见 1.x 的 Store 注释）。
 */
@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
