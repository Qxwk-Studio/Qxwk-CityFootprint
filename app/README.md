# City Footprint · 安卓端（2.0 离线版）

把「去过哪些城市」记下来、在地图上点亮它们的单机应用。**2.0 是离线单机**：数据全在本机
SQLite，不登录、不上传；整个 app 只有**一个**网络请求 —— 「检查更新」读版本清单。

工程口径与任务分解在**仓库外**的《2.0 工程口径》文档里（`C:\Code\.闲置文件\2.0-文档\`，含
`2.0-development.md` 与产品设计稿 `2.0.md`）—— 那两份是内部笔记，不入库；本文件是仓库内唯一
的安卓端说明。

## 包名与技术栈

| 项 | 值 |
| --- | --- |
| 包名 / applicationId | `top.qxwkstudio.travel`（debug 包加 `.dev` 后缀，见「发版」） |
| 语言与界面 | Kotlin + **XML View + viewBinding**（无 Compose） |
| SDK | compileSdk 34 / minSdk 24 / targetSdk 34，Java 17 |
| 构建 | AGP 8.2.2 + Kotlin 1.9.22 + Gradle 8.2 |

**工程布局：Gradle 根就是 `app/` 这一层**（`settings.gradle.kts` 在 `app/` 下，`com.android.application`
直接挂在根项目上，源集是 `app/src/main/...`，没有 `:app` 子模块）。所以所有命令都要**在 `app/` 目录里**跑：

```bash
cd app
./gradlew testDebugUnitTest    # 纯 JVM 单测（不需要设备）
./gradlew assembleDebug        # 需要本机有 Android SDK
```

## 依赖取舍

压得很小，每一处都有理由：

| 依赖 | 为什么 |
| --- | --- |
| `androidx.room` + KSP | 本地库是 2.0 的**唯一真相**。Room 在编译期校验 SQL、迁移有 schema 比对；KSP 版本必须与 Kotlin 完全对齐（`1.9.22-1.0.18`） |
| `kotlinx-serialization-json` | 序列化器编译期生成、无反射，R8 不需要 keep 规则；时间线事件用它存 JSON |
| `androidx.lifecycle` + 协程 | `runIo`（`ui/Coroutines.kt`）把「后台跑一段活、回主线程交结果」挂在页面生命周期上，页面销毁自动取消 |
| `org.maplibre.gl:android-sdk` | 地图。选它的唯一原因是**原生支持 PMTiles**（11.7.0 起），不需要自建读取器或本地 HTTP 服务 |
| Material Components | 按钮/开关/对话框/底栏；主题色跟着 `appAccent` 走 |
| **没有** Retrofit/OkHttp | 只剩一个 GET 请求，`HttpURLConnection` 足够（`net/Http.kt`） |
| **没有**图片加载库 | 只有一张本地头像，`BitmapFactory` + 手写缩放 |

## 数据层

四张表（`data/db/Entities.kt`，schema 快照在 `app/schemas/`，**要入库**）：

| 表 | 内容 |
| --- | --- |
| `trips` | 行程：标题、备注、时间线 JSON、起止时间与总花销（冗余列，列表排序不解析 JSON）、软删列、同步预留列 |
| `city_checkins` | 打卡：手动打卡 + 由行程派生的关联打卡共用一张表，`source` 区分；唯一索引 `(trip_uuid, city_name, source)` 是重算幂等的依据；另存可选的同行人 / 评分 / 照片路径（v2 迁移加的，`app/schemas/2.json`） |
| `achievement_states` | 成就状态：达成与否能重算，但**解锁时间重算不出来**，必须落表 |
| `app_settings` | 设备级键值：昵称、UUID、头像路径、徽章色 |

三条硬约定：

1. **时间一律存 UTC 毫秒**；时间线事件另存记录时的时区偏移 `tz`（分钟），否则跨时区旅行后
   「当时几点」还原不出来、排序又会错。
2. **派生数据不落表**：统计（城市/省份/次数/花销）与派生打卡都是现算的。
3. **派生打卡重算用物理删除**：唯一索引不认 `deleted_at`，软删的旧行会一直占着键。

### 时间线（`logic/Timeline.kt`）

一条行程 = **双层结构**：外层是到访城市（`segments`），内层是这座城市里的事件（`events`）。

- 内层只有两种类型：**活动**（做了什么）/ **交通**（怎么走，自由文本）；「到访」升成了外层城市，
  「前往」取消 —— 下一座城市就是转场；
- **城市不单独记时间**：一个城市段的时间范围取它第一条与最后一条事件，离开时间由下一座城决定；
- JSON 里的 `type` 与字段名是**数据格式契约**（改一个等于让存量行解不出来）；
- 时间必须非降序（相等可以），**跨城市也一样**；同一座城市只能出现一段；
- 每座城市至少一条事件（否则派生不出一条打卡）；
- 每个城市段可带**可选打卡信息**：同行人（独自/朋友/情侣/家人）、城市评分 1–5、一张本地照片；
  这些字段同时落到派生打卡上，所以手动打卡与行程里看到的是一套信息；
- 短文本（活动 / 交通）≤ 23 字，备注 ≤ 233 字（`logic/Limits.kt` 唯一出处）；
- `costCents` 用分：`null` = 没填，`0` = 合法的「花了 0 元」；每条事件还可写一条备注（≤ 233 字）。

### 派生打卡（`data/TripRepo.kt` + `logic/DerivedCheckin.kt`）

保存行程时，在**同一个事务**里按时间线重算关联打卡：同一座城市只取**首次到访**，
一城一条 `source = 派生` 的打卡。城市表里查不到的名字直接跳过（宁可少一条，不写脏数据）。

### 刷新链（`data/DataVersion.kt`）

五个 tab 用 add + hide/show 切换，隐藏的 Fragment 仍是 RESUMED —— 从表单页回到列表页
**收不到 `onHiddenChanged`**。所以写操作成功后 `DataVersion` 自增，各页在 `onResume` 比对版本号决定
要不要重读。只在事务成功后自增，回滚不白刷。

## 页面

底部五 tab：**主页 / 行程 / 地图 / 成就 / 我的**（`ui/MainActivity.kt` 的路由与本页顶栏文案）。

| tab | 内容 | 实现 |
| --- | --- | --- |
| 主页 | 时间跨度 / 打卡城市 / 打卡省份 / 次数最多 / 总花销 + 城市排名（次数↔时间） | `HomeFragment` |
| 行程 | 分段控件「打卡 (N) / 足迹 (N)」；三种卡片（足迹蓝 / 独立打卡绿 / 关联打卡灰） | `TripsFragment` |
| 地图 | MapLibre 离线底图 + 点亮去过的城市 + 点击只读面板 | `MapFragment` |
| 成就 | 54 条成就，分类胶囊、解锁日期、进度条 | `AchievementsFragment` |
| 我的 | 头像 / 昵称 / UUID / 主题色 / 徽章色 / 外观 / 检查更新 / 版本 | `MineFragment` |

四个独立页面：`CheckinEditActivity`（手动打卡，四档日期精度）、`TripEditActivity`（行程编辑器，
时间线增删改、正序校验、返回拦截）、`CityPickerActivity`（选城，分组 + 搜索）、以及主壳。

入口：主页右上角 **「+」** = 城市打卡 / 添加足迹（自动记录在 2.1）。

## 地图（`ui/MapFragment.kt` + `logic/MapStyle.kt` + `data/BasemapStore.kt`）

- **底图**：`assets/basemap/cn-z0-6.pmtiles`（Protomaps 中国区 z0–6，5.8 MB，ODbL）。
  MapLibre 的 PMTiles 读取在原生层，**只认 `pmtiles://file://…`，不支持 `asset://`** ——
  所以首次进地图页时把资产拷到 `filesDir`（`BasemapStore.ensure`，按文件大小判断是否要重拷）。
