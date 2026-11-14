# City Footprint · 安卓端

把去过的城市记下来、在地图上点亮它们的应用。默认是离线单机：数据保存在本机 SQLite，不登录、不上传。

当前版本：**2.3.2**（versionCode 20302）。

2.2.0 起提供**可选同步**：默认关闭，在「权限管理 → 网络 → 可选同步」开启后，才会登录通行证账号并同步行程、手动打卡。

## 包名与技术栈

| 项 | 值 |
| --- | --- |
| 包名 / applicationId | `top.qxwkstudio.travel`（debug 包加 `.dev` 后缀） |
| 语言与界面 | Kotlin + **XML View + viewBinding**（无 Compose） |
| SDK | compileSdk 34 / minSdk 24 / targetSdk 34，Java 17 |
| 构建 | AGP 8.2.2 + Kotlin 1.9.22 + Gradle 8.2 |

Gradle 根就是 `app/` 这一层（`settings.gradle.kts` 在 `app/` 下，`com.android.application`
直接挂在根项目上，源集是 `app/src/main/...`，没有 `:app` 子模块）。命令都要在 `app/` 目录里跑：

```bash
cd app
./gradlew testDebugUnitTest    # 纯 JVM 单测，不需要设备
./gradlew assembleDebug        # 需要本机有 Android SDK
```

## 依赖取舍

| 依赖 | 用途 |
| --- | --- |
| `androidx.room` + KSP | 本地数据库；编译期校验 SQL，schema 快照在 `app/schemas/` |
| `kotlinx-serialization-json` | 时间线与边界 JSON；编译期生成序列化器，无反射 |
| `androidx.lifecycle` + 协程 | `runIo` 把后台任务挂到页面生命周期，销毁自动取消 |
| `org.maplibre.gl:android-sdk` | MapLibre 12.3.1，原生读取 PMTiles |
| Material Components | 按钮、开关、对话框、底栏 |
| 无 Retrofit/OkHttp | 接口很少，单次 `HttpURLConnection` 足够 |
| 无图片加载库 | 本地照片用 `BitmapFactory` + 手写缩放 |

## 数据层

数据库版本 **7**，四张表（`data/db/Entities.kt`）：

| 表 | 内容 |
| --- | --- |
| `trips` | 行程：标题、备注、时间线 JSON、起止时间、总花销、软删列、同步预留列 |
| `city_checkins` | 打卡：手动打卡 + 行程派生打卡共用一张表，`source` 区分；唯一索引 `(trip_uuid, segment_index, source)`；同行人、评分、照片、金额、停留天数、`created_at` |
| `achievement_states` | 成就状态：解锁时间必须落表，重算保留已有时间 |
| `app_settings` | 设备级键值：昵称、UUID、头像路径、徽章色等 |

同步元数据不建表，存 SharedPreferences：每条记录的 `revision`、上次同步修改时间、拉取游标、待处理冲突和上次同步结果。Token 也只存 SharedPreferences，不进入 `app_settings` 和导出包。

约定：

1. **时间一律存 UTC 毫秒**；时间线事件另存记录时的时区偏移 `tz`（分钟）。
2. **统计现算**：城市、省份、次数、花销不落冗余表。
3. **软删**：业务表带 `deleted_at`；派生打卡重算时先用墓碑标记旧行，再按城市段复用。
4. **改时间线必须同步更新 `trips` 的冗余列**，写入只走 `TripDao.upsert`。

### 时间线

`logic/Timeline.kt`：一条行程分为到访城市（`segments`）与城市内事件（`events`）。

- 事件只有活动 / 交通两种；到访城市独立成段。
- 城市时间不单独记录，取该城市第一条与最后一条事件；离开时间由下一座城市决定。
- 时间非降序，跨城市也一样；每座城市至少一条活动或交通。
- 城市段可带同行人、评分、照片、到达方式；这些字段同时落到派生打卡。
- 短文本 ≤ 23 字，备注 ≤ 233 字；金额用分，`null` 表示未填，`0` 是合法值。
- JSON 字段名是数据格式契约，改动会导致存量数据无法解析。

### 派生打卡

