# City Footprint · 安卓端

Qxwk City Footprint 的原生安卓客户端。登录走「Qxwk 通行证」，足迹数据走本项目后端（Cloudflare Worker）。

## 包名与技术栈

| 项 | 值 |
| --- | --- |
| 包名 / applicationId | `top.qxwkstudio.travel` |
| 语言与界面 | Kotlin + **XML View + viewBinding**（无 Compose、**无 WebView**） |
| SDK | compileSdk 34 / minSdk 24 / targetSdk 34，Java 17 |
| 构建 | AGP 8.2.2 + Kotlin 1.9.22 + Gradle 8.2 |

**工程布局：Gradle 根就是 `app/` 这一层**（`settings.gradle.kts` 在 `app/` 下，`com.android.application`
直接挂在根项目上，源集是 `app/src/main/...`，没有 `:app` 子模块）。所以所有命令都要**在 `app/` 目录里**跑：

```bash
cd app
./gradlew testDebugUnitTest    # 纯 JVM 单测（成就阈值 / visit_date / 城市搜索）
./gradlew assembleDebug        # 需要本机有 Android SDK
```

## 依赖取舍

**压得很小**：`core-ktx`、`appcompat`、`material`、`recyclerview`、`swiperefreshlayout`、`osmdroid`、
`kotlinx-coroutines-android` + `lifecycle-runtime-ktx`、`kotlinx-serialization-json`、`junit`（测试）。

- **网络**仍是 JDK 的 `HttpURLConnection`（`net/Http.kt`），**不引** Retrofit/OkHttp：
  一共十来个接口、全是「点一下发一次」，Retrofit 的注解接口 + converter 换不到什么，
  OkHttp 的连接池/拦截器也用不上。
- **JSON** 从 `org.json` 换成 `kotlinx-serialization`（`@Serializable` 数据类，见 `logic/Models.kt` 与 `data/Json.kt`）：
  序列化器在**编译期**生成、运行时不用反射，所以**不需要手写 keep 规则**（这是相对 Gson/Moshi 的主要好处）。
  后端那个「没有布尔类型、`is_private` 存 0/1」的怪癖用一个自定义序列化器表达
  （`IntBooleanSerializer`），界面上只见到 Boolean。
- **异步**用协程 + lifecycle（`ui/Coroutines.kt` 的 `runIo`）：挂在 `viewLifecycleOwner.lifecycleScope`
  （Fragment）或 `lifecycleScope`（Activity）上，**页面销毁时请求自动取消** —— 因此各页不再需要
  `_binding ?: return` 那一堆兜底判空。退出登录那次「尽力而为」的撤销请求挂在进程级 `appScope` 上
  （挂 UI scope 会被页面销毁一起取消，那就发不出去了）。
- **边界 GeoJSON 仍是手工解析**（`ui/GeoJson.kt`，用 Android 自带的 `org.json`）：坐标是异构嵌套数组，
  换成 kotlinx 也得手动遍历 `JsonElement`，省不下多少，而这里只要 Polygon / MultiPolygon 的坐标。
- release 开了 R8（`isMinifyEnabled` + `isShrinkResources`）。**风险点在 `proguard-rules.pro`**：
  `fragment_map.xml` 里按类名写死的 `org.osmdroid.views.MapView` 走的是 LayoutInflater 反射路径，
  名字被改掉会在**打开地图页时直接崩**，所以那里显式 keep 了地图相关包。
  （kotlinx-serialization 与协程都不需要手写 keep 规则。）

## 后端与域名（要改地址只改一处）

`app/src/main/java/top/qxwkstudio/travel/Api.kt` 是域名与接口路径的**唯一出处**：

- `ACCOUNT_BASE = https://account.qxwkstudio.top/api` —— 「Qxwk 通行证」，管登录 / 登出 / 身份。密码只发到这里，**不经过足迹后端**。
- `TRAVEL_BASE = https://api.travel.qxwkstudio.top/api` —— 本项目后端（Cloudflare Worker），管足迹数据（`/my-visits`、`/visits`、`/stats`）与地图边界（`/geo/{adcode}`）。

三个域名（通行证 `account.qxwkstudio.top` / 网页 `travel.qxwkstudio.top` / 接口 `api.travel.qxwkstudio.top`）
都是 HTTPS，所以清单里只有 `INTERNET` 权限、没有 `network_security_config` 的明文例外。

**阶段 2 已完成**（页面搬去 GitHub Pages `travel.qxwkstudio.top`、接口挪到 `api.travel.qxwkstudio.top`）：
全程只改了这一行 `TRAVEL_BASE`，其余代码都按相对路径拼。前后端分家后网页要过 CORS，
但**安卓走原生 HTTP、不带 `Origin` 头，不受浏览器那套 CORS 规矩约束** —— 白名单里没有安卓、也不用加。

