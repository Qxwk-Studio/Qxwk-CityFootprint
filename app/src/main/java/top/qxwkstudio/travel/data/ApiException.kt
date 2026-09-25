package top.qxwkstudio.travel.data

import org.json.JSONObject
import top.qxwkstudio.travel.net.Http
import top.qxwkstudio.travel.net.HttpResult

/**
 * 服务端答了非 2xx（或压根没连上）。
 *
 * code 的含义：
 *   401 —— token 失效/未登录。**调用方不要自己弹错**，交给 ui/Session.expired 统一清 token 回登录页；
 *   403 —— 想改别人的记录（PUT/DELETE 越权）；
 *   429 —— 通行证登录失败次数过多被锁 15 分钟；
 *   [Http.NETWORK_FAILED] —— 连都没连上，message 是底层原因。
 *
 * message 优先取后端返回的 `error` **原文**：那些文案（「帐号或密码不正确」「请选择城市」）
 * 是给用户看的，客户端再编一遍只会与后端不一致。
 */
class ApiException(val code: Int, message: String) : Exception(message)

/** 从失败响应里抽出能给用户看的一句话；后端没给 error 字段时用调用方的兜底文案。 */
internal fun apiException(result: HttpResult, fallback: String): ApiException {
    val fromServer = runCatching { JSONObject(result.body).strOrNull("error") }.getOrNull()
    return ApiException(result.code, fromServer ?: fallback)
}