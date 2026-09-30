package top.qxwkstudio.travel.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import top.qxwkstudio.travel.Api
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.json
import top.qxwkstudio.travel.databinding.ActivityWebviewBinding

/**
 * App 内看网页的通用页：顶栏（标题 + 返回）+ WebView。
 *
 * 两个入口：登录页那行「我已阅读并同意《用户协议》」里的链接（跳 Api.AGREEMENT），
 * 以及主页左上角抽屉里的栏目（地址来自网页端 version.json 的 menu，见 ui/MainActivity）。
 * 以后要再加限时活动之类的页面，还是走这一个页面，`startActivity(WebViewActivity.intent(...))` 即可，
 * 不必再开第二个 WebView 页。**不引 androidx.webkit**：这里用到的东西（WebViewClient、
 * settings 那几个开关）都在系统框架里，为一个只读页面多带一个依赖不值。
 *
 * 四条边界（都是刻意这么定的）：
 *  1. **只留在本站**：同 host 的跳转（协议页的锚点、活动页的子页）由 WebView 自己加载；
 *     链去别处的一律交系统浏览器（见 [WebViewClient.shouldOverrideUrlLoading]）——
 *     别站有自己的登录态与脚本，塞进 WebView 只会做出一个半残的浏览器。
 *  2. **不做下载与文件选择**：这一页只读，所以 WebView 的下载、文件选择、JS 弹窗都没接。
 *     「检查更新 → 前往下载」与「通行证中心」仍然走系统浏览器（前者是 APK 链接，
 *     WebView 里点了什么都不会发生；后者要用它自己的会话登录，在 App 里登录对 App 的登录态毫无帮助）。
 *  3. **返回先退网页、再退页面**：网页里点进去几层后按返回不该直接退出本页（见下面的回退回调）。
 *  4. **身份只递给自家页面，且要调用方点名要**（[intent] 的 withIdentity）：见 [IdentityBridge]。
 *     网页那边默认是**未登录态** —— token 在 App 的 SharedPreferences 里，不在 WebView 的
 *     localStorage 里，两套身份各存各的。不显式递一把，活动页之类需要登录的页面打开就是空的。
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding

    /** 起始地址的 host。只有同 host 的跳转留在 WebView 里 —— 判定基准取「进来的那个地址」，
     *  而不是写死 travel.qxwkstudio.top：以后的活动页若放别的域名，本页不用改。 */
    private var host: String = ""

    /** 允许注入身份桥的 host：**只认自家网页站**（[Api.WEB_ORIGIN]，即仓库里 docs/ 那一层）。 */
    private val identityHost: String = Uri.parse(Api.WEB_ORIGIN).host.orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        // 没给地址就没有可展示的东西：直接退，比开一屏空白页好
        if (url.isBlank()) {
            finish()
            return
        }
        host = Uri.parse(url).host.orEmpty()
        // 要不要把本机身份递给网页（见文件头第 4 点）。默认**不递**：绝大多数页面与登录态无关，
        // 少开一个口子是白赚的
        val withIdentity = intent.getBooleanExtra(EXTRA_WITH_IDENTITY, false)

        binding = ActivityWebviewBinding.inflate(layoutInflater)
        val b = binding
        // 必须在 setContentView 之前（见 ui/EdgeToEdge.kt）。这一页没有底栏，把内容区当「底」
        // topBar 是带 id 的 <include>，ViewBinding 里是 ViewTopBarBinding 而不是 View，取 .root 才是那条栏本身
        applyEdgeToEdge(b.topBar.root, b.content)
        setContentView(b.root)

        b.topBar.title.setText(intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.webview_title_fallback))
        b.topBar.btnBack.visibility = View.VISIBLE
        // 返回按钮不自己 finish()：走系统返回那条路，于是「先退网页」的逻辑只有一份
        b.topBar.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (b.webView.canGoBack()) {
                        b.webView.goBack()
                    } else {
                        // 关掉自己再交回默认处理，否则会把「返回」吃掉、页面退不出去
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        // 自家 HTTPS 页面，脚本只用来做主题切换（网页的 theme.js）与以后活动页的交互，所以开 JS。
        // 文件与内容访问**关掉**：WebView 不需要读本机任何东西，多开一个都是白给的面。
        // domStorage 开着是因为 theme.js 会读 localStorage 记深浅色偏好（它自带 try/catch，关掉也不崩，
        // 但关掉就记不住用户选的主题）。
        b.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        b.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == host) return false
                // 别站 / 邮箱（mailto: 没有 host）交系统浏览器。返回 true = 这个地址我们自己处理了，
                // WebView 不要再去加载它
                openExternal(request.url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.progress.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // 只看主文档：子资源（图片、字体）拉失败不该弹提示，那属于「网页有点小瑕疵」
                if (!request.isForMainFrame) return
                binding.progress.visibility = View.GONE
                Toast.makeText(this@WebViewActivity, R.string.webview_load_failed, Toast.LENGTH_SHORT).show()
            }
        }

        // 身份桥（见文件头第 4 点）。两个条件都满足才挂：
        //  - 调用方点名要（withIdentity）：协议页这种纯文档页不需要，少开一个口子；
        //  - 地址是自家域名：栏目地址来自网页上的 version.json，那是**数据不是代码** ——
        //    万一哪天被改成外站，token 不该跟着流出去。
        // 必须在 loadUrl **之前**注入：网页脚本一执行就可能来找 window.CityFootprint，
        // 晚一步就是「第一次打开拿不到、退出去重进才有」这种最难查的毛病。
        if (withIdentity && host == identityHost) {
            b.webView.addJavascriptInterface(IdentityBridge(this), BRIDGE_NAME)
        }
        b.webView.loadUrl(url)
    }

    override fun onDestroy() {
        // 先把桥摘掉再销毁：桥对象是网页能拿到的引用，留着它等于让「以为已经关掉的」页面继续持有身份
        binding.webView.removeJavascriptInterface(BRIDGE_NAME)
        // WebView 会持有 Activity 的 Context（自己的线程与视图树都挂在上面），不手动销毁等于漏一整屏视图
        binding.webView.destroy()
        super.onDestroy()
    }

    /** 交系统浏览器。兜住「设备上一个能开 https 的应用都没有」这一档（Intent 找不到接收者会抛）。 */
    private fun openExternal(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(this, R.string.common_open_url_failed, Toast.LENGTH_LONG).show() }
    }

    /**
     * 递给网页的身份。**跨语言契约**：下面这些名字（桥名、方法名、报文字段名）两头都在用 ——
     * Java 侧靠 [JavascriptInterface] 暴露给 JS，JS 侧 `window.CityFootprint.getIdentity()` 调用它、
     * 再按字段名取用。改任何一个都是改协议，两端要一起动。
     *
     * 报文字段名对齐网页端 localStorage 里那份用户对象（docs/app.js 的 LS_USER：
     * userId / nickname / color / avatar / is_admin），网页拿到后可以整块塞进自己的存储复用现成读取路径；
     * token 单列一个键（网页端也是单独存一份）。没登录时字段照给、值给空串 —— **结构固定**，
     * 免得网页那边还要判「有没有这个键」。
     */
    private class IdentityBridge(context: Context) {
        // 持 applicationContext：桥对象挂在 WebView 上，活得比造它的那次调用久；而 Store 只需要
        // 一个 Context 去拿 SharedPreferences，没必要把 Activity 拽住
        private val store = Store(context.applicationContext)

        @JavascriptInterface
        fun getIdentity(): String = json.encodeToString(
            IdentityPayload.serializer(),
            IdentityPayload(
                token = store.token.orEmpty(),
                userId = store.userId,
                nickname = store.nickname,
                color = store.color,
                avatar = store.avatar.orEmpty(),
                isAdmin = store.isAdmin,
            ),
        )
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_WITH_IDENTITY = "with_identity"

        /**
         * 注入到网页里的桥名：JS 侧写 `window.CityFootprint.getIdentity()`。
         * 与 [IdentityBridge] 一起被 proguard 规则按名字保住（见 app/proguard-rules.pro）——
         * 名字被改掉以后，debug 包一切正常、release 包拿不到身份，那种问题最难查。
         */
        const val BRIDGE_NAME = "CityFootprint"

        /**
         * 唯一入口。[title] 给顶栏用：网页自己的 `<title>` 带着站点名与后缀，
         * 塞进顶栏只会被省略号截掉，所以由调用方给一句短的。
         *
         * [withIdentity] 置 true 才会给网页挂身份桥（且只在自家域名上生效，见文件头第 4 点）：
         * 活动页之类要读登录态的页面传 true，协议这种纯文档页保持默认的 false。
         */
        fun intent(context: Context, url: String, title: String, withIdentity: Boolean = false): Intent =
            Intent(context, WebViewActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_WITH_IDENTITY, withIdentity)
    }
}

/**
 * 递给网页的那份身份报文（[WebViewActivity.IdentityBridge] 用）。
 * 每个字段都有默认值：`coerceInputValues` + 全字段赋值，序列化出来的键**一个不少**（见 data/Json.kt）。
 */
@Serializable
private data class IdentityPayload(
    val token: String = "",
    val userId: Long = 0,
    val nickname: String = "",
    val color: String = "",
    val avatar: String = "",
    @SerialName("is_admin") val isAdmin: Boolean = false,
)