### `client` 字符串与通行证登记的关系

登录时 body 里带 `"client": "CityFootprint Android"`（常量在 `Api.CLIENT_NAME`）。
安卓没有 `Origin` 头，通行证只能靠这个字段记「这次登录来自哪个应用」，会话管理页据此分类展示。

**若通行证侧没登记过这个名字，登录照常可用**，只是那条会话在通行证后台会被记成「未登记来源」。
要让它在会话列表里有正式名字/能被单独撤销，需要**在通行证（Qxwk-Account）侧登记这个字符串**。
改这里的字符串就要同步登记，否则又回到「未登记来源」。

## 地图选型

用 **osmdroid 6.1.20**（`org.osmdroid:osmdroid-android`，Maven Central）。理由：

- 纯原生 Android View 的 OSM 地图，**不需要 API key**、不用在控制台绑包名与签名，个人项目少一处密钥管理；
- 与「界面与地图都要原生、不许 WebView 套壳」的要求一致；
- 6.1.20 是它在 Maven Central 上的最后一个正式版（上游仓库 2024-11 已归档，包还在、API 稳定）。

被否掉的方案：**Google Maps SDK**（要申请 API key 并绑包名 + 签名指纹，多一处密钥）、
**WebView 套网页版地图**（用户明确不要 WebView）。

边界数据用阿里 DataV GeoJSON，**手工解析**（`ui/GeoJson.kt`，只认 Polygon / MultiPolygon 的坐标），
不引通用 GeoJSON 库。整条边界链路都是可降级的：城市在源数据里没有 `adcode`（县级市）、
或 GeoJSON 解析失败，都只提示一句、**标记照旧保留**。

瓦片缓存**用 osmdroid 的默认值**，不要再加 `setExpirationOverrideDuration`：文件缓存的默认有效期
是 `DEFAULT_MAXIMUM_CACHED_FILE_AGE`（一周）—— `MapTileFilesystemProvider` 的构造器把
`Configuration.getExpirationExtendedDuration()`（默认 0）加上它，正好就是「瓦片缓存 7 天」。
另有一个常量 `TILE_EXPIRY_TIME_MILLISECONDS`（30 天），走的是 HTTP 头那条路径的兜底，别与它说混。
边界与瓦片都极少变动，过期只是标记 stale 触发重下，并不会删掉已缓存的文件。

边界 GeoJSON 另有本机落盘缓存（`data/GeoCache.kt`，`filesDir/geo/{adcode}.json`，按文件 mtime 判 7 天），
与网页 IndexedDB 那份同形态：命中直接返回；过期**先返回旧内容**、后台**串行**重下换文件（SWR，不重画）。
它**不跟登录态走** —— 边界是公共数据，别把它塞进 `Store` 的 `cache_` 前缀（那套会随退出登录 / 写操作整体作废）。

## 城市表（assets/cities.json 是生成物）

`app/src/main/assets/cities.json` **不要手改**：它由 `app/tools/gen-cities.mjs` 从
`docs/cities.js`（名称 / 省份 / 坐标）与 `docs/city-codes.js`（名称 → adcode）生成，
零依赖、可重复运行（同样的输入产出逐字节相同的输出，diff 干净）。

```bash
node app/tools/gen-cities.mjs
```

脚本会自己校验并在失败时**以退出码 1 结束、不写文件**：城市条数与源文件条数一致、无重名、坐标是数字且在国境内。
输出还会报告有多少城市缺 `adcode`（源数据里的县级市，地图上会降级成「只显示标记」）。

**前端改了城市数据（增删城市、改坐标）就重跑一次并提交这份 JSON** —— 两端同源靠的就是这一步。
当前：384 座城市，370 座有 adcode。

城市搜索在 `logic/City.kt`（名称 / 省份模糊匹配，空查询返回全部）。
**没有做拼音首字母匹配**：源数据里没有拼音字段，要支持就得再维护一张几千字的拼音表；
哪天 `cities.js` 出现了拼音字段，在那里加一条比对即可。

## 界面结构（底部导航 5 个 tab）

五个 tab 的正文都对齐网页端，改 tab 时两端一起看：