保存行程时，同一事务内按时间线重算关联打卡：同一座城市只取首次到访，一城一条
`source = 派生` 的打卡。城市名在本地城市表里查不到时跳过，不写脏数据。

派生打卡的 uuid 由「行程 uuid + 城市段序号」确定性生成；重算时保留墓碑行占住唯一索引。

### 刷新链

五个 tab 使用 add + hide/show 切换，隐藏页面仍是 RESUMED，收不到 `onHiddenChanged`。
写操作成功后 `DataVersion` 自增，各页在 `onResume` 比对版本号重读；事务回滚不增加版本。

## 页面

底部五 tab：**主页 / 行程 / 地图 / 成就 / 我的**。

| tab | 内容 |
| --- | --- |
| 主页 | 时间跨度、打卡城市/省份、次数最多、总花销、城市排名（次数 / 金额 / 时长 / 评分） |
| 行程 | 分段控件「打卡 (N) / 足迹 (N)」；足迹、独立打卡、关联打卡三种卡片 |
| 地图 | 离线底图、已点亮城市、中文地名、点击城市看最近 3 条打卡 |
| 成就 | 88 条成就，四大分类、进度条、解锁日期，中英文文案 |
| 我的 | 头像、昵称、UUID、主题色、徽章色、外观、语言、权限管理、存储空间、导出数据、可选同步、检查更新 |

独立页面：

- `CheckinEditActivity`：手动打卡，四档日期精度、备注、金额、停留天数、评分、同行人、照片。
- `TripEditActivity`：行程编辑器，时间线增删改、正序校验、返回拦截。
- `CityPickerActivity`：城市分组浏览与中英文搜索。
- `PermissionsActivity`：必要权限与可选权限。
- `StorageActivity`：存储占比、分类大小、清理照片与缓存。

入口：主页右上角「+」= 城市打卡 / 添加足迹。

## 地图

`ui/MapFragment.kt` + `logic/MapStyle.kt` + `data/BasemapStore.kt`。

- **底图**：`assets/basemap/cn-z0-6.pmtiles`（Protomaps 中国区 z0–6，约 6.6 MB，覆盖南海，ODbL）。
  MapLibre 只认 `pmtiles://file://…`；首次进地图页时把 asset 拷到 `filesDir`。
- **style**：运行时拼底色、陆地、用地、水系、道路、边界六层，颜色取主题色板，深色自动换。
  PMTiles 源必须写在 `url` 字段，写成 `tiles` 模板数组会静默空白。
- **点亮**：把城市边界拼成 GeoJSON 叠填充与描边，另加城市中心圆点兜底。
- **中文地名**：`ui/CityLabelsView` 用原生 Canvas 绘制，不依赖 glyphs 字体包。
  低缩放只显示主要城市，z4.2 以上逐步显示全部 384 城，并做文字包围盒碰撞剔除；
  去过的城市始终优先显示，颜色取用户徽章色。
- **交互**：点城市 → 底部只读面板，列出该城市最近 3 条打卡；定位按钮判城后移动镜头。
- 右下角 `© OpenStreetMap` 署名是 ODbL 要求，不要删。
- 地图使用 TextureView 渲染；`splits { abi }` 只打 arm64-v8a 与 armeabi-v7a。

## 城市边界与判定

`logic/CityShapes.kt`、`logic/CityLocator.kt`。

- 数据是生成物：`assets/data/city_boundaries.geojson`（370 城，已从 GCJ-02 转 WGS-84、
  简化 20%）与 `assets/data/provinces.geojson`（省界背景线）。
- 解析用手写流式扫描器，避免 4.6 MB JSON 建语法树导致 OOM。
- 判定：0.5° 网格粗筛 → 包围盒 → 射线法（含洞）→ 最近城市中心兜底，上限 50 km。
- 台湾岛内统一判为「台湾」，因为边界数据整岛只有一个 adcode。
- `AssetPathsTest` 断言三份资产路径存在，避免路径错误被静默吞掉。

## 成就

`logic/Achievements.kt`：88 条，四类——足迹丰碑 / 城市打卡 / 巡游四方 / 极限挑战。

规则：城市数里程碑、打卡数、行程数、全部去过、任一去过。

