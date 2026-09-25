# 城市足迹 · 安卓端

Qxwk City Footprint 的原生安卓客户端。登录走「Qxwk 通行证」，足迹数据走本项目后端（Cloudflare Worker）。

## 包名与技术栈

| 项 | 值 |
| --- | --- |
| 包名 / applicationId | `top.qxwkstudio.travel` |
| 语言与界面 | Kotlin + **XML View + viewBinding**（无 Compose、**无 WebView**） |
| SDK | compileSdk 34 / minSdk 24 / targetSdk 34，Java 17 |
| 构建 | AGP 8.2.2 + Kotlin 1.9.22 + Gradle 8.2 |

技术栈与同工作室的 [`Class-Assistant/android`](../../Class-Assistant/android) 对齐，两边一起升级。

**工程布局：Gradle 根就是 `app/` 这一层**（`settings.gradle.kts` 在 `app/` 下，`com.android.application`
直接挂在根项目上，源集是 `app/src/main/...`，没有 `:app` 子模块）。所以所有命令都要**在 `app/` 目录里**跑：

```bash
cd app
./gradlew testDebugUnitTest    # 纯 JVM 单测（成就阈值 / visit_date / 城市搜索）
./gradlew assembleDebug        # 需要本机有 Android SDK
```

## 本地构建

**这台机器上没有 Android SDK，也没有 JDK**，所以本地**编不了**：安卓侧的改动只能靠 CI 验证
（`.github/workflows/build-android.yml`）。改动推上去后到 Actions 里 `Build Android APK` →
`Run workflow`，填 `version_name` / `version_code` 触发。

能本地跑的两件事：

```bash
node app/tools/gen-cities.mjs        # 重新生成城市表（见下）
# XML 是否 well-formed（PowerShell）：
#   Get-ChildItem -Recurse app/src/main/res -Include *.xml | % { [xml](Get-Content $_.FullName) }
```

## 依赖取舍

**刻意压到最少**，只有：`core-ktx`、`appcompat`、`material`、`recyclerview`、`swiperefreshlayout`、`osmdroid`、`junit`（测试）。

- 网络用 JDK 的 `HttpURLConnection`、JSON 用 Android 自带的 `org.json`（见 `net/Http.kt` 与 `data/Json.kt`）：
  一共十来个接口、全是「点一下发一次」，引 Retrofit/OkHttp/Moshi 要多一份体积 + 一套 R8 规则 + 一个要跟着升的版本。
- release 开了 R8（`isMinifyEnabled` + `isShrinkResources`）。**风险点在 `proguard-rules.pro`**：
  `fragment_map.xml` 里按类名写死的 `org.osmdroid.views.MapView` 走的是 LayoutInflater 反射路径，
  名字被改掉会在**打开地图页时直接崩**，所以那里显式 keep 了地图相关包。

## 后端与域名（要改地址只改一处）

`app/src/main/java/top/qxwkstudio/travel/Api.kt` 是域名与接口路径的**唯一出处**：

- `ACCOUNT_BASE = https://account.qxwkstudio.top/api` —— 「Qxwk 通行证」，管登录 / 登出 / 身份。密码只发到这里，**不经过足迹后端**。
- `TRAVEL_BASE = https://travel.qxwkstudio.top/api` —— 本项目后端，管足迹数据（`/my-visits`、`/visits`、`/stats`）与地图边界（`/geo/{adcode}`）。

两个域名都是 HTTPS，所以清单里只有 `INTERNET` 权限、没有 `network_security_config` 的明文例外。
**阶段 2 会把 API 挪到 `api.travel.qxwkstudio.top`**（页面挪去 Pages）：届时只改 `TRAVEL_BASE` 这一行，
其余代码全部按相对路径拼；通行证那个域名不跟着迁移。

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

## 城市表（assets/cities.json 是生成物）

`app/src/main/assets/cities.json` **不要手改**：它由 `app/tools/gen-cities.mjs` 从
`frontend/cities.js`（名称 / 省份 / 坐标）与 `frontend/city-codes.js`（名称 → adcode）生成，
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
    │   ├── AndroidManifest.xml # 只有 INTERNET；四个 Activity
    │   ├── assets/cities.json  # 生成物（见上）
    │   ├── res/                # values / drawable / menu / layout
    │   └── java/top/qxwkstudio/travel/
    │       ├── Api.kt          # 域名与接口路径的唯一出处
    │       ├── logic/          # 纯 Kotlin：不 import android.*，所以能在 JVM 上单测
    │       ├── net/ data/      # HttpURLConnection / org.json / SharedPreferences / 各接口
    │       └── ui/             # Activity / Fragment / Adapter / 401 统一处理
    └── test/java/top/qxwkstudio/travel/logic/   # 单测：成就边界、visit_date、城市搜索
```

## 本机状态与登录态

token 存在 `MODE_PRIVATE` 的 SharedPreferences（`data/Store.kt`），与网页版放 localStorage 属同一级别，
**未加密**：安卓没有系统级加密存储，真机 root 后能被读走（与其它未加固 App 同级）。
兜底靠三层：清单里 `allowBackup=false`（云备份不带 token）、全站 HTTPS、日志不打印 token。
刻意**没用** `EncryptedSharedPreferences`（要求 targetSdk 更高，且换库要迁移存量数据，收益与改动不成比例）。

任何接口拿到 401 都走**同一个出口**（`ui/Session.kt`）：清本地 token → 提示一次 → 回登录页，
避免三个页面各弹一次错、或者用户被反复踢。**退出登录**无论通行证那边成功与否都清本地 token。