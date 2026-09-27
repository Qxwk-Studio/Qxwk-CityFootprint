package top.qxwkstudio.travel.data

import kotlinx.serialization.Serializable
import top.qxwkstudio.travel.Api
import top.qxwkstudio.travel.logic.LoginSession
import top.qxwkstudio.travel.logic.Me
import top.qxwkstudio.travel.net.Http

/**
 * 登录结果。三种形态都是**真实存在**的，别把它压成「成功/失败」两种：
 * 第三种（[NeedSetPassword]）在网页版的契约里就有，压掉它就会把空 token 当登录成功存下来。
 */
sealed class LoginResult {
    data class Ok(val session: LoginSession) : LoginResult()

    /** 通行证返回 200 但没有 token：该账号是管理员预建的**空壳**（还没设过密码）。 */
    object NeedSetPassword : LoginResult()

    /** 401 帐号或密码不正确 / 429 失败过多被锁 / 网络不通。message 是给用户看的原文。 */
    data class Failed(val message: String) : LoginResult()
}

/** /api/me 的三种结果。区分 [Unauthorized] 与 [Failed]：前者要清 token 回登录页，后者只是这一次没成功。 */
sealed class MeResult {
    data class Ok(val me: Me) : MeResult()
    object Unauthorized : MeResult()
    data class Failed(val message: String) : MeResult()
}

/** 登录请求体：字段名（nickname/password/client）就是通行证那边认的名字。 */
@Serializable
private data class LoginBody(val nickname: String, val password: String, val client: String)

/**
 * 登录响应。成功时带 token 等字段，失败时带 `error` —— 两种形态共用这一个私有解析模型：
 * 通行证只在这一处解析，拆成两个 DTO 就得把同一个报文解析两遍。
 */
@Serializable
private data class LoginResponse(
    val token: String = "",
    val userId: Long = 0,
    val nickname: String = "",
    val color: String = "",
    val email: String? = null,
    val avatar: String? = null,
    val error: String? = null,
)

/**
 * 「Qxwk 通行证」的登录/登出，以及足迹后端的 getMe。
 *
 * 认证链路：app 直接把账号密码提交给通行证换 token（密码**不经过**足迹后端），
 * 之后所有足迹接口都带 `Authorization: Bearer <token>`。
 * 跨站 SSO / 跳转授权已下线，所以这里是唯一入口，没有网页那套回调。
 */
object Auth {

    fun login(account: String, password: String): LoginResult {
        val body = json.encodeToString(
            LoginBody.serializer(),
            // 安卓没有 Origin 头，通行证只能靠 client 记来源；名字没登记过也不影响登录（见 Api.CLIENT_NAME）
            LoginBody(nickname = account, password = password, client = Api.CLIENT_NAME)
        )

        val result = Http.request("POST", Api.LOGIN, jsonBody = body)
        if (result.code == Http.NETWORK_FAILED) {
            return LoginResult.Failed("网络连接失败，请检查网络后重试")
        }

        // 报文可能不是 JSON（网关的 HTML 错误页）→ 解析失败当「没有可用信息」，走下面的兜底文案
        val resp = runCatching { json.decodeFromString(LoginResponse.serializer(), result.body) }.getOrNull()
        if (result.ok) {
            // ★ 200 但没有 token（空壳账号）与「报文根本解析不出来」都归到 NeedSetPassword 这一档 ——
            //   绝不能把空 token 当成功存下来：存了之后每个接口都 401，
            //   用户会看到「刚登录就又要登录」，且找不到原因。
            val ok = resp?.takeIf { it.token.isNotEmpty() } ?: return LoginResult.NeedSetPassword
            return LoginResult.Ok(
                LoginSession(
                    token = ok.token,
                    userId = ok.userId,
                    nickname = ok.nickname,
                    color = ok.color,
                    email = ok.email,
                    avatar = ok.avatar,
                )
            )
        }

        // 401「帐号或密码不正确」、429「失败过多请稍后再试」——文案一律用通行证给的原文，不自己编
        val message = resp?.error ?: when (result.code) {
            Http.NETWORK_FAILED -> "网络连接失败，请检查网络后重试"
            else -> "登录失败（HTTP ${result.code}）"
        }
        return LoginResult.Failed(message)
    }

    /**
     * 退出登录：让通行证**撤销这一个会话**（多端登录时不影响其它设备）。
     * 无论成功失败，调用方都要清本地 token —— 网络不通时也该退出去，
     * 否则用户以为没退出、下次打开还在里面。
     */
    fun logout(token: String) {
        runCatching { Http.request("POST", Api.LOGOUT, token = token) }
    }

    /** 校验 token 并取当前用户资料。token 失效时足迹后端回 401。 */
    fun me(token: String): MeResult {
        val result = Http.request("GET", Api.ME, token = token)
        if (result.code == 401) return MeResult.Unauthorized
        if (!result.ok) {
            return MeResult.Failed(
                if (result.code == Http.NETWORK_FAILED) "网络连接失败" else "获取资料失败（HTTP ${result.code}）"
            )
        }
        val me = runCatching { json.decodeFromString(Me.serializer(), result.body) }.getOrNull()
            ?: return MeResult.Failed("返回内容无法解析")
        return MeResult.Ok(me)
    }
}
