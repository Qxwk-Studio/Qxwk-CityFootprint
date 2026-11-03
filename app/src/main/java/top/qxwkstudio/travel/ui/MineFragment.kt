package top.qxwkstudio.travel.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import top.qxwkstudio.travel.BuildConfig
import top.qxwkstudio.travel.CityFootprintApp
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Appearance
import top.qxwkstudio.travel.data.SettingsRepo
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.Update
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.databinding.FragmentMineBinding
import java.io.File

/**
 * 「我的」页（2.0）：本机资料 + 外观 / 徽章色 / 检查更新 / 版本。
 *
 * 2.0 没有账号，**这一页不登录、不发身份请求**：
 *  - 昵称、UUID、头像路径、徽章色都在本地库（[SettingsRepo]）；
 *  - 头像走系统照片选择器（[ActivityResultContracts.PickVisualMedia]，Android 13+ 与低版本都不需要
 *    读存储权限），拷一份缩到 512px 存进 filesDir，路径落库 —— 原图不留在应用里；
 *  - 没设头像时退回「昵称首字 + 徽章色」的圆，与 1.x 的兜底同一套观感。
 *
 * 外观偏好这一轮仍走 1.x 的 [Store]（SharedPreferences）：`CityFootprintApp` 启动时要**同步**读它，
 * 而数据库是异步的；把它迁进 `app_settings` 要连启动流程一起改，留给「主色可自定义」那一刀（P6 后半）。
 */
class MineFragment : Fragment() {

    private var _binding: FragmentMineBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store

    /** 徽章色预设（口径 16：一组预设，不做取色器）。名字只给弹窗用，值进库。 */
    private val badgeOptions = listOf(
        "蓝" to "#2563EB",
        "绿" to "#34A853",
        "橙" to "#F59E0B",
        "紫" to "#7C3AED",
        "红" to "#DC2626",
        "青" to "#0891B2",
        "粉" to "#DB2777",
        "灰" to "#64748B",
    )

    /** 正在显示的头像路径；用来避免切 tab 回来重复解码同一张图。 */
    private var shownAvatarPath: String? = null

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.runIo({ saveAvatar(uri) }) { result ->
            val path = result.getOrNull()
            if (path == null) {
                requireContext().notify(R.string.mine_avatar_failed, long = true)
                return@runIo
            }
            viewLifecycleOwner.lifecycleScope.launch { persistAvatar(path) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = Store(requireContext())
        binding.rowAppearance.setOnClickListener { chooseAppearance() }
        binding.rowBadge.setOnClickListener { chooseBadgeColor() }
        binding.rowTheme.setOnClickListener { chooseThemeColor() }
        binding.rowCheckUpdate.setOnClickListener { checkUpdate() }
        binding.rowProfile.setOnClickListener { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        binding.textVersion.text = BuildConfig.VERSION_NAME
        load()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshLocal()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.runIo({
            val db = AppDatabase.get(requireContext())
            Profile(
                uuid = SettingsRepo.uuid(db),
                nickname = SettingsRepo.nickname(db),
                avatarPath = SettingsRepo.avatarPath(db),
                badge = SettingsRepo.badgeColor(db),
            )
        }) { result ->
            val profile = result.getOrNull() ?: return@runIo
            bindProfile(profile)
        }
    }

    private data class Profile(
        val uuid: String,
        val nickname: String?,
        val avatarPath: String?,
        val badge: String,
    )

    private fun bindProfile(profile: Profile) {
        val b = binding
        val nickname = profile.nickname ?: getString(R.string.mine_nickname_default)
        b.textNickname.text = nickname
        b.textUuid.text = getString(R.string.mine_uuid, profile.uuid)
        b.colorDot.background = circle(profile.badge)
        currentBadge = profile.badge
        b.colorDotTheme.background = circle(Palette.selected(requireContext()).color)

        // 首字那一版每次都先画好：它是兜底，也是图片加载前的占位
        b.textAvatar.text = nickname.firstOrNull()?.toString() ?: "?"
        b.textAvatar.background = circle(profile.badge)

        val path = profile.avatarPath
        val bitmap = if (path != null && path != shownAvatarPath && File(path).exists()) {
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        } else {
            null
        }
        if (bitmap != null) {
            b.imageAvatar.setImageBitmap(bitmap)
            shownAvatarPath = path
        }
        // 只有真的拿到图才切到图片形态；解码失败就继续显示首字，别留一个空框
        val hasImage = bitmap != null || (path != null && path == shownAvatarPath)
        b.imageAvatar.visibility = if (hasImage) View.VISIBLE else View.GONE
        b.textAvatar.visibility = if (hasImage) View.GONE else View.VISIBLE
        if (!hasImage) shownAvatarPath = null
        refreshLocal()
    }

    /** 外观是同步读 SharedPreferences 的，单独一个方法，切回来时不用再查库。 */
    private fun refreshLocal() {
        if (_binding == null) return
        binding.textAppearance.setText(appearanceLabel(store.appearance))
    }

    private fun appearanceLabel(appearance: Appearance): Int = when (appearance) {
        Appearance.SYSTEM -> R.string.mine_appearance_follow_system
        Appearance.LIGHT -> R.string.mine_appearance_light
        Appearance.DARK -> R.string.mine_appearance_dark
    }

    /**
     * 头像落盘：解码 → 缩到 512px 以内 → 存 JPEG。
     *
     * 不直接存原图：手机相册里的照片动辄几 MB、几千万像素，用来当 48dp 的头像既费内存也费磁盘；
     * 只留一份够用的。
     */
    private fun saveAvatar(uri: Uri): String? {
        val app = requireContext().applicationContext
        val bitmap = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > AVATAR_MAX_PX) {
            val ratio = AVATAR_MAX_PX.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val file = File(app.filesDir, AVATAR_FILE)
        file.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return file.absolutePath
    }

    private suspend fun persistAvatar(path: String) {
        val db = AppDatabase.get(requireContext())
        SettingsRepo.setAvatarPath(db, path)
        requireContext().notify(R.string.mine_avatar_done)
        load()
    }

    /**
     * 主题色：只换「主色」这一档（按钮 / 开关 / 底栏选中态 / 代码里画的强调色），
     * 面与文字不动。选完要 recreate：主题叠层在 Activity 创建时就定了，不重建看不到效果。
     */
    private fun chooseThemeColor() {
        val presets = Palette.presets
        val current = presets.indexOfFirst { it.key == Palette.selected(requireContext()).key }
        val labels = presets.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mine_theme_color)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                Palette.save(requireContext(), presets[which].key)
                requireActivity().recreate()
            }
            .show()
    }

