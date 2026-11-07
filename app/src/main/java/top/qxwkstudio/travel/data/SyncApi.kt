package top.qxwkstudio.travel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable
import top.qxwkstudio.travel.Api
import top.qxwkstudio.travel.net.Http

/** 可选同步的账号与服务信息；数据 push/pull 暂未接入。 */
internal object SyncApi {

    @Serializable
    private data class LoginRequest(val account: String, val password: String)

    @Serializable
    private data class UserDto(
        val uuid: String = "",
        val nickname: String = "",
        val color: String = "",
        val avatar: String? = null,
    )

    @Serializable
    private data class LoginResponse(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("expires_in") val expiresIn: Long = 0,
        val user: UserDto = UserDto(),
    )

    @Serializable
    private data class MeResponse(val user: UserDto = UserDto())

    @Serializable
    data class ServerInfo(
        @SerialName("server_name") val serverName: String = "",
        @SerialName("supports_photo_upload") val supportsPhotoUpload: Boolean = false,
    )

    data class Session(val token: String, val uuid: String, val nickname: String)

    @Serializable
    data class DeviceResponse(@SerialName("device_id") val deviceId: Long = 0)

    @Serializable
    data class PushResult(
        @SerialName("entity_type") val entityType: String = "",
        val uuid: String = "",
        val status: String = "",
        @SerialName("server_revision") val serverRevision: Int = 0,
        val data: JsonObject? = null,
    )

    @Serializable
    data class PushResponse(val results: List<PushResult> = emptyList())

    @Serializable
    data class PullChange(
        val seq: Long = 0,
        @SerialName("entity_type") val entityType: String = "",
        val uuid: String = "",
        val op: String = "",
        val data: JsonObject? = null,
    )

    @Serializable
    data class PullResponse(
        val changes: List<PullChange> = emptyList(),
        @SerialName("next_since") val nextSince: Long = 0,
        @SerialName("has_more") val hasMore: Boolean = false,
    )

    fun register(server: String, token: String, deviceUuid: String, appVersion: String): Result<DeviceResponse> {
        val body = buildJsonObject {
            put("device_uuid", deviceUuid)
            put("name", "Android")
            put("platform", "android")
            put("app_version", appVersion)
        }.toString()
        val result = Http.request("POST", api(server, "sync/register"), token = token, jsonBody = body)
        if (!result.ok) return Result.failure(IllegalStateException(result.body))
        return runCatching { json.decodeFromString(DeviceResponse.serializer(), result.body) }
    }

    fun push(server: String, token: String, deviceUuid: String, changes: List<JsonObject>): Result<PushResponse> {
        val body = buildJsonObject {
            put("device_uuid", deviceUuid)
            put("changes", JsonArray(changes))
        }.toString()
        val result = Http.request("POST", api(server, "sync/push"), token = token, jsonBody = body)
        if (!result.ok) return Result.failure(IllegalStateException(result.body))
        return runCatching { json.decodeFromString(PushResponse.serializer(), result.body) }
    }

    fun pull(server: String, token: String, since: Long): Result<PullResponse> {
        val url = api(server, "sync/pull") + "?since=" + since
        val result = Http.request("GET", url, token = token)
        if (!result.ok) return Result.failure(IllegalStateException(result.body))
        return runCatching { json.decodeFromString(PullResponse.serializer(), result.body) }
    }

    private fun api(server: String, path: String): String {
        val base = server.trim().trimEnd('/').removeSuffix("/api")
        return "$base/api/$path"
    }

    fun login(server: String, account: String, password: String): Result<Session> {
        val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(account, password))
        val result = Http.request("POST", api(server, "auth/login"), jsonBody = body)
        if (!result.ok) return Result.failure(IllegalStateException(result.body))
        return runCatching {
            val parsed = json.decodeFromString(LoginResponse.serializer(), result.body)
            if (parsed.accessToken.isBlank()) error("empty token")
            Session(parsed.accessToken, parsed.user.uuid, parsed.user.nickname)
        }
    }

    fun me(server: String, token: String): Result<Session> {
        val result = Http.request("GET", api(server, "auth/me"), token = token)
        if (!result.ok) return Result.failure(IllegalStateException(result.body))
        return runCatching {
            val parsed = json.decodeFromString(MeResponse.serializer(), result.body)
            Session(token, parsed.user.uuid, parsed.user.nickname)
        }
    }

    fun server(server: String): Result<ServerInfo> {
        val result = Http.request("GET", api(server, "server"))
        if (!result.ok) return Result.failure(IllegalStateException(result.body))
        return runCatching { json.decodeFromString(ServerInfo.serializer(), result.body) }
    }
}