- **style** 运行时拼（`MapStyle.json`）：底色 / 陆地 / 用地 / 水系 / 道路 / 边界六层，
  颜色取主题色板；**PMTiles 源写在 `url` 字段**，写进 `tiles` 数组会被当成瓦片模板、静默空白。
- **点亮**：把判定用的那份边界（`CityShapes.shapesFor`）拼成 GeoJSON 叠填充 + 描边，
  另加一层城市圆点（来自城市表，不依赖边界解析）兜底。
- **交互**：点城市 → 底部只读面板（该城市打卡列表）；右下角定位按钮判城后把镜头挪过去。
- 右下角 `© OpenStreetMap` 署名是 ODbL 的强制要求，别删。

已知限制：底图只到 z6（再放大底图是空的，叠加层不受影响）；没有地名标注（需要 glyphs 字体包）；
`ndk.abiFilters` 只留 arm64-v8a + armeabi-v7a（四个 ABI 全打包 APK 会从 8 MB 涨到 50 MB）。

## 城市边界与判定（`logic/CityShapes.kt`、`logic/CityLocator.kt`）

- 数据是**生成物**（不要手改）：`assets/data/city_boundaries.geojson`（370 城，已从 **GCJ-02 转 WGS-84**、
  简化 20%，4.6 MB）与 `assets/data/provinces.geojson`（省界，背景线）。
  重跑命令见「assets 生成物」一节。系统定位给的是 WGS-84，不转的话判定整体偏 500–640 m。
