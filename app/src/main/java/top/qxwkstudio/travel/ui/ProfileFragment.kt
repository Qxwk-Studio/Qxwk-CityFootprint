package top.qxwkstudio.travel.ui

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import top.qxwkstudio.travel.BuildConfig
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Auth
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.databinding.FragmentProfileBinding

/**
 * 「我的」：本机登录身份的展示与退出登录。
 * 资料（昵称/主题色）来自本机 SharedPreferences，由 MainActivity 启动时的 /api/me 顺手刷新，
 * 所以这一页不发请求、切回来重读一次本机状态即可（见 onHiddenChanged）。
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = Store(requireContext())
        binding.btnLogout.setOnClickListener { confirmLogout() }
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun refresh() {
        val b = _binding ?: return
        val nickname = store.nickname
        b.textNickname.text = nickname.ifBlank { "?" }
        // 头像用昵称首字（不为一个头像引图片加载库，见布局里的注释）
        b.textAvatar.text = nickname.firstOrNull()?.toString() ?: "?"
        b.textAvatar.background = avatarDrawable(store.color)
        b.textVersion.text = getString(R.string.profile_version, BuildConfig.VERSION_NAME)
    }

    /** 通行证给的主题色是 "#RRGGBB"；给空或给了怪值就退回强调色，不让 parseColor 的异常崩在这一句。 */
    private fun avatarDrawable(color: String): Drawable {
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.OVAL
        shape.setColor(
            runCatching { Color.parseColor(color) }
                .getOrDefault(ContextCompat.getColor(requireContext(), R.color.accent))
        )
        return shape
    }

    private fun confirmLogout() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_logout)
            .setMessage(R.string.profile_logout_confirm)
            .setPositiveButton(R.string.profile_logout) { _, _ -> logout() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun logout() {
        val token = store.token
        // applicationContext：退出这件事不依赖这一屏还活着
        val appContext = requireContext().applicationContext
        Async.run({
            // 让通行证撤销这一个会话（多端登录时不影响其它设备）。
            // 失败也不处理 —— 本地 token 必须清掉，否则用户以为退出了、下次打开还在里面
            if (token != null) Auth.logout(token)
        }) {
            store.clearSession()
            appContext.gotoLogin()
            activity?.finish()
        }
    }
}