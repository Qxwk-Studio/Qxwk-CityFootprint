package top.qxwkstudio.travel.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地库。4 张表见 [TripEntity] 那一组文件。
 *
 * **单例**：Room 的实例本身是线程安全的（内部有连接池），重复 `databaseBuilder` 会开出多份连接池。
 * 所有调用方都走 [get]，别自己 new。
 *
 * **开发期策略**：当前还在 2.x 迭代中，表结构可以直接改、不写兼容迁移。
 * 旧版本数据库在打开时会被**销毁重建**（[fallbackToDestructiveMigration]），
 * 不再维护 1→2→…→5 的迁移链。正式版发布前需要恢复「每版一份迁移」的做法。
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
        /** 改表时 +1（开发期无需写迁移）。 */
        const val VERSION = 5

        private const val DB_NAME = "city_footprint.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    // 开发期不做兼容：结构对不上就重建，避免维护迁移链与迁移测试
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
