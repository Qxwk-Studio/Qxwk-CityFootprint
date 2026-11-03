package top.qxwkstudio.travel.ui

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
import top.qxwkstudio.travel.data.SettingsRepo
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.Update
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.databinding.FragmentMineBinding

/**
 * 「我的」页（2.0）：本机资料 + 两张设置卡（外观与颜色 / 关于）。
 *
 * 2.0 没有账号，**资料不可编辑**：昵称与 UUID 由本机生成后固定，头像也不是相册图 ——
 * 统一显示「主题色实心圆 + 白色人像」，圆心随徽章色（这也就是用户在别处看到的「我的颜色」）。
 * 这一页不发任何身份请求；检查更新是用户点一下才发的唯一请求。
 *
 * 外观偏好仍走 1.x 的 [Store]（SharedPreferences）：`CityFootprintApp` 启动时要**同步**读它，
 * 而数据库是异步的；把它迁进 `app_settings` 要连启动流程一起改，留给 2.1。
 */
class MineFragment : Fragment() {

    private var _binding: FragmentMineBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store

    /** 徽章色预设（口径：一组预设，不做取色器）。名字只给弹窗用，值进库。 */
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

    /** 当前徽章色（用于弹窗选中项与头像圆底）。 */
    private var currentBadge: String = SettingsRepo.DEFAULT_BADGE

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = Store(requireContext())
        binding.rowAppearance.setOnClickListener { chooseAppearance() }
        binding.rowTheme.setOnClickListener { chooseThemeColor() }
        binding.rowBadge.setOnClickListener { chooseBadgeColor() }
        binding.rowOpenSource.setOnClickListener { showOpenSource() }
        binding.rowCheckUpdate.setOnClickListener { checkUpdate() }
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
        val badge: String,
    )

    private fun bindProfile(profile: Profile) {
        val b = binding
        b.textNickname.text = profile.nickname ?: getString(R.string.mine_nickname_default)
        b.textUuid.text = getString(R.string.mine_uuid, profile.uuid)

        // 头像：主题色圆底 + 白色人像（不提供修改入口）
        b.iconAvatar.background = circle(profile.badge)
        b.colorDot.background = circle(profile.badge)
        b.colorDotTheme.background = circle(Palette.selected(requireContext()).color)
        currentBadge = profile.badge
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
     * 2.0 只保留这一个网络请求。
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

    /** 圆形底色（头像与色点共用）。坏色值退回主色，别让一行脏数据把界面搞崩。 */
    private fun circle(color: String): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(
            runCatching { Color.parseColor(color) }
                .getOrDefault(ContextCompat.getColor(requireContext(), R.color.accent)),
        )
    }
}
