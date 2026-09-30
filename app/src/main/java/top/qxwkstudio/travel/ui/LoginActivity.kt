package top.qxwkstudio.travel.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.Api
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Auth
import top.qxwkstudio.travel.data.LoginResult
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.databinding.ActivityLoginBinding

/**
 * 登录页（通行证）。也是整个 app 的入口判定点：
 * **已登录就直接进主页并 finish**，用户不会看到这一屏闪一下。
 *
 * 登录成功后拿到的 token 存在本机 SharedPreferences（见 data/Store 的取舍说明），
 * 密码只发往通行证，绝不落到足迹后端。
 */
class LoginActivity : AppCompatActivity() {

    // 非空 lateinit：Activity 与它的视图同生共死，不像 Fragment 需要在 onDestroyView 里置 null。
    // 异步回调挂在 lifecycleScope 上（onDestroy 时取消），所以回调里直接用 binding 是安全的。
    private lateinit var binding: ActivityLoginBinding
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 每次回到这一屏都重新打开「401 已处理」的闸门（见 Session.reset 的说明）
        Session.reset()
        store = Store(this)

        if (store.isLoggedIn) {
            goMain()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        val b = binding
        // 必须在 setContentView 之前（见 ui/EdgeToEdge.kt）。底栏（账号说明那条）也在这里吃 inset。
        // topBar 是带 id 的 <include>，ViewBinding 里是 ViewTopBarBinding 而不是 View，取 .root 才是那条栏本身
        applyEdgeToEdge(b.topBar.root, b.bottomBar)
        setContentView(b.root)

        // 顶栏是四页共用的（view_top_bar.xml），不自带文案，标题在本页填
        b.topBar.title.setText(R.string.login_bar_title)
        b.btnLogin.setOnClickListener { submit() }
        // 注册与找回密码只在通行证那边有，跳系统浏览器（注册是通行证登录页里的 tab，见 Api.PASSPORT_LOGIN）
        b.textRegisterTip.setOnClickListener { openUrl(Api.PASSPORT_LOGIN) }
        bindAgreeRow()
    }

    /**
     * 同意协议那行：只把「《用户协议》」这一截做成可点链接（跳系统浏览器看网页上的协议正文），
     * 勾选框其余部分照旧能点、能勾。
     *
     * 用 span 而不是把整行都变成链接：整行可点的话，想勾选的人一点就跳走了。
     * 两句文案（整句 / 片段）对不上时静静退回纯文字 —— 那是文案改漏了，不该在登录页崩一下。
     */
    private fun bindAgreeRow() {
        val text = getString(R.string.login_agree_terms)
        val link = getString(R.string.login_agree_link)
        val start = text.indexOf(link)
        if (start < 0) return
        val spannable = SpannableString(text)
        spannable.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    // 协议正文在 App 内看（顶栏带返回，不把用户整个甩到浏览器）；
                    // 正文仍只有网页那一份，改文案不用发版 —— 见 Api.AGREEMENT 与 ui/WebViewActivity
                    startActivity(
                        WebViewActivity.intent(this@LoginActivity, Api.AGREEMENT, getString(R.string.agreement_title))
                    )
                }

                // 链接色取主色、不加下划线（与「没有账号」那行的观感一致，别一处带线一处不带）
                override fun updateDrawState(ds: TextPaint) {
                    ds.color = ContextCompat.getColor(this@LoginActivity, R.color.accent)
                    ds.isUnderlineText = false
                }
            },
            start,
            start + link.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        binding.checkAgree.text = spannable
        // CheckBox 也是 TextView：setMovementMethod 之后，落在链接上的触摸由 span 消费，
        // 落在别处的照旧切换勾选 —— 这也是不整行套点击的原因
        binding.checkAgree.movementMethod = LinkMovementMethod.getInstance()
    }

    /** 跳外部浏览器。兜住「设备上一个能开 https 的应用都没有」这一档（Intent 找不到接收者会抛）。
     *  与 ProfileFragment / WebViewActivity 里那两处是同一形状 —— 三处都只有几行，
     *  为一个「起个 Intent 打开 URL」单开工具类不划算。 */
    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.common_open_url_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun submit() {
        val b = binding
        val account = b.inputAccount.text?.toString()?.trim().orEmpty()
        val password = b.inputPassword.text?.toString().orEmpty()

        // 上一轮的报错先清掉：不清的话用户改完重试，旧提示还挂着，看起来像「又错了一次」
        b.accountLayout.error = null
        b.passwordLayout.error = null
        b.textLoginError.visibility = View.GONE

        // 空值这一档只有客户端知道，不必往返一次网络（通行证那边也会拦，但用户等一次往返没意义）
        if (account.isEmpty()) {
            b.accountLayout.error = getString(R.string.login_err_account)
            return
        }
        if (password.isEmpty()) {
            b.passwordLayout.error = getString(R.string.login_err_password)
            return
        }
        // 协议是本站自己的前置条件（通行证那边不知道有这份协议），只能拦在这一层：不勾就不发请求。
        // 提示走 textLoginError，与 401 那些通行证原文同一处显示
        if (!b.checkAgree.isChecked) {
            showError(getString(R.string.login_agree_required))
            return
        }

        setBusy(true)
        // 挂在 Activity 的 lifecycleScope 上：退出这一屏时请求自动取消，回调里不必再判 binding
        lifecycleScope.runIo({ Auth.login(account, password) }) { result ->
            val login = result.getOrNull()
            if (login == null) {
                // 连 work 块本身都抛了（不是通行证答错）：用兜底文案，不要把 exception 的 toString 甩给用户
                setBusy(false)
                showError(result.exceptionOrNull()?.message ?: getString(R.string.common_error))
                return@runIo
            }
            when (login) {
                is LoginResult.Ok -> {
                    // 存 token 之后才进主页 —— MainActivity 一进去就会拿它拉数据
                    store.saveSession(login.session)
                    goMain()
                }
                // 200 但没有 token：空壳账号，引导去通行证设密码。绝不能当成功存下空 token
                LoginResult.NeedSetPassword -> {
                    setBusy(false)
                    showError(getString(R.string.login_need_password))
                }
                // 401「帐号或密码不正确」/ 429「失败过多…」：一律原文展示，不自己编
                is LoginResult.Failed -> {
                    setBusy(false)
                    showError(login.message)
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnLogin.isEnabled = !busy
        binding.loginProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        binding.textLoginError.text = message
        binding.textLoginError.visibility = View.VISIBLE
    }

    private fun goMain() {
        // CLEAR_TASK：把登录页从返回栈里摘掉，登录后再按返回不该回到登录页
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