- **解析是手写流式扫描器**：4.6 MB、四十多万个坐标点，用 `JsonElement` 建语法树在手机上会 OOM，
  而失败被静默吞成「空边界」（症状：地图面数 0、定位退化成最近城市中心）。现在边扫边填扁平数组。
- 判定：0.5° 网格粗筛 → 包围盒 → 射线法（含洞）→ 都不中时按**最近城市中心**兜底，上限 50 km
  （海上/国外返回「判不到」让用户手选）。台湾岛内一律判成「台湾」（DataV 整岛只有一个 adcode）。
- `AssetPathsTest` 直接断言三份资产在仓库里的路径存在 —— 路径常量写错会立刻红，
  这类「路径错→静默降级」的坑不该靠真机发现第二次。

## 成就（`logic/Achievements.kt`）

54 条，四类（足迹丰碑 / 巡游四方 / 城市打卡 / 极限挑战），判定输入只有本地打卡的城市集合。
三种规则：城市数里程碑、全部去过、任一去过。

**回溯解锁日期**：按时间升序喂打卡，某条成就第一次成立时记下那一刻 —— 重装、清库、以后导入备份后
重算，得到的仍是当初那条打卡的日期，而不是「今天」。结果落 `achievement_states`，写入时**保留已有的
`unlocked_at`**（后来删记录导致暂时不满足，不该抹掉已点亮的成就）。

1.x 把这套放后端（`backend/src/achievements.js`），2.0 搬到本地时逐条比对过（数量/顺序/名称/描述 0 差异）。

## 统计口径（`logic/Stats.kt`）

- 打卡城市 / 省份：去重；省份为空的不计入。
- 时间跨度：只统计**完整日期**的打卡（只记得年/年月/记不清的记录带占位日期，混进去会把跨度起点拉偏）。
- 次数最多：按**城市**计次，并列取名字字典序靠前。
- 总花销：各行程花销之和，只出现在主页那张卡与行程卡上；**城市排名不按花费**（一笔行程可能去多城，
  花销拆到城市要么重复计入、要么靠分摊猜）。

## 个性化

- **外观**：跟随系统 / 浅色 / 深色。应用点在进程最早处（`CityFootprintApp`），晚一步会让先起来的页面
  按系统默认渲染一帧再重建 —— 有设置项的人每次冷启动都会看到闪一下。
- **主题色**：6 个预设（蓝/绿/紫/橙/玫瑰/青），走 `ThemeOverlay.App.Accent.*` 覆盖 `appAccent` 与
  `colorPrimary/colorSecondary`，所以按钮、开关、输入框、底栏选中态会一起换色；代码里画的强调色
  走 `ui/Palette.accent()`。
  已知限制：主色的「浅底」（`accent_light`）与主色系小字仍是固定淡蓝，换紫/橙时这一档略偏蓝。
- **徽章色**：头像圆底与「我的」页色点，8 个预设。
- **头像**：系统照片选择器（不需要读存储权限），缩到 512px 存 JPEG 进 `filesDir`，原图不留。
- 外观与主题色存 `SharedPreferences`（Activity 在 `setContentView` 前必须同步读），
  昵称/头像/徽章色存 `app_settings`（跟着数据走）。

## 检查更新

`GET /api/manifest`（`Api.VERSION_MANIFEST`，后端在 `backend/`）只读 `android` 那一节：
比 `version_code`（整数，**不是**版本名），严格大于当前 `BuildConfig.VERSION_CODE` 才算有新版本。
发新版时**往 `cf_app_version` 插一行新记录**（不是改旧行），否则永远看不到更新。
App 内不做下载安装，点「前往下载」跳系统浏览器。

## assets 生成物（不要手改）

| 文件 | 来源 | 重跑命令 |
| --- | --- | --- |
| `assets/cities.json` | `docs/cities.js` + `docs/city-codes.js` | `node app/tools/gen-cities.mjs` |
| `assets/data/city_boundaries.geojson` | DataV 逐市边界 → GCJ→WGS → 简化 20% | `node app/tools/build-city-geo.mjs` |
| `assets/data/provinces.geojson` | DataV `100000_full` → 同上 | `node app/tools/build-provinces.mjs` |
| `assets/basemap/cn-z0-6.pmtiles` | Protomaps 中国区 z0–6（外部下载，非本仓库生成） | 见 2.0 工程口径文档 §7.1 |

