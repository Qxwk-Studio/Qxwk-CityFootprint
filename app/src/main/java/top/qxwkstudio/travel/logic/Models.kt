package top.qxwkstudio.travel.logic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * App 清单里安卓那一节（后端 `GET /api/manifest` 的 `android` 字段）。
 *
 * 2.0 只保留「检查更新」这一个网络请求（口径 2），清单里的公告（`notices`）与主页菜单栏目
 * （`menu`）随对应页面一起删掉了 —— `data/Json.kt` 开了 `ignoreUnknownKeys`，
 * 后端多回这两个键也不会解析失败。
 *
 * **比对用 [versionCode] 而不是 [versionName]**：版本名是给人看的，
 * 「1.2.10 与 1.2.9 谁大」拿字符串比一定答错，而 CI 发版时填的 versionCode 是单调递增的整数。
 * 各字段都有默认值：缺字段不抛异常，[versionCode] 缺省为 0 就等同「没有新版本」——
 * 宁可漏一次提示，也别因为清单写漏一个键就把所有人推向浏览器。
 *
 * [notes] 是更新说明，**一行一条**（弹窗里逐条列出）。后端把 cf_app_version.notes 那一列的多行
 * 文本按 \n 拆成这个数组，所以加减一行说明就是改那一列里的文本。
 */
@Serializable
data class VersionManifest(
    val android: ReleaseInfo = ReleaseInfo(),
)

/** 清单的安卓版本信息：`{version_name, version_code, download_url, notes}`。 */
@Serializable
data class ReleaseInfo(
    @SerialName("version_name") val versionName: String = "",
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("download_url") val downloadUrl: String = "",
    val notes: List<String> = emptyList(),
)
