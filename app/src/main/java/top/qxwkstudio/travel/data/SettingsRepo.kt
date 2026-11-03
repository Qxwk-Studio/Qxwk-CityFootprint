package top.qxwkstudio.travel.data

import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.AppSettingEntity
import java.util.UUID

/**
 * 设备级设置的读写（`app_settings` 表）。
 *
 * 目前只有两样：本机 UUID 与昵称。外观偏好暂时还留在 1.x 的 [Store]（SharedPreferences）里 ——
 * 2.0 会把它搬进这张表，但启动时读它需要一个能同步读的入口，那一块要和 CityFootprintApp 一起改（P6）。
 *
 * **UUID 不是账号**：2.0 没有登录，它只是给「导出/导入」与将来的同步一个稳定标识，
 * 换手机/重装会重新生成。第一次进「我的」页时惰性创建。
 */
object SettingsRepo {

    private const val KEY_UUID = "uuid"
    private const val KEY_NICKNAME = "nickname"

    suspend fun uuid(db: AppDatabase): String {
        db.settings().get(KEY_UUID)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        db.settings().put(AppSettingEntity(KEY_UUID, generated))
        return generated
    }

    /** 昵称为空时返回 null，界面用默认文案（不要在代码里拼一个假的昵称进去）。 */
    suspend fun nickname(db: AppDatabase): String? =
        db.settings().get(KEY_NICKNAME)?.takeIf { it.isNotBlank() }

    suspend fun setNickname(db: AppDatabase, nickname: String) {
        db.settings().put(AppSettingEntity(KEY_NICKNAME, nickname))
    }
}
