package top.qxwkstudio.travel.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * 本地库。4 张表见 [TripEntity] 那一组文件。
 *
 * **单例**：Room 的实例本身是线程安全的（内部有连接池），重复 `databaseBuilder` 会开出多份连接池。
 * 所有调用方都走 [get]，别自己 new。
 *
 * **迁移**：2.3.5 起**不再销毁重建**。改表必须给 [VERSION] +1 并在 [MIGRATIONS] 补一条迁移；
 * 缺迁移时 Room 打开数据库会直接抛异常 —— 抛异常比静默清库安全：用户的行程与打卡不会因为
 * 一次忘记写迁移而消失。历史库（version 7 之前）同理，需要迁移就从 7 往上补。
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
        /** 改表时 +1，并在 [MIGRATIONS] 里补对应迁移。 */
        const val VERSION = 7

        /**
         * 迁移链。现在是空数组：还没有跨版本升级。
         * 加一版就是 `Migration(VERSION_OLD, VERSION_OLD + 1) { db -> db.execSQL(...) }`，
         * 同时把 [VERSION] +1、`schemas/` 会多一份新快照（要入库）。
         */
        private val MIGRATIONS = arrayOf<Migration>()

        private const val DB_NAME = "city_footprint.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    backUpLegacyDatabase(context)
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        DB_NAME,
                    )
                        .addMigrations(*MIGRATIONS)
                        .build()
                        .also { instance = it }
                }
            }

        /**
         * 打开前兜底：磁盘上的库版本低于当前 [VERSION] 且没有对应迁移时，
         * 先把库文件复制一份 `.v<旧版本>.bak`，再让它重建。
         *
         * 为什么不是直接 `fallbackToDestructiveMigration`：那会**静默**清空用户数据；
         * 这里至少留一份可恢复的副本，而且只在确实来不及迁移时发生。
         */
        private fun backUpLegacyDatabase(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return
            val version = runCatching {
                android.database.sqlite.SQLiteDatabase
                    .openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                    .use { it.version }
            }.getOrNull() ?: return
            if (version >= VERSION) return
            val backup = java.io.File(dbFile.parentFile, "${dbFile.name}.v$version.bak")
            val copied = runCatching { dbFile.copyTo(backup, overwrite = true) }.isSuccess
            // 备份成功才动原库：失败就保持现状（宁可打不开，也不要在没有副本的情况下删数据）
            if (copied) runCatching { dbFile.delete() }
        }
    }
}