    private fun chooseBadgeColor() {
        val current = badgeOptions.indexOfFirst { it.second == currentBadge }
        val names = badgeOptions.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mine_badge_color)
            .setSingleChoiceItems(names, current) { dialog, which ->
                dialog.dismiss()
                val color = badgeOptions[which].second
                viewLifecycleOwner.lifecycleScope.runIo({
                    SettingsRepo.setBadgeColor(AppDatabase.get(requireContext()), color)
                }) { load() }
            }
            .show()
    }

    /** 当前徽章色（用于弹窗选中项）。从最近一次绑定的色点反推，省一份状态。 */
    private var currentBadge: String = SettingsRepo.DEFAULT_BADGE

    private fun chooseAppearance() {
        val options = Appearance.entries
        val labels = options.map { getString(appearanceLabel(it)) }.toTypedArray()
        val current = options.indexOf(store.appearance)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mine_appearance_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                val picked = options[which]
                store.saveAppearance(picked)
                CityFootprintApp.applyAppearance(picked)
            }
            .show()
    }

    /**
     * 检查更新：读后端 `GET /api/manifest`，比 `version_code`（不是版本名）。
     * 2.0 只保留这一个网络请求（见 2.0 工程口径文档 口径 2）。
     */
    private fun checkUpdate() {
        viewLifecycleOwner.lifecycleScope.runIo({ Update.fetch() }) { result ->
            val info = result.getOrNull()?.android
            if (info == null) {
                requireContext().notify(R.string.profile_update_failed)
                return@runIo
            }
            if (info.versionCode <= BuildConfig.VERSION_CODE) {
                requireContext().notify(R.string.profile_update_latest)
                return@runIo
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.profile_update_new_title))
                .setMessage(getString(R.string.profile_update_new_message, info.versionName))
                .setPositiveButton(R.string.profile_update_go) { _, _ ->
                    val url = info.downloadUrl
                    if (url.isBlank()) {
                        requireContext().notify(R.string.profile_update_no_url, long = true)
                    } else {
                        runCatching {
                            startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url),
                                )
                            )
                        }.onFailure {
                            requireContext().notify(R.string.common_open_url_failed, long = true)
                        }
                    }
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    /** 圆形底色（头像首字版与徽章色点共用）。坏色值退回主色，别让一行脏数据把界面搞崩。 */
    private fun circle(color: String): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(runCatching { Color.parseColor(color) }.getOrDefault(ContextCompat.getColor(requireContext(), R.color.accent)))
    }

    companion object {
        /** 头像最长边上限（px）。48dp 的头像用这个尺寸绰绰有余。 */
        private const val AVATAR_MAX_PX = 512

        /** 头像文件名固定：换头像直接覆盖，不攒垃圾文件。 */
        private const val AVATAR_FILE = "avatar.jpg"
    }
}