| tab | 图标 | 正文 = 网页端的 | Fragment |
| --- | --- | --- | --- |
| 主页 | `ic_home` | 足迹管理页（`visits.html`，含顶部「足迹统计」概览） | `VisitsFragment` |
| 我的成就 | `ic_achievement` | 足迹管理页的成就区（`visits.html`，成就结果由后端 `backend/src/achievements.js` 下发） | `AchievementsFragment` |
| 地图 | `ic_map` | 首页足迹大地图（`index.html`） | `MapFragment` |
| 全站统计 | `ic_stats` | 全站统计页（`stats.html`） | `StatsFragment` |
| 我的 | `ic_person` | 个人中心（`account.html`）与设置 | `ProfileFragment` |

- 图标必须是**单色矢量**（`BottomNavigationView` 按选中态自己染色），放在 `res/drawable/ic_*.xml`；
  菜单在 `res/menu/bottom_nav.xml`，路由（tag / 标题 / 首个 Fragment）在 `ui/MainActivity.kt`。
- Fragment 用 **add + hide/show**（不是 replace），所以隐藏页仍是 RESUMED，
  各页感知「被切回来」用 `onHiddenChanged` 而不是 `onResume`。

## 签名与发版

1. 生成 keystore（别名按本仓库的习惯叫 `city-footprint`）：
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias city-footprint -keyalg RSA -keysize 2048 -validity 10000
   ```
2. base64 一份填进 Secrets（PowerShell）：
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))
   ```
3. 仓库 Secrets 要齐四个（日志里都叫 `CF_*` 前缀是给 `build.gradle.kts` 读的环境变量名，与 Secrets 名字无关）：
   `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。**密钥与口令务必另行备份**，
   丢了只能改包名、让所有人重装一次。
4. Actions → `Build Android APK` → `Run workflow`，填 `version_name`（如 `1.0.0`）与 `version_code`（整数）。
   CI 会：单测 → `assembleRelease` → 把产物改名为 `CityFootprint-<version_name>.apk` 并上传为 artifact。
   缺 `KEYSTORE_BASE64` 会直接 `::error::` 退出；签名没生效时产物叫 `app-release-unsigned.apk`，
   改名那一步会因为找不到 `app-release.apk` 而失败 —— 这是**故意的**，绝不把未签名包当正式包传上去。

## 目录

```
app/
├── build.gradle.kts            # 根项目：applicationId / 版本号（CI 用 sed 注入）/ 签名 / R8 / 依赖
├── settings.gradle.kts         # 单模块工程，没有 include(":app")
├── proguard-rules.pro
├── tools/gen-cities.mjs        # 城市表生成脚本
└── src/
    ├── main/
    │   ├── AndroidManifest.xml # 只有 INTERNET + ACCESS_COARSE_LOCATION；四个 Activity
    │   ├── assets/cities.json  # 生成物（见上）
    │   ├── res/                # values / drawable / mipmap / menu / layout
    │   └── java/top/qxwkstudio/travel/
    │       ├── Api.kt          # 域名与接口路径的唯一出处
    │       ├── logic/          # 纯 Kotlin：不 import android.*，所以能在 JVM 上单测
    │       ├── net/ data/      # HttpURLConnection / kotlinx-serialization / SharedPreferences / 各接口
    │       └── ui/             # Activity / Fragment / Adapter / 协程封装 / 401 统一处理
    └── test/java/top/qxwkstudio/travel/         # 单测（JVM，不需要设备）：
        ├── logic/   # 成就边界、visit_date、城市搜索
        └── data/    # JsonWireTest：请求/响应报文字段契约
```

> **启动图标是生成物**：`res/mipmap-*/ic_launcher.png` 五档（mdpi 48 / hdpi 72 / xhdpi 96 /
> xxhdpi 144 / xxxhdpi 192）都是**同一张 1024×1024 的 logo 源图**缩放出来的（圆角方形、四角透明），
> 源图不进仓库，所以**不要手改这些 PNG**。网页端的 `docs/favicon.webp`（256×256，lossless webp）
> 也出自这张源图 —— 换 logo 时两边要一起换，否则网页与 app 的图标会不一致。

## 本机状态与登录态

token 存在 `MODE_PRIVATE` 的 SharedPreferences（`data/Store.kt`），与网页版放 localStorage 属同一级别，
**未加密**：安卓没有系统级加密存储，真机 root 后能被读走（与其它未加固 App 同级）。
兜底靠三层：清单里 `allowBackup=false`（云备份不带 token）、全站 HTTPS、日志不打印 token。
刻意**没用** `EncryptedSharedPreferences`（要求 targetSdk 更高，且换库要迁移存量数据，收益与改动不成比例）。

任何接口拿到 401 都走**同一个出口**（`ui/Session.kt`）：清本地 token → 提示一次 → 回登录页，
避免三个页面各弹一次错、或者用户被反复踢。**退出登录**无论通行证那边成功与否都清本地 token。