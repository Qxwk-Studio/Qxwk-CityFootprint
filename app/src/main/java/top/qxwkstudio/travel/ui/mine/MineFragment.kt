package top.qxwkstudio.travel.ui.mine

import top.qxwkstudio.travel.ui.common.*

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.qxwkstudio.travel.BuildConfig
import top.qxwkstudio.travel.CityFootprintApp
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Appearance
import top.qxwkstudio.travel.data.PhotoJanitor
import top.qxwkstudio.travel.data.DataExport
import top.qxwkstudio.travel.data.Prefs
import top.qxwkstudio.travel.data.SettingsRepo
import top.qxwkstudio.travel.data.Update
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.logic.Timeline
import top.qxwkstudio.travel.databinding.FragmentMineBinding
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「我的」页：本机资料 + 个性化 / 软件设置 / 关于软件三张卡片。
 *
 * 没有账号体系：UUID 本机生成后固定，昵称与徽章色可在「个性化」里改，头像不是相册图 ——
 * 统一显示「主题色实心圆 + 白色人像」，圆心随徽章色（这也就是用户在别处看到的「我的颜色」）。
 * 这一页不发任何身份请求；检查更新是用户点一下才发的唯一请求。
 *
 * 外观偏好仍走 1.x 的 [Store]（SharedPreferences）：`CityFootprintApp` 启动时要**同步**读它，
 * 而数据库是异步的；把它迁进 `app_settings` 要连启动流程一起改，留给 2.1。
 */
class MineFragment : Fragment() {

    private var _binding: FragmentMineBinding? = null
    private val binding get() = requireNotNull(_binding) { "binding 在视图生命周期外被访问" }

    /** 徽章色预设（口径：一组预设，不做取色器）。名字只给弹窗用，值进库。 */
    private val badgeOptions = listOf(
        R.string.color_red to "#DC2626",
        R.string.color_orange to "#F59E0B",
        R.string.color_green to "#34A853",
        R.string.color_teal to "#0891B2",
        R.string.color_blue to "#2563EB",
        R.string.color_purple to "#7C3AED",
        R.string.color_pink to "#DB2777",
        R.string.color_gray to "#64748B",
    )

    /** 当前徽章色（用于弹窗选中项与头像圆底）。 */
    private var currentBadge: String = SettingsRepo.DEFAULT_BADGE

    /** 检查更新 / 清理照片这类耗时操作在跑时，禁用入口防重复。 */
    private var working = false
    private var exporting = false

