package top.qxwkstudio.travel.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
