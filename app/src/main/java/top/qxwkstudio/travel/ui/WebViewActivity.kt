package top.qxwkstudio.travel.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.databinding.ActivityWebviewBinding

/**
 * App 内看网页的通用页：顶栏（标题 + 返回）+ WebView。
 *
 * 现在只有一个入口 —— 登录页那行「我已阅读并同意《用户协议》」里的链接（跳 Api.AGREEMENT）；
 * 以后要加限时活动之类的页面，直接 `startActivity(WebViewActivity.intent(context, url, 标题))`，
 * 不必再开第二个 WebView 页。**不引 androidx.webkit**：这里用到的东西（WebViewClient、
 * settings 那几个开关）都在系统框架里，为一个只读页面多带一个依赖不值。
 *
 * 三条边界（都是刻意这么定的）：
 *  1. **只留在本站**：同 host 的跳转（协议页的锚点、活动页的子页）由 WebView 自己加载；
 *     链去别处的一律交系统浏览器（见 [WebViewClient.shouldOverrideUrlLoading]）——
 *     别站有自己的登录态与脚本，塞进 WebView 只会做出一个半残的浏览器。
 *  2. **不做下载与文件选择**：这一页只读，所以 WebView 的下载、文件选择、JS 弹窗都没接。
 *     「检查更新 → 前往下载」与「通行证中心」仍然走系统浏览器（前者是 APK 链接，
 *     WebView 里点了什么都不会发生；后者要用它自己的会话登录，在 App 里登录对 App 的登录态毫无帮助）。
 *  3. **返回先退网页、再退页面**：网页里点进去几层后按返回不该直接退出本页（见下面的回退回调）。
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding

    /** 起始地址的 host。只有同 host 的跳转留在 WebView 里 —— 判定基准取「进来的那个地址」，
     *  而不是写死 travel.qxwkstudio.top：以后的活动页若放别的域名，本页不用改。 */
    private var host: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        // 没给地址就没有可展示的东西：直接退，比开一屏空白页好
        if (url.isBlank()) {
            finish()
            return
        }
        host = Uri.parse(url).host.orEmpty()

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
        b.webView.loadUrl(url)
    }

    override fun onDestroy() {
        // WebView 会持有 Activity 的 Context（自己的线程与视图树都挂在上面），不手动销毁等于漏一整屏视图
        binding.webView.destroy()
        super.onDestroy()
    }

    /** 交系统浏览器。兜住「设备上一个能开 https 的应用都没有」这一档（Intent 找不到接收者会抛）。 */
    private fun openExternal(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(this, R.string.common_open_url_failed, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        /**
         * 唯一入口。[title] 给顶栏用：网页自己的 `<title>` 带着站点名与后缀，
         * 塞进顶栏只会被省略号截掉，所以由调用方给一句短的。
         */
        fun intent(context: Context, url: String, title: String): Intent =
            Intent(context, WebViewActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
    }
}
