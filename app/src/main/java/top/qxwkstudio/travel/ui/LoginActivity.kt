package top.qxwkstudio.travel.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
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

    private var binding: ActivityLoginBinding? = null
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

        val b = ActivityLoginBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        b.btnLogin.setOnClickListener { submit() }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    private fun submit() {
        val b = binding ?: return
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
        Async.run({ Auth.login(account, password) }) { result ->
            val login = result.getOrNull()
            if (login == null) {
                // 连 work 块本身都抛了（不是通行证答错）：用兜底文案，不要把 exception 的 toString 甩给用户
                setBusy(false)
                showError(result.exceptionOrNull()?.message ?: getString(R.string.common_error))
                return@run
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
        val b = binding ?: return
        b.btnLogin.isEnabled = !busy
        b.loginProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        val b = binding ?: return
        b.textLoginError.text = message
        b.textLoginError.visibility = View.VISIBLE
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