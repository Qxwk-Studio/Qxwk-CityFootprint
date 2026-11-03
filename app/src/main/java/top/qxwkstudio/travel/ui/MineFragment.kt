package top.qxwkstudio.travel.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * 「我的」页（2.0）：本机资料 + 外观 / 检查更新 / 版本。
 *
 * 2.0 没有账号，**这一页不登录、不发身份请求**：昵称与 UUID 都在本地库（[SettingsRepo]），
 * 头像暂时是「昵称首字 + 主色圆」（相册头像在 P6 做）。
 *
 * 外观偏好这一轮仍走 1.x 的 [Store]（SharedPreferences）：`CityFootprintApp` 启动时要**同步**读它，
 * 而数据库是异步的；把它迁进 `app_settings` 要连启动流程一起改，留给 P6（见 SettingsRepo 的类注释）。
 */
class MineFragment : Fragment() {

    private var _binding: FragmentMineBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = Store(requireContext())
        binding.rowAppearance.setOnClickListener { chooseAppearance() }
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
            SettingsRepo.uuid(db) to SettingsRepo.nickname(db)
        }) { result ->
            val (uuid, nickname) = result.getOrNull() ?: return@runIo
            binding.textNickname.text = nickname ?: getString(R.string.mine_nickname_default)
            binding.textUuid.text = getString(R.string.mine_uuid, uuid)
            binding.textAvatar.text = (nickname ?: getString(R.string.mine_nickname_default)).firstOrNull()?.toString() ?: "?"
            refreshLocal()
        }
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
     * 外观三选。用 `setItems` 的系统列表而不是自绘行：这一页只是设置入口，
     * 2.0.md 也没给弹窗样式（1.x 那份自绘行是照网页做的，2.0 重做时再统一）。
     *
     * 选完先存再切：切外观会重建所有 Activity（见 CityFootprintApp.applyAppearance）。
     */
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
     * 检查更新：读后端 `GET /api/manifest`，比 `version_code`（不是版本名，理由见 1.x 的 ReleaseInfo）。
     *
     * 2.0 只保留这一个网络请求（见 2.0-development.md 口径 2）；拉不到就 Toast 一句，
     * 这不是内容主体，不该挡着用户用 App。
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
}
