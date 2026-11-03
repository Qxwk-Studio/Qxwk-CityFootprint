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
    private const val KEY_AVATAR_PATH = "avatar_path"
    private const val KEY_BADGE_COLOR = "badge_color"

    /** 徽章色默认值：与主题主色一致（口径 16 说的「预设色板」，默认就是主色）。 */
    const val DEFAULT_BADGE = "#2563EB"

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

    // ── 头像与徽章色（2.0 的「个性化」）──

    /** 头像文件路径（应用私有目录）。空或 null 表示没设过，界面退回「昵称首字 + 徽章色」。 */
    suspend fun avatarPath(db: AppDatabase): String? =
        db.settings().get(KEY_AVATAR_PATH)?.takeIf { it.isNotBlank() }

    suspend fun setAvatarPath(db: AppDatabase, path: String?) {
        if (path.isNullOrBlank()) db.settings().remove(KEY_AVATAR_PATH)
        else db.settings().put(AppSettingEntity(KEY_AVATAR_PATH, path))
    }

    /**
     * 徽章色（头像底 / 我的页色点 / 地图定位点），存 #RRGGBB。
     * 没设过时返回 [DEFAULT_BADGE]，界面不要自己猜一个颜色。
     */
    suspend fun badgeColor(db: AppDatabase): String =
        db.settings().get(KEY_BADGE_COLOR)?.takeIf { it.isNotBlank() } ?: DEFAULT_BADGE

    suspend fun setBadgeColor(db: AppDatabase, color: String) {
        db.settings().put(AppSettingEntity(KEY_BADGE_COLOR, color))
    }
}
