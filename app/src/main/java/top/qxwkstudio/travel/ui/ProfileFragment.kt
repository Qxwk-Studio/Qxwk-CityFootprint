package top.qxwkstudio.travel.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.launch
import top.qxwkstudio.travel.Api
import top.qxwkstudio.travel.BuildConfig
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Auth
import top.qxwkstudio.travel.data.GeoCache
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.databinding.FragmentProfileBinding
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「我的」：本机登录身份的展示（头像 / 昵称 / UID / 管理员徽章 / 专属地图颜色）、通行证入口、
 * 清除本机缓存与退出登录（对齐网页 docs/account.html 的个人中心）。
 * 资料（昵称/主题色/头像/uid/is_admin）来自本机 SharedPreferences，由 MainActivity 启动时的 /api/me
 * 顺手刷新，所以这一页**只差头像那一次图片下载**（那是图片本身，不是接口），
 * 其余切回来重读一次本机状态即可（见 onHiddenChanged）。
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store

    /** 已经**尝试过**下载的头像地址。同一张图不重复拉（切 tab 会反复走 [refresh]）；
     *  设成「尝试过」而不是「已成功」，是为了让一个拉不下来的地址也别每次都再试一遍。
     *  视图销毁时置空 —— 那时 ImageView 连同位图一起没了，重建后必须重新下一次（见 [onDestroyView]）。 */
    private var attemptedAvatarUrl: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = Store(requireContext())
        binding.btnLogout.setOnClickListener { confirmLogout() }
        binding.btnPassport.setOnClickListener { openPassport() }
        binding.btnClearCache.setOnClickListener { clearCache() }
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    override fun onDestroyView() {
        // 头像位图跟着视图一起没了：不清掉这个标记，重建后就会以为「已经下过了」而不去补图
        attemptedAvatarUrl = null
        _binding = null
        super.onDestroyView()
    }

    private fun refresh() {
        val nickname = store.nickname
        binding.textNickname.text = nickname.ifBlank { "?" }
        binding.textUid.text = getString(R.string.profile_uid, store.userId)
        // 管理员徽章：is_admin 只来自 /api/me，见 Store.isAdmin
        binding.textAdminBadge.visibility = if (store.isAdmin) View.VISIBLE else View.GONE
        binding.colorDot.background = colorDot(store.color)
        binding.textAboutVersion.text = getString(R.string.profile_about_version_text, BuildConfig.VERSION_NAME)
        showAvatar(nickname)
    }

    /**
     * 头像：优先通行证给的真实头像链接，没有 / 没下下来就回退「昵称首字 + 专属颜色」，
     * 与网页 account.js 的 setAvatarFromUrl 同一套行为。
     *
     * 首字那一版**每次 refresh 都先画好**：它是兜底，也是图片加载期间的占位，任何时候切过来都不该是空白。
     */
    private fun showAvatar(nickname: String) {
        binding.textAvatar.text = nickname.firstOrNull()?.toString() ?: "?"
        binding.textAvatar.background = circleDrawable(store.color)

        val url = store.avatar
        // 没有头像链接（通行证那边没传 / 用户没设）就直接停在首字那版
        if (url == null || url == attemptedAvatarUrl) {
            if (url == null) {
                binding.imageAvatar.visibility = View.GONE
                binding.textAvatar.visibility = View.VISIBLE
            }
            return
        }
        attemptedAvatarUrl = url
        binding.imageAvatar.visibility = View.GONE
        binding.textAvatar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.runIo({ urlToBitmap(url) }) { result ->
            // 下载/解码失败（断网、链接失效、不是图片）就保持首字那版，不弹错 —— 头像是锦上添花
            val bitmap = result.getOrNull() ?: return@runIo
            binding.imageAvatar.setImageBitmap(bitmap)
            binding.imageAvatar.visibility = View.VISIBLE
            binding.textAvatar.visibility = View.GONE
        }
    }

    /**
     * 下头像并解码。整张读进内存：头像就几十 KB，不引图片加载库（理由见 fragment_profile.xml 的注释）。
     * **只在工作线程调用**（[runIo] 把这段放在 Dispatchers.IO 上），主线程发网络会抛 NetworkOnMainThreadException。
     */
    private fun urlToBitmap(url: String): Bitmap? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = AVATAR_TIMEOUT_MS
            readTimeout = AVATAR_TIMEOUT_MS
        }
        return try {
            // 必须套一层 BufferedInputStream：HttpURLConnection 的流不支持 mark/reset，
            // 而某些图片格式（渐进式 JPEG）解码时要回退重读，直接喂原始流会解出 null
            conn.inputStream.use { BitmapFactory.decodeStream(BufferedInputStream(it)) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 通行证中心只在网页上（改昵称 / 颜色 / 密码、生成邀请码），App 里跳系统浏览器打开。
     * 设备上一个能开 https 的应用都没有时 startActivity 会抛 ActivityNotFoundException ——
     * 别让一次点击把 App 崩掉，给一句提示就够。
     */
    private fun openPassport() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Api.PASSPORT_CENTER))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(requireContext(), R.string.profile_passport_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 一个圆形底色（头像首字那版与「专属地图颜色」的色点共用）。
     * 通行证给的主题色是 "#RRGGBB"；给空或给了怪值就退回强调色，不让 parseColor 的异常崩在这一句。
     */
    private fun circleDrawable(color: String): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                runCatching { Color.parseColor(color) }
                    .getOrDefault(ContextCompat.getColor(requireContext(), R.color.accent))
            )
        }

    /**
     * 「专属地图颜色」那枚 22dp 色点：实心是通行证主题色、外面一圈 [R.color.border] 描边。
     * 网页那个色点自带「白 2px 间隙 + 同色 40% 光晕」（box-shadow），一个 View 配 GradientDrawable
     * 做不出这种复合效果；退化成灰描边圆，同样一眼看得出「这是个颜色点」，也不必为它引 layer-list。
     */
    private fun colorDot(color: String): Drawable =
        circleDrawable(color).apply {
            setStroke(
                (COLOR_DOT_STROKE_DP * resources.displayMetrics.density).toInt(),
                ContextCompat.getColor(requireContext(), R.color.border)
            )
        }

    /**
     * 清除缓存：接口报文（[Store.invalidatePayloads]）+ 地图边界（[GeoCache.clear]），
     * 与网页 app.js 的 clearAppCache 同一范围。**不动登录态与偏好**，所以清完不必重新登录。
     *
     * invalidatePayloads 会自增 dataVersion，地图页下次切回来时据此察觉「数据变了」自动重拉 ——
     * 不需要在这里额外通知谁。
     */
    private fun clearCache() {
        store.invalidatePayloads()
        GeoCache.clear(requireContext())
        Toast.makeText(requireContext(), R.string.profile_clear_cache_done, Toast.LENGTH_SHORT).show()
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

        // 本地状态**先**清干净、立刻回登录页：这一步不依赖网络、也不依赖这一屏还在。
        // （旧实现是先发请求、回到回调里才清，网络一慢就看到「点了退出还停在原地」。）
        store.clearSession()
        appContext.gotoLogin()
        activity?.finish()

        // 再「尽力而为」地通知通行证撤销这一个会话（多端登录时不影响其它设备）。
        // 挂在**进程级** scope 上：此刻页面正在销毁，UI scope 会把请求一起取消，那就发不出去。
        // 失败也不处理 —— 本地 token 已经清掉，通不通知不影响本机登录态。
        if (token != null) appScope.launch { runCatching { Auth.logout(token) } }
    }

    private companion object {
        /** 头像图那一次下载的超时（连上 + 读完各一份）。比接口那套短：它只是张缩略图，
         *  超时就退回首字，不必让用户对着空头像等二十秒。 */
        const val AVATAR_TIMEOUT_MS = 8_000

        /** 「专属地图颜色」色点的描边宽度（dp）。1dp 在 22dp 的圆上几乎看不见，
         *  2dp 才起到网页那圈「白边 + 光晕」的分隔作用（见 [colorDot]）。 */
        const val COLOR_DOT_STROKE_DP = 2
    }
}