# 🗺️ City Footprint

> 把走过的城市点亮成自己的地图。

City Footprint 由三部分组成：

- **安卓 App**：离线记录行程、手动打卡、照片与成就；可选登录后同步。
- **网页**：新官网、全屏行程地图、用户查询、行程/打卡查询。
- **Cloudflare Worker + D1**：通行证登录、公开查询、可选同步。

## ✨ 功能

### 安卓 App

- 行程编辑：时间线、到访城市、交通、花销、备注、评分、照片
- 手动打卡：年 / 年月 / 年月日 / 时间不详四档日期精度
- 足迹地图：MapLibre + 本地 PMTiles 离线底图、城市边界、中文地名
- 成就：88 条，四大分类，按本地打卡回溯解锁
- 统计：城市、省份、次数、花销、城市排名
- 导出：ZIP（`data.json` + `photos/`）
- **可选同步**：不登录也能完整使用；登录后同步行程与手动打卡

### 网页

| 页面 | 路径 | 内容 |
| --- | --- | --- |
| 首页 | `/` | 软件介绍、版本号、国内网盘与 GitHub 下载 |
| 行程地图 | `/map/` | 全屏高德瓦片、城市边界、最近 10 条记录弹窗 |
| 用户查询 | `/user/` | 按通行证 UUID 查昵称、颜色、行程数、城市数 |
| 行程查询 | `/trip/` | 足迹 / 打卡切换，按 UUID 查详情 |

网页使用原生 HTML/CSS/JS + Leaflet。地图边界经 Worker 代理 DataV GeoAtlas，前端 IndexedDB 缓存 7 天。

### 可选同步

只有同时满足以下条件才同步：

1. 权限页「网络」开启
2. 网络子项「可选同步」开启
3. 已保存同步服务器地址与凭证

同步范围：

- 行程
- 手动打卡

不同步：

- 成就（本地按同步后的数据重算）
- 照片
- 昵称、主题色、语言等设置

行为：

- App 启动时自动同步
- 行程或打卡变化后延迟自动同步
- 同步页可手动同步
- 新设备首次同步拉取服务端快照，解决迁移数据没有 `sync_changes` 的问题
- 冲突弹窗对比本地/远端，由用户选择保留哪边
- 失败最多重试 3 次，仍失败则 Toast 提示

## 📁 项目结构

```text
.
├── app/                    # 安卓 App，详见 app/README.md
├── backend/                # Cloudflare Worker + D1，详见 backend/README.md
│   ├── src/
│   │   ├── worker.js       # 路由、公开接口、通行证登录
│   │   ├── sync.js         # 可选同步：register / push / pull
│   │   ├── auth.js         # 通行证 token 校验
│   │   └── city-coordinates.js
│   ├── migrations-sync/    # 同步库建表
│   ├── test/               # 同步参数、新设备快照测试
│   └── wrangler.toml
├── docs/                   # GitHub Pages 前端
│   ├── index.html
│   ├── map/
│   ├── user/
│   ├── trip/
│   ├── legal/            # 用户协议与免责声明
│   ├── site.css
│   └── site.js
├── scripts/                # 城市数据生成脚本
└── .github/workflows/      # Android 构建
```

## 🔌 主要接口

### 公开

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查 |
| GET | `/api/manifest` | App 版本清单 |
| GET | `/api/server` | 服务器名称、照片上传能力 |
| GET | `/api/map` | 地图城市、最近 10 条打卡/足迹 |
| GET | `/api/users/:uuid` | 用户公开资料与统计 |
| GET | `/api/trips/:uuid` | 行程详情 |
| GET | `/api/checkins/:uuid` | 打卡详情 |
| GET | `/api/geo/:adcode` | DataV 边界代理，缓存 7 天 |

### 登录与同步

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 通行证账号密码登录，返回 Bearer token |
| GET | `/api/auth/me` | 校验 token 并返回当前用户 |
| POST | `/api/sync/register` | 注册设备 |
| POST | `/api/sync/push` | 推送行程/打卡变更 |
| GET | `/api/sync/pull?since=` | 拉取增量；`since=0` 返回当前快照 |

同步使用 `revision` 乐观锁。服务端冲突返回 `conflict` 和远端数据，客户端弹窗选择，不自动覆盖。

## 🛠 开发

### 安卓

```powershell
$env:JAVA_HOME='C:\Code\.tool\jdk-17.0.2'
$env:ANDROID_HOME='C:\Code\.tool\android-sdk'
$env:GRADLE_USER_HOME='C:\Code\.tool\gradle-home'
cd app
C:\Code\.tool\gradle-8.2\bin\gradle.bat testDebugUnitTest
C:\Code\.tool\gradle-8.2\bin\gradle.bat assembleDebug
```

Debug APK：

```text
app/build/outputs/apk/debug/CityFootprint-arm64-v8a-debug.apk
```

### 后端

```powershell
cd backend
npm.cmd test
npx.cmd wrangler deploy
```

### 网页

直接打开 `docs/index.html`，或用静态服务器：

```powershell
npx.cmd -y serve docs
```

## 📦 发版

1. 修改根目录 `release.json`：

```json
{
  "versionName": "2.3.0",
  "versionCode": 20300,
  "importance": 0,
  "changelog": [
    "### 新增",
    "- 完整的发布说明"
  ],
  "cfNotes": [
    "用户能感知到的一条更新",
    "另一条简短说明"
  ]
}
```

2. 在 GitHub Actions → `Build, Release and Publish Android APK` → `Run workflow` 手动触发。
   只调试构建时使用 `Build Signed Debug APK`，不会发版或更新 D1；该流程也会恢复正式 keystore，调试包使用正式签名。

- `changelog`：GitHub Release 的完整更新日志
- `cfNotes`：写入 D1 `app_versions.notes` 的简短说明，用于 App 更新提示
- `importance`：0 普通 / 1 重要 / 2 强制，写入 `app_versions.importance`
- Gradle 直接读取版本号
- Workflow 创建 tag、GitHub Release，并把新版本写入 D1

D1 更新需要仓库 Secrets：`CLOUDFLARE_API_TOKEN`、`CLOUDFLARE_ACCOUNT_ID`。

`release.json` 只保存当前要发布的版本，不保留历史 CHANGELOG。

## 🌐 在线地址

- 网页：<https://travel.qxwkstudio.top>
- 后端：<https://api.travel.qxwkstudio.top>
- 通行证：<https://account.qxwkstudio.top>

---

📧 联系邮箱：QxwkStudio@outlook.com

版权所有 2026 青翔未阔工作室