    private val exportPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) openExportConfirm() else requireContext().notify(R.string.export_permission)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        val showSync = top.qxwkstudio.travel.data.Prefs.networkAllowed &&
            top.qxwkstudio.travel.data.Prefs.syncEnabled
        _binding?.rowSync?.visibility = if (showSync) View.VISIBLE else View.GONE
        _binding?.dividerSync?.visibility = if (showSync) View.VISIBLE else View.GONE
        _binding?.let { load() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rowName.setOnClickListener { chooseName() }
        binding.rowAppearance.setOnClickListener { chooseAppearance() }
        binding.rowTheme.setOnClickListener { chooseThemeColor() }
        binding.rowLanguage.setOnClickListener { chooseLanguage() }
        binding.rowBadge.setOnClickListener { chooseBadgeColor() }
        binding.rowOpenSource.setOnClickListener { showOpenSource() }
        binding.rowCheckUpdate.setOnClickListener { checkUpdate() }
        binding.rowTerms.setOnClickListener { openLegal("https://travel.qxwkstudio.top/legal/terms") }
        binding.rowDisclaimer.setOnClickListener { openLegal("https://travel.qxwkstudio.top/legal/disclaimer") }
        binding.rowPermissions.setOnClickListener { startActivity(PermissionsActivity.intent(requireContext())) }
        binding.rowStorage.setOnClickListener { startActivity(StorageActivity.intent(requireContext())) }
        binding.rowExportData.setOnClickListener { startExportFlow() }
        binding.rowSync.setOnClickListener { startActivity(SyncActivity.intent(requireContext())) }
        binding.root.fadeSlideIn()
        binding.textVersion.text = BuildConfig.VERSION_NAME
        load()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) { _binding?.root?.fadeSlideIn(); load(); refreshLocal() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.runIo({
            val db = AppDatabase.get(requireContext())
            val synced = Prefs.syncToken.isNotBlank()
            Profile(
                uuid = if (synced) Prefs.syncUuid else SettingsRepo.uuid(db),
                nickname = if (synced) Prefs.syncNickname else SettingsRepo.nickname(db),
                badge = if (synced) Prefs.syncColor.ifBlank { SettingsRepo.badgeColor(db) } else SettingsRepo.badgeColor(db),
                avatar = if (synced) Prefs.syncAvatar else null,
                synced = synced,
            )
        }) { result ->
            val profile = result.getOrNull() ?: return@runIo
            bindProfile(profile)
        }
    }

    private data class Profile(
        val uuid: String,
        val nickname: String?,
        val badge: String,
        val avatar: String?,
        val synced: Boolean,
    )

    private fun bindProfile(profile: Profile) {
        val b = binding
        val displayName = localizedNickname(profile.nickname)
        b.textNickname.text = displayName
        b.textNameValue.text = displayName
        b.textUuid.text = getString(R.string.mine_uuid, profile.uuid)

        // 登录同步后，昵称、头像、徽章色都来自通行证；未登录时仍是本地资料。
        b.rowName.isEnabled = !profile.synced
        b.rowBadge.isEnabled = !profile.synced
        b.rowName.alpha = if (profile.synced) 0.55f else 1f
        b.rowBadge.alpha = if (profile.synced) 0.55f else 1f
        b.iconAvatar.background = circle(profile.badge)
        b.colorDot.background = circle(profile.badge)
        val themeColor = if (Palette.selected().key == "system") {
            // 跟随系统时没有固定的色值，用当前动态取色结果画圆点
            String.format("#%06X", 0xFFFFFF and Palette.accent(requireContext()))
        } else {
            Palette.selected().color
        }
        b.colorDotTheme.background = circle(themeColor)
        b.textLanguage.text = when (Prefs.languageTag) {
            "zh-CN" -> getString(R.string.language_zh_cn)
            "en" -> getString(R.string.language_en)
            else -> getString(R.string.language_follow_system)
        }
        currentBadge = profile.badge
        if (profile.synced) loadSyncedAvatar(profile.avatar) else resetAvatarIcon(profile.badge)
        refreshLocal()
    }

    private fun loadSyncedAvatar(url: String?) {
        val safeUrl = url?.takeIf { it.startsWith("https://") && Prefs.networkAllowed }
        if (safeUrl == null) {
            resetAvatarIcon(currentBadge)
            return
        }
        viewLifecycleOwner.lifecycleScope.runIo({
            val connection = (URL(safeUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
            }
            try {
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                connection.disconnect()
            }
        }) { result ->
            val bitmap = result.getOrNull()
            if (bitmap == null) {
                resetAvatarIcon(currentBadge)
            } else {
                _binding?.iconAvatar?.apply {
                    setPadding(0, 0, 0, 0)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    imageTintList = null
                    clipToOutline = true
                    setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun resetAvatarIcon(color: String) {
        _binding?.iconAvatar?.apply {
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.on_accent))
            setImageResource(R.drawable.ic_person)
            background = circle(color)
        }
    }

    /** 昵称预设按当前语言显示；库里始终存 canonical 中文。 */
    private fun localizedNickname(name: String?): String {
        if (name.isNullOrBlank()) return getString(R.string.mine_nickname_default)
        val canonical = resources.getStringArray(R.array.mine_name_presets_canonical)
        val localized = resources.getStringArray(R.array.mine_name_presets)
        val index = canonical.indexOf(name).takeIf { it >= 0 } ?: localized.indexOf(name)
        if (index < 0) return name
        return if (resources.configuration.locales[0].language == "en") localized[index] else canonical[index]
    }

    /** 外观是同步读 SharedPreferences 的，单独一个方法，切回来时不用再查库。 */
    private fun refreshLocal() {
        if (_binding == null) return
        binding.textAppearance.setText(appearanceLabel(Prefs.appearance))
    }

    private fun appearanceLabel(appearance: Appearance): Int = when (appearance) {
        Appearance.SYSTEM -> R.string.mine_appearance_follow_system
        Appearance.LIGHT -> R.string.mine_appearance_light
        Appearance.DARK -> R.string.mine_appearance_dark
    }

    /**
     * 清理无引用照片：收集所有活着的打卡与行程时间线里引用的路径，删掉目录里剩下的文件。
     *
     * 删除前问一句：这是不可撤销操作，而且用户可能只是想腾空间、不想动任何在用的图。
     */
    /**
     * 开源项目与许可：地图页原来压在左下角的 MapLibre / © OpenStreetMap 署名，
     * 以及底图（Protomaps）与图标（Material Icons）的来源，都收在这一页。
     * 这些是许可证要求的署名，别删。
     */
    private fun showOpenSource() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mine_open_source)
            .setMessage(R.string.mine_open_source_body)
            .setPositiveButton(R.string.common_confirm, null)
            .show()
    }

    private fun chooseBadgeColor() {
        if (Prefs.syncToken.isNotBlank()) { requireContext().notify(R.string.mine_sync_locked); return }
        val current = badgeOptions.indexOfFirst { it.second == currentBadge }
        val names = badgeOptions.map { getString(it.first) }.toTypedArray()
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

    /**
     * 主题色：只换「主色」这一档（按钮 / 开关 / 底栏选中态 / 代码里画的强调色），
     * 面与文字不动。选完要 recreate：主题叠层在 Activity 创建时就定了，不重建看不到效果。
     */
    private fun themeLabel(key: String): String = getString(
        when (key) {
            "rose" -> R.string.color_red
            "orange" -> R.string.color_orange
            "green" -> R.string.color_green
            "teal" -> R.string.color_teal
            "blue" -> R.string.color_blue
            "purple" -> R.string.color_purple
            else -> R.string.color_follow_system
        },
    )

    private fun chooseThemeColor() {
        val presets = Palette.presets
        val current = presets.indexOfFirst { it.key == Palette.selected().key }
        val labels = presets.map { themeLabel(it.key) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mine_theme_color)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                Palette.save(requireContext(), presets[which].key)
                requireActivity().recreate()
            }
            .show()
    }

    private fun chooseLanguage() {
        val tags = arrayOf("", "zh-CN", "en")
        val labels = arrayOf(
            getString(R.string.language_follow_system),
            getString(R.string.language_zh_cn),
            getString(R.string.language_en),
        )
        val current = tags.indexOf(Prefs.languageTag).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mine_language)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                Prefs.saveLanguage(requireContext(), tags[which])
                if (tags[which].isBlank()) {
                    java.io.File(
                        requireContext().filesDir,
                        "androidx.appcompat.app.AppCompatDelegate.application_locales_record_file",
                    ).delete()
                }
                AppRestart.exitForLanguageChange(requireContext(), tags[which])
            }
            .show()
    }

    /** 名称：从预制选项里选一个，写入本机设置。 */
    private fun chooseName() {
        if (Prefs.syncToken.isNotBlank()) { requireContext().notify(R.string.mine_sync_locked); return }
        val canonical = resources.getStringArray(R.array.mine_name_presets_canonical)
        val presets = resources.getStringArray(R.array.mine_name_presets)
        val current = binding.textNameValue.text?.toString().orEmpty()
        val checked = presets.indexOfFirst { it == current }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mine_name)
            .setSingleChoiceItems(presets, checked) { dialog, which ->
                dialog.dismiss()
                viewLifecycleOwner.lifecycleScope.runIo({
                    SettingsRepo.setNickname(AppDatabase.get(requireContext()), canonical[which])
                }) { load() }
            }
            .show()
    }

    private fun chooseAppearance() {
        val options = Appearance.entries
        val labels = options.map { getString(appearanceLabel(it)) }.toTypedArray()
        val current = options.indexOf(Prefs.appearance)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mine_appearance_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                val picked = options[which]
                val previous = Prefs.appearance
                Prefs.saveAppearance(requireContext(), picked)
                refreshLocal()
                if (picked != previous) CityFootprintApp.applyAppearance(picked)
            }
            .show()
    }

    private fun startExportFlow() {
        if (exporting) return
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            exportPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        openExportConfirm()
    }

    private fun openExportConfirm() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_confirm_title)
            .setMessage(R.string.export_confirm_message)
            .setPositiveButton(R.string.common_confirm) { _, _ -> doExport() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun doExport() {
        if (exporting) return
        exporting = true
        _binding?.rowExportData?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.runIo({
            DataExport.export(requireContext(), AppDatabase.get(requireContext()))
        }) { result ->
            exporting = false
            _binding?.rowExportData?.isEnabled = true
            val data = result.getOrNull()
            if (data == null) {
                requireContext().notify(R.string.export_failed, long = true)
            } else {
                requireContext().notify(
                    getString(R.string.export_done, data.displayPath, data.trips, data.checkins, data.photos),
                    long = true,
                )
            }
        }
    }

    /**
     * 检查更新：读后端 `GET /api/manifest`，比 `version_code`（不是版本名）。
     * 2.0 只保留这一个网络请求。
     */
    private fun checkUpdate() {
        if (!Prefs.networkAllowed) {
            requireContext().notify(R.string.perm_network_off)
            return
        }
        if (working) return
        working = true
        binding.rowCheckUpdate.isEnabled = false
        viewLifecycleOwner.lifecycleScope.runIo({ Update.fetch() }) { result ->
            working = false
            binding.rowCheckUpdate.isEnabled = true
            val info = result.getOrNull()?.android
            if (info == null) {
                requireContext().notify(R.string.profile_update_failed)
                return@runIo
            }
            if (info.versionCode <= BuildConfig.VERSION_CODE) {
                requireContext().notify(R.string.profile_update_latest)
                return@runIo
            }
            val forced = info.importance >= 2
            val message = buildString {
                append(getString(R.string.profile_update_new_message, info.versionName))
                val notes = info.notes.filter { it.isNotBlank() }
                if (notes.isNotEmpty()) {
                    append("\n\n")
                    notes.forEach { append("• ").append(it).append("\n") }
                }
                if (forced) {
                    append("\n")
                    append(getString(R.string.profile_update_force_hint))
                }
            }
            val titleRes = when (info.importance.coerceIn(0, 2)) {
                0 -> R.string.profile_update_small_title
                1 -> R.string.profile_update_normal_title
                else -> R.string.profile_update_force_title
            }
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(titleRes))
                .setMessage(message)
                .setPositiveButton(R.string.profile_update_go) { _, _ ->
                    val url = info.downloadUrl
                    val safeUri = android.net.Uri.parse(url.ifBlank { "" })
                    if (url.isBlank() || safeUri.scheme !in setOf("https", "http")) {
                        requireContext().notify(R.string.profile_update_no_url, long = true)
                    } else {
                        runCatching {
                            startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    safeUri,
                                )
                            )
                        }.onFailure {
                            requireContext().notify(R.string.common_open_url_failed, long = true)
                        }
                    }
                }
            if (!forced) dialog.setNegativeButton(R.string.common_cancel, null)
            dialog.setCancelable(!forced)
            dialog.show()
        }
    }

    private fun openLegal(url: String) {
        runCatching {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure {
            requireContext().notify(R.string.common_open_url_failed, long = true)
        }
    }

    /** 圆形底色（头像与色点共用）。坏色值退回主色，别让一行脏数据把界面搞崩。 */
    private fun circle(color: String): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(
            runCatching { Color.parseColor(color) }
                .getOrDefault(ContextCompat.getColor(requireContext(), R.color.accent)),
        )
    }
}
