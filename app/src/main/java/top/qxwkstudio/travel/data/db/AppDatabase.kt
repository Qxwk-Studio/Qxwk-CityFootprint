package top.qxwkstudio.travel.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地库。4 张表见 [TripEntity] 那一组文件。
 *
 * **单例**：Room 的实例本身是线程安全的（内部有连接池），重复 `databaseBuilder` 会开出多份连接池、
 * 并在迁移时互相打架。所有调用方都走 [get]，别自己 new。
 *
 * **schema 导出开着**（见 build.gradle.kts 的 `ksp { arg("room.schemaLocation", …) }`）：
 * `app/schemas/` 下每个版本一份 DDL 快照，改表时必须一起提交。它是「这一版到底动了哪些列」的唯一凭据，
 * 也是以后写迁移测试的输入 —— 没有它，迁移只能靠人回忆。
 *
 * [VERSION] 从 1 开始。2.0 首发之前随便改（还没人有存量数据）；**一旦发过版，改表就必须带迁移**，
 * 否则用户升级后 `IllegalStateException: Room cannot verify the data integrity` 直接崩。
 */
@Database(
    entities = [
        TripEntity::class,
        CheckinEntity::class,
        AchievementStateEntity::class,
        AppSettingEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trips(): TripDao
    abstract fun checkins(): CheckinDao
    abstract fun achievements(): AchievementDao
    abstract fun settings(): SettingsDao

    companion object {
        /** 改表时 +1 并补迁移；发版前可以随意改（见类注释）。 */
        const val VERSION = 2

        private const val DB_NAME = "city_footprint.db"

        /**
         * 1 → 2：打卡增加「同行人 / 评分 / 照片路径」三列（都可空）。
         *
         * 纯新增列，不重建表：`ALTER TABLE ... ADD COLUMN` 对存量行安全（默认 NULL），
         * 也不会碰唯一索引。改表必须带迁移 —— 没有迁移的话，升级用户一开库就
         * `Room cannot verify the data integrity` 崩溃。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE city_checkins ADD COLUMN companions TEXT")
                db.execSQL("ALTER TABLE city_checkins ADD COLUMN rating INTEGER")
                db.execSQL("ALTER TABLE city_checkins ADD COLUMN photo_path TEXT")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