前三个脚本都是**可重复运行、失败不写文件**的；中间产物落在 `app/tools/.cache` 与 `.out`（已 gitignore）。

## 测试

```bash
cd app && ./gradlew testDebugUnitTest
```

46 条纯 JVM 测试（不需要设备）：时间线校验与编解码、派生打卡、统计口径、城市判定（合成数据 + 真实数据
抽查：天安门→北京且落在面内、外滩→上海、台北→台湾、远海→null）、成就（54 条数量 / code 唯一 / 回溯 /
进度封顶）、地图 style 字段契约、assets 路径存在性。

## 签名与发版

1. keystore 与口令在仓库 Secrets（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），
   离库原件在 `C:\Code\.闲置文件\签名密钥-足迹.jks`。本地想验签名时把这四项按 `CF_*` 环境变量喂给
   Gradle（`CF_KEYSTORE_FILE` / `CF_KEYSTORE_PASSWORD` / `CF_KEY_ALIAS` / `CF_KEY_PASSWORD`），
   缺它们时 release 出的是 `*-release-unsigned.apk`（能编译、装不上）。
2. Actions → `Build Android APK` → `Run workflow`，填 `version_name` 与 `version_code`：
   CI 用 sed 覆盖 `app/build.gradle.kts` 的两行，所以**版本段从 20000 起**（本地默认就是
   `20000` / `2.0.0`），必须大于线上 1.x 的最高值。CI 会跑单测 → `assembleRelease` → 改名上传；
   缺 keystore 直接失败，签名没生效也会因找不到 `*-arm64-v8a-release.apk` 而失败（故意的，
   绝不把未签名包当正式包传）。
3. **按 ABI 拆包**（`splits { abi { … } }`）：产物是 `CityFootprint-<abi>-release.apk`，
   CI 只取 **arm64-v8a**（约 22.7 MB）上传；armeabi-v7a 那份约 19.2 MB，留给 32 位设备。
   注意 AGP 的 splits 是全局的，**debug 也会拆**：本机装包要取
   `app/build/outputs/apk/debug/CityFootprint-arm64-v8a-debug.apk`。
4. **debug 包带 `.dev` 后缀**（`top.qxwkstudio.travel.dev`），可以和线上 1.x 并排装 ——
   签名不同，同包名是装不上的。
5. 2.0 正式顶替 1.x 时：`applicationId` 不变、签名与 1.x 一致、`versionCode` ≥ 20000。
   **1.x 的历史数据不会自动迁过来**（数据在后端 D1，2.0 是纯本地），迁移方案见
   2.0 工程口径文档 §2/§8。

## 已知限制 / 2.1 计划

- 自动记录（前台服务 + 通知 + 草稿）整块推后；「+」菜单第三项目前置灰。
- 分享按钮已摆好，点了只提示「功能即将推出」。
- 数据导出/导入未做：**换手机或卸载会丢数据**（这是当前最大的缺口）。
- 底图只到 z6、没有地名；主题色的浅底一档仍固定淡蓝。
- 1.x 的后端（Cloudflare Worker + D1）仍存在，网页版还用它；2.0 只调 `/api/manifest`。

## 目录

```
app/
├── build.gradle.kts            # 根项目：版本号（CI 注入）/ 签名 / R8 / abiFilters / 依赖
├── settings.gradle.kts         # 单模块工程，没有 include(":app")
├── schemas/                    # Room schema 快照（入库，迁移的依据）
├── proguard-rules.pro
├── tools/                      # 生成 assets 的脚本（cities / 城市边界 / 省界）
└── src/
    ├── main/
    │   ├── AndroidManifest.xml # 只有 INTERNET + ACCESS_COARSE_LOCATION；四个页面
    │   ├── assets/              # cities.json / data/*.geojson / basemap/*.pmtiles（生成物）
    │   └── java/top/qxwkstudio/travel/
    │       ├── Api.kt           # 域名与接口路径的唯一出处（现在只剩 manifest）
    │       ├── CityFootprintApp.kt  # MapLibre 初始化 / 外观 / 城市表预热
    │       ├── logic/           # 纯 Kotlin、可 JVM 单测：时间线 / 判城 / 统计 / 成就 / style / 日期
    │       ├── data/            # Room 实体与 DAO、仓库、偏好、底图缓存、单次 HTTP
    │       └── ui/              # Activity / Fragment / Palette / Notify / EdgeToEdge / Locate
    └── test/                    # 46 条 JVM 测试
```
