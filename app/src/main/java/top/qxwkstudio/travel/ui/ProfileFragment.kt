package top.qxwkstudio.travel.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import top.qxwkstudio.travel.Api
import top.qxwkstudio.travel.BuildConfig
import top.qxwkstudio.travel.CityFootprintApp
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Appearance
import top.qxwkstudio.travel.data.Auth
import top.qxwkstudio.travel.data.GeoCache
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.Update
import top.qxwkstudio.travel.databinding.FragmentProfileBinding
import top.qxwkstudio.travel.logic.ReleaseInfo
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「我的」：本机登录身份的展示（头像 / 昵称 / UID / 管理员徽章 / 专属地图颜色）、通行证入口、
 * 外观切换、清除本机缓存与退出登录（对齐网页 docs/account.html 的个人中心）。
 *
 * 外观（浅色 / 深色 / 跟随系统）是 **App 特有的一行**：网页那颗太阳/月亮按钮在导航栏上，
 * App 没有那条导航栏，就收进这一页的设置组（两档扩成三档，见 [chooseAppearance]）。
 *
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
        // 六处都是「整行可点」的设置行，没有按钮（见 fragment_profile.xml 的分组结构）
        binding.rowLogout.setOnClickListener { confirmLogout() }
        binding.rowPassport.setOnClickListener { openUrl(Api.PASSPORT_CENTER) }
        binding.rowAppearance.setOnClickListener { chooseAppearance() }
        binding.rowClearCache.setOnClickListener { clearCache() }
        binding.rowCheckUpdate.setOnClickListener { checkUpdate() }
        binding.rowNotice.setOnClickListener { startActivity(NoticeActivity.intent(requireContext())) }
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
        binding.textAboutVersion.text = getString(R.string.profile_about_version_value, BuildConfig.VERSION_NAME)
        binding.textAppearanceValue.text = getString(appearanceLabel(store.appearance))
        showAvatar(nickname)
    }

    /**
     * 外观偏好 → 那一行的行尾值、也是弹窗里的选项文案。三档见 [Appearance]。
     * 认不出的值（换版本 / 手改偏好留下的怪值）由 [Store.appearance] 兜成 SYSTEM，这里自然也就落在「跟随系统」。
     */
    private fun appearanceLabel(appearance: Appearance): Int = when (appearance) {
        Appearance.SYSTEM -> R.string.profile_appearance_follow_system
        Appearance.LIGHT -> R.string.profile_appearance_light
        Appearance.DARK -> R.string.profile_appearance_dark
    }

    /**
     * 「外观」三选：跟随系统 / 浅色 / 深色。用单选列表而不是 Switch —— 三档摆不下一个开关
     * （网页那颗太阳/月亮按钮只有两档），而单选的当前项天然就是「现在是什么」，与行尾那个值对得上。
     *
     * 弹窗壳取 MaterialAlertDialogBuilder（本页三处弹窗——外观 / 检查更新 / 退出登录——都用它，
     * 全 app 的弹窗也都是它：M3 那套圆角 28dp 的壳），但**不用 setSingleChoiceItems**：
     * 它铺出来的是系统单选列表（三行 RadioButton、系统自带的行高与配色），
     * 与全站那套卡片式界面不是一回事 —— 这里自己铺三行（见 [appearanceRow]）。
     *
     * 选完**先收弹窗再切**：切外观会重建所有正在显示的 Activity（理由见 CityFootprintApp.applyAppearance），
     * 弹窗挂在一个正在销毁的窗口上会报 WindowLeaked。重建后本页自己会重新 refresh 出行尾的新值，
     * 这里不必手动改界面。
     */
    private fun chooseAppearance() {
        val ctx = requireContext()
        val options = Appearance.entries
        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // 行的回调里要 dismiss，但弹窗要等 show() 才有：先留个可空引用，行建好后再赋值
        var dialog: AlertDialog? = null
        for (option in options) {
            rows.addView(
                appearanceRow(option, option == store.appearance) {
                    dialog?.dismiss()
                    store.saveAppearance(option)
                    CityFootprintApp.applyAppearance(option)
                }
            )
        }
        dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.profile_appearance_title)
            .setView(rows)
            .show()
    }

    /** 外观弹窗里的一行：左侧档名、行尾一枚 ✓（只有当前那一档有）。整行可点、带按下涟漪。 */
    private fun appearanceRow(option: Appearance, selected: Boolean, onClick: () -> Unit): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 左右 24dp 与 M3 对话框标题/正文的内边距对齐，整行热区左右也就顶到弹窗边上
            setPadding(dp(24), dp(14), dp(24), dp(14))
            // 按下涟漪取自主题（布局里那套 ?attr/selectableItemBackground 在代码里只能用这种取法）
            val ripple = TypedValue()
            ctx.theme.resolveAttribute(androidx.appcompat.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onClick() }
        }
        row.addView(TextView(ctx).apply {
            text = getString(appearanceLabel(option))
            setTextColor(ContextCompat.getColor(ctx, if (selected) R.color.accent else R.color.fg))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = getString(R.string.profile_appearance_check)
            setTextColor(ContextCompat.getColor(ctx, R.color.accent))
            textSize = 15f
            // 用 INVISIBLE 而不是 GONE 占位：勾在几行之间出现/消失时，档名不会左右挪一下
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
        })
        return row
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
     * 跳系统浏览器打开一个 https 地址：通行证中心与「检查更新 → 前往下载」两处共用。
     * 设备上一个能开 https 的应用都没有时 startActivity 会抛 ActivityNotFoundException ——
     * 别让一次点击把 App 崩掉，给一句提示就够。
     */
    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(requireContext(), R.string.common_open_url_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 检查更新：拉网页根下的版本清单（[Api.VERSION_MANIFEST]，就是 docs/version.json），
     * 把清单里的 version_code 与当前 [BuildConfig.VERSION_CODE] 比大小（为什么比 code 而不是版本名，见 logic/Models.kt 的 ReleaseInfo）。
     *
     * 三条出路：拉不到 → Toast 一句；不新 → Toast 一句；有新版本 → 弹窗问一句，点「前往下载」交给系统浏览器。
     * **App 内不做下载与安装** —— 那要一路处理存储权限、FileProvider 与「未知来源」安装授权，
     * 而现在连 APK 的公开下载地址都还没定（version.json 里是占位符），跳浏览器是最不容易做错的一步。
     */
    private fun checkUpdate() {
        viewLifecycleOwner.lifecycleScope.runIo({ Update.fetch() }) { result ->
            val info = result.getOrNull()?.android
            if (info == null) {
                Toast.makeText(requireContext(), R.string.profile_update_failed, Toast.LENGTH_SHORT).show()
                return@runIo
            }
            if (info.versionCode <= BuildConfig.VERSION_CODE) {
                Toast.makeText(requireContext(), R.string.profile_update_latest, Toast.LENGTH_SHORT).show()
                return@runIo
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_update_new_title)
                .setMessage(updateMessage(info))
                .setPositiveButton(R.string.profile_update_go) { _, _ -> openDownload(info.downloadUrl) }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    /**
     * 弹窗正文：三段拼起来 —— 版本、更新说明、问一句，段与段之间空一行。
     *
     * 更新说明来自清单的 `notes`（一行一条，逐条套上「· 」前缀），**没写就整段省掉**，
     * 连小标题一起 —— 否则弹窗里会挂着一个空荡荡的「更新说明：」。
     * 说明很长时不用管换行与截断：AlertDialog 的正文区自己会滚。
     */
    private fun updateMessage(info: ReleaseInfo): String {
        val blocks = mutableListOf(getString(R.string.profile_update_new_message, info.versionName))
        if (info.notes.isNotEmpty()) {
            val items = info.notes.joinToString("\n") { getString(R.string.profile_update_note_item, it) }
            blocks += getString(R.string.profile_update_notes_title) + "\n" + items
        }
        blocks += getString(R.string.profile_update_confirm)
        return blocks.joinToString("\n\n")
    }

    /**
     * 「前往下载」：清单里的 download_url 是手改的，可能空着 ——
     * 空地址别塞给 [Uri.parse]（会得到一个空的 URI，浏览器打开一片空白），直接提示一句。
     */
    private fun openDownload(url: String) {
        if (url.isBlank()) {
            Toast.makeText(requireContext(), R.string.profile_update_no_url, Toast.LENGTH_LONG).show()
            return
        }
        openUrl(url)
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

    /** dp → 像素。外观弹窗那三行的内边距要用（同 MapFragment 的 dp()）。 */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        MaterialAlertDialogBuilder(requireContext())
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