**回溯解锁日期**：按时间升序喂打卡，成就第一次成立时记下那一刻；重算时保留已有
`unlocked_at`，后来删除记录不会抹掉已点亮的成就。

`logic/AchievementsEnglish.kt` 提供 88 条英文名称、描述与分类标题；英文界面只影响显示，
不影响判定。成就页支持分类胶囊、进度条与解锁日期。

## 统计口径

`logic/Stats.kt`。

- 打卡城市 / 省份去重；省份为空的不计入。
- 时间跨度只统计完整日期的打卡；只记得年/年月/记不清的记录不参与跨度。
- 次数最多按城市计次，并列取名字字典序靠前。
- 总花销为各打卡金额合计。
- 城市排名支持次数、金额、时长、评分四个维度；金额与时长来自打卡镜像字段。

## 我的

- **资料**：头像圆底、昵称、UUID；昵称从预设里选，随语言显示中英文，但库里存中文规范值。
- **外观**：跟随系统 / 浅色 / 深色，设置在进程最早处应用。
- **主题色**：跟随系统、红、橙、绿、青、蓝、紫；通过 `ThemeOverlay.App.Accent.*` 覆盖
  `appAccent` 与主色，按钮、开关、输入框、底栏选中态一起变色。
- **徽章色**：8 个预设；用于头像圆底、我的页色点和地图上已点亮城市的文字。
- **语言**：跟随系统 / 简体中文 / English。资源按页面拆分在 `values/strings_*.xml` 与
  `values-en/strings_*.xml`；切换后写入 Prefs，Toast 提示并退出进程，下次启动在
  `LocalizedActivity.attachBaseContext` 阶段应用新语言。
- **权限管理**：
  - 必要权限：存储（Android 9 及以下保存照片）。
  - 可选权限：定位、通知、网络；右侧为开关。
  - 网络开关默认关闭，控制检查更新与同步。
  - 网络开启后显示子项「可选同步」，默认关闭；开启后才显示「我的 → 可选同步」入口。
- **存储空间**：顶部圆环显示总占用与各类占比，下面按应用本体、离线地图、行程数据、
  行程照片、缓存分项显示，每项带对应颜色圆点；支持清理无引用照片与缓存。
- **导出数据**：ZIP（`data.json` + `photos/`），包含行程、打卡和照片，不包含设置与成就；
  保存到系统下载目录，格式带版本号。
- **检查更新**：读取版本清单，比较 `version_code`。

## 可选同步

入口：**权限管理 → 网络 → 可选同步**。关闭时取消待执行同步，并隐藏「我的 → 可选同步」。

同步页：

- 服务器地址：默认空，只接受 HTTPS
- 读取服务器名称与照片上传能力
- 同步凭证：凭证（账号）+ 密钥（密码）
- 登录成功后用 Android Keystore 加密保存凭证；Token 过期后自动填充，按钮变为「一键登录」；退出登录会清除凭证
- 上次同步时间、上传/拉取/冲突数
- 立即同步

同步范围：

- 行程、手动打卡双向同步
- 成就、照片、设置不同步

自动同步：

- App 启动
- 行程/打卡新增、修改、删除后延迟约 2.5 秒
- 仅当网络、同步开关、凭证都就绪

冲突：

- 推送遇到 revision 冲突时不覆盖本地
- 同步页弹窗展示本地/远端摘要
- 用户选择「保留本地」或「使用远端」
- 未处理冲突会保留，后台不会覆盖对应记录

失败处理：

- 最多 3 次，间隔 1s / 2s
- 最终失败 Toast，并写入上次同步卡片

账号：

- 登录/退出都会弹窗说明本地数据处理方式
- 支持保留本地数据或清空行程、打卡、成就
- 换账号会清空同步元数据，避免把旧账号数据推到新账号

新设备：

- 首次 `since=0` 拉取服务端快照
- 解决迁移数据没有写 `sync_changes`、增量拉不到的问题

## 检查更新

`GET /api/manifest`（`Api.VERSION_MANIFEST`）只读 `android` 节点，比较整数 `version_code`；
严格大于当前 `BuildConfig.VERSION_CODE` 才提示新版本。App 内不下载安装，点「前往下载」
跳系统浏览器。网络开关关闭时不会发起请求。

## assets 生成物

| 文件 | 来源 | 重跑命令 |
| --- | --- | --- |
| `assets/cities.json` | `docs/cities.js` + `docs/city-codes.js`，含 `nameEn` / `provinceEn` | `node app/tools/gen-cities.mjs` |
| `assets/data/city_boundaries.geojson` | DataV 逐市边界 → GCJ→WGS → 简化 20% | `node app/tools/build-city-geo.mjs` |
| `assets/data/provinces.geojson` | DataV `100000_full` → 同上 | `node app/tools/build-provinces.mjs` |
| `assets/basemap/cn-z0-6.pmtiles` | Protomaps 中国区 z0–6（外部下载） | — |

生成脚本可重复运行、失败不写文件；中间产物在 `app/tools/.cache` 与 `.out`。`gen-cities.mjs`
会保留旧输出里的 `nameEn` / `provinceEn`，避免重新生成后丢英文地名。

## 测试

```bash
cd app && ./gradlew testDebugUnitTest
```

当前纯 JVM 测试全部通过，覆盖：

- 时间线校验与编解码
- 派生打卡
- 统计口径
- 城市判定
- 成就数量与回溯
- 地图 style 字段契约
- assets 路径存在性
- 同步判定（新增/修改、墓碑、游标、冲突状态）

## 签名与发版

1. keystore 与口令在仓库 Secrets：`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
   `KEY_PASSWORD`。本地验证签名时按 `CF_KEYSTORE_FILE` / `CF_KEYSTORE_PASSWORD` /
   `CF_KEY_ALIAS` / `CF_KEY_PASSWORD` 环境变量传给 Gradle；缺这些变量时 release 产物是
   `*-release-unsigned.apk`。
2. 修改根目录 `release.json` 的 `versionName` / `versionCode` / `importance` / `changelog`（完整 Release 说明）/ `cfNotes`（App 更新提示）。
   GitHub Actions → `Build, Release and Publish Android APK` → `Run workflow`（**仅手动触发，无版本输入**）。
   只构建 debug APK 时使用 `Build Signed Debug APK`；该流程会恢复正式 keystore，并用 `-PuseReleaseSigningForDebug=true` 给调试包使用正式签名。
   Gradle 直接读取 release.json 获取版本号，无需修改 `app/build.gradle.kts`；跑单测 → `assembleRelease`
   → 上传 arm64-v8a 包 → 创建 `v<version>` tag 与 GitHub Release（正文取 `changelog`）
   → 写入 D1 `app_versions`（`notes` 取 `cfNotes`）。D1 步骤需要 Secrets `CLOUDFLARE_API_TOKEN` / `CLOUDFLARE_ACCOUNT_ID`。
   缺 keystore 或签名未生效会直接失败。
3. 按 ABI 拆包：产物名为 `CityFootprint-<abi>-release.apk`，CI 取 **arm64-v8a**；
   debug 也拆包，本机安装取 `app/build/outputs/apk/debug/CityFootprint-arm64-v8a-debug.apk`。
4. debug 包使用 `top.qxwkstudio.travel.dev`，可与正式包并排安装。


## 目录

```
app/
├── build.gradle.kts            # 版本号、签名、R8、ABI 拆包、依赖
├── settings.gradle.kts         # 单模块工程
├── schemas/                    # Room schema 快照
├── proguard-rules.pro
├── tools/                      # cities / 城市边界 / 省界生成脚本
└── src/
    ├── main/
    │   ├── AndroidManifest.xml # INTERNET、粗略定位、旧版存储、通知权限
    │   ├── assets/              # cities.json / data/*.geojson / basemap/*.pmtiles
    │   └── java/top/qxwkstudio/travel/
    │       ├── Api.kt           # 域名与接口路径
    │       ├── CityFootprintApp.kt  # MapLibre / 外观 / 语言 / 城市表预热
    │       ├── logic/           # 纯 Kotlin：时间线 / 判城 / 统计 / 成就 / style / 日期
    │       ├── data/            # Room、仓库、偏好、同步、导出、单次 HTTP
    │       └── ui/              # Activity / Fragment / Palette / 组件
    └── test/                    # JVM 测试（含同步判定）
```
