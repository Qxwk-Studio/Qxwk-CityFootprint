# 🗺️ City Footprint

> 记录每个人去过的城市，在足迹地图上点亮属于自己的颜色。
>
> 登录通行证后把自己去过的城市打点在地图上，和朋友们一起拼出一张五彩斑斓的足迹大地图。

## ✨ 功能一览

### 🗺️ 足迹大地图 (`index.html`)
- **公开浏览**：所有访客无需登录即可查看完整地图
- **专属颜色**：每个用户一种标记色（颜色由通行证统一分配），同城多点自动散开
- **按人筛选**：点击图例中的昵称，只看某一个人的足迹
- **私密行程**：本人登录时可见自己的不公开足迹（弹窗带 🔒），他人不可见
- **管理员视图**：管理员可查看所有人的行程（含私密），右上角显示 👑 标识

### 👤 个人中心 (`account.html`)
- **本站登录表单**：未登录视图直接给出「昵称或邮箱 + 密码」表单，提交后由本站前端 JS 跨域调通行证 `/api/login` 换取 token（密码只从浏览器发给通行证，不经过本站服务器）；下方保留「打开通行证」外链用于注册 / 找回密码
- **本地会话**：登录成功把 token 存 localStorage（键 `qxwf_token`），再调本站 `/api/me` 把用户信息写入 `qxwf_user` 缓存，后续访问免登录
- **账户信息**：专属颜色大头像（**直接使用通行证返回的 avatar URL，前端不再自己计算邮箱哈希**）、UID、注册时间、管理员徽章
- **通行证中心入口**：个人中心右列提供通行证外链卡，方便改昵称 / 颜色 / 密码、生成邀请码
- **退出登录**：清掉本地 token 后，顺带跨域 POST 通行证 `/api/logout` 撤销该会话（请求失败不影响本地登出）

### ✈️ 足迹管理 (`visits.html`)
- **足迹增删改**：添加 / 修改 / 删除自己的城市足迹（城市联想、时间/备注/私密开关）
- **足迹统计**：去过城市数、覆盖省份、足迹总数、最早·最近行程
- **成就系统**：4 大分类（足迹丰碑 / 巡游四方 / 城市打卡 / 极限挑战），自动判定解锁，成就卡片整体折叠
- **不公开行程**：可勾选"仅自己可见"，不出现在公开地图与统计中

### 📊 全站统计 (`stats.html`)
- 总行程、总城市、总用户、覆盖省份（4 卡网格排版）
- 城市次数排名 Top30、成就达成人数
- 管理员登录时统计全部行程（含私密）

### 🌓 夜间模式
- **全站 6 页面**支持：默认跟随系统深浅色自动切换，导航栏可手动切换（太阳/月亮按钮），选择存 localStorage
- 深色下地图瓦片自动暗化（滤镜），UI（图例 / FAB / 弹窗 / 表单 / 统计卡）全部深色适配

### 🛡️ 安全设计
- **统一认证**：本站不再持有密码 / 会话，登录态完全由通行证 account.qxwkstudio.top 签发与撤销
- **密码不经本站服务器**：登录由前端 JS 跨域直调通行证 `/api/login`，密码只发往通行证，本站后端全程不接触
- **Bearer Token**：业务请求带通行证下发的 token，本站后端拿 token 去问通行证 `/api/me` 验证（**无缓存**，每个业务请求都会跨站验证一次）
- **私密数据**：不公开行程在接口层过滤，仅本人（或管理员）可见

## 🔗 通行证登录接入说明

本站是 [Qxwk 通行证](https://account.qxwkstudio.top/) 的接入站点之一。通行证侧的**跨站 SSO / 跳转授权已整条下线**（`/?redirect=` 入口、URL 片段交付 `#_t=<token>`、授权确认页与 `/api/sso/*` 接口均已删除），本站不再有「跳过去登录再回跳落地」的流程，改为**本站前端直调通行证登录接口**换 token：

1. 用户在 `account.html` 未登录视图的表单填「昵称或邮箱 + 密码」并提交，`docs/app.js` 的 `passportLogin(nickname, password)` 跨域 POST 通行证 `https://account.qxwkstudio.top/api/login`，body 为 `{nickname, password, client: location.origin}`（`client` 供通行证侧 `apps` 白名单识别来源站点，未登记也能登录）
2. 通行证校验通过返回 `token`，本站前端把 token 存进 localStorage（键 `qxwf_token`），再调本站 `/api/me` 把用户信息写入 `qxwf_user` 缓存；失败则把错误信息显示在表单下方
3. 后续业务请求带 `Authorization: Bearer <token>`，本站后端 `resolveViewer()` 拿 token 去通行证 `/api/me` 验证（**无缓存**），再**按通行证 userId 映射**到本地 `users` 表：先按 `users.passport_id` 查行 → 查不到时把「同昵称且 `passport_id IS NULL`」的存量行回填 `passport_id` 认领（保住该行已有的足迹与 `is_admin` 标志）→ 仍查不到才 `INSERT OR IGNORE` 新建；昵称 / 颜色 / 头像每次访问同步通行证，昵称撞上本站 `users.nickname` 唯一约束时保留本站旧昵称、只同步颜色与头像
4. 退出登录：`logout()` 清掉本地 token 后，跨域 POST 通行证 `/api/logout`（带 `Authorization: Bearer <token>`）撤销该会话；请求失败不影响本地登出

> **已知限制**：本地昵称与通行证昵称已经不一致、且该用户此前从没用过新版流程的存量行无法被认领——登录时会新建一行，旧行及其足迹需人工在库里合并。

## 🧱 技术栈

- **运行时**：Cloudflare Workers（只跑接口，不再托管静态资源）
- **数据库**：D1（SQLite，Cloudflare 原生）
- **认证**：Qxwk 通行证统一登录（本站前端直调通行证 `/api/login` 换 token + Bearer Token 跨站校验，无缓存）
- **前端**：原生 HTML / JS + [Leaflet](https://leafletjs.com/) 地图库
- **托管平台**：前端 **GitHub Pages**（发布仓库的 `docs/` 目录，域名 `travel.qxwkstudio.top`）+ 后端 **Cloudflare Workers**（域名 `api.travel.qxwkstudio.top`，部署命令在 `backend/` 目录执行 `npx wrangler deploy`）

## 📁 项目结构

仓库根目录按「前端 / 后端 / 安卓 app」三分（前端目录叫 `docs/` 是 GitHub Pages 的要求 —— 它只允许发布仓库根或 `/docs`）：

```
├── docs/                   # 静态前端（GitHub Pages 就发布这个目录）
│   ├── vendor/             # 自托管前端依赖（Leaflet JS + CSS + 标记图标，避免外链与 CORS）
│   ├── index.html          # 足迹大地图
│   ├── account.html        # 个人中心（本站登录表单 + 资料卡）
│   ├── visits.html         # 足迹管理（增删改/统计/成就）
│   ├── setup.html          # 欢迎动画页（嵌入 account 未登录左侧，跟随主题同步）
│   ├── stats.html          # 全站统计
│   ├── news.html           # 公告与更新日志
│   ├── achievements.js     # 成就定义与判定
│   ├── app.js              # API 客户端 + 通行证登录/会话（直调 /api/login、401 兜底） + setAvatarFromUrl()（头像渲染）
│   ├── cities.js           # 国内地级市坐标数据
│   ├── city-codes.js       # 城市 adcode（地图边界用）
│   ├── favicon.webp        # 站点图标（lossless webp，与安卓启动图标同一张源图，见 app/README.md）
│   └── robots.txt          # 爬虫规则（屏蔽登录页与足迹管理页；接口在另一个域上，这里管不到）
├── backend/                # 后端（Cloudflare Worker + D1）；后端命令都在这个目录里执行
│   ├── src/
│   │   ├── worker.js       # Worker 入口（/api/* 接口 + CORS；静态资源回退已删）
│   │   └── lib.js          # 通行证 token 验证 + 本地用户映射 + 工具
│   ├── migrations/
│   │   └── 0001_init.sql   # 建表：users（两站共享）/ cf_visits（本站独占，含 is_private）
│   └── wrangler.toml       # Worker 配置（只有 D1 绑定；静态资源段已删 —— 页面在 GitHub Pages）
├── app/                    # 安卓 app，见 app/README.md（Kotlin + XML View/viewBinding，原生 osmdroid 地图，不用 WebView）
├── scripts/                # 城市数据维护脚本（Node，本地手动跑，不参与部署）
│   ├── complete-cities.js  # 从 DataV 补齐 docs/cities.js 缺失的地级行政区
│   └── gen-city-codes.js   # 由 DataV 生成 docs/city-codes.js（城市名 → adcode）
├── .github/
│   └── workflows/
│       └── build-android.yml  # 手动触发的安卓打包（JDK 17 + Secrets 里的签名密钥 → 签名 APK artifact）
└── README.md
```

## 🔌 API 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/api/me` | Bearer | 当前用户信息（token 经通行证验证，返回 `userId/nickname/color/avatar/is_admin/created_at`） |
| GET | `/api/cities` | 可选 Bearer | 城市 + 谁去过（地图用；登录可见本人私密，管理员可见全部） |
| GET | `/api/stats` | 可选 Bearer | 全站统计（管理员含私密行程） |
| GET | `/api/my-visits` | Bearer | 自己的足迹（含 is_private） |
| POST | `/api/visits` | Bearer | 添加足迹（可带 is_private） |
| PUT | `/api/visits/:id` | Bearer | 修改（仅本人，可改 is_private） |
| DELETE | `/api/visits/:id` | Bearer | 删除（仅本人） |
| GET | `/api/geo/:adcode` | 无 | 代理 DataV 边界接口（规避浏览器跨域，结果缓存 24h） |

> 注册 / 改密 / 邀请码 等账号能力已全部移交通行证 account.qxwkstudio.top；本站的登录表单只是前端直调通行证 `/api/login`，本站后端不自建账号体系。

## 🛠 设计说明

- **认证完全移交通行证**：本站不持有密码、不签发会话、不生成 token，登录也只由前端跨域直调通行证 `/api/login`（密码不经本站服务器）。所有身份来源都由 `account.qxwkstudio.top` 负责。前端收到 401 清掉本地 token 并回到登录视图；后端业务接口的 Bearer Token 必须经通行证 `/api/me` 二次验证（**无任何缓存**，每个请求都会跨站验证一次）。
- **前后端跨域，接口自带 CORS**：页面在 `travel.qxwkstudio.top`（GitHub Pages）、接口在 `api.travel.qxwkstudio.top`（Worker），不同源。前端请求带 `Authorization` / `Content-Type`，浏览器会先发 `OPTIONS` 预检，所以 Worker 必须处理预检并回 `Access-Control-Allow-*`；**4xx/5xx 也要带头**，否则浏览器只报 "CORS error"，前端那套 401 清 token / 回登录视图的逻辑永远触发不了。白名单（`backend/src/worker.js` 的 `ALLOWED_ORIGINS`）只放行前端域与 localhost —— 身份靠 Bearer token、不用 cookie，本就没有「靠 CORS 挡人」的安全边界，但也没必要让任意站点读响应。
- **本地身份按 `passport_id` 映射**：`resolveViewer()` 用通行证 `/api/me` 返回的 `userId` 认人（`users.passport_id`，唯一索引 `idx_users_passport`）。通行证里改昵称不会改 userId，所以改昵称不会在本站多出一条行；升级前的老行按「同昵称且 `passport_id IS NULL`」自动回填认领，保住原有足迹与管理员标志。
- **颜色与头像都由 Account 输出**：`users.color` 和用户头像 URL 都是通行证"单一事实源"，本站每次用户访问时同步覆盖。这样用户在通行证改颜色 / 改邮箱（头像 hash 变化）后，访问本站自动生效，避免两端数据漂移。
- **私密行程接口层过滤**：`is_private` 过滤在 Worker 侧（`lib.js` / worker 查询）做，而不是前端，防止有人抓接口构造出别人的私密足迹。管理员用 `is_admin=1` 标志绕过过滤查看全部。
- **成就系统**：判定逻辑在 `achievements.js` 前端执行，按"足迹丰碑 / 巡游四方 / 城市打卡 / 极限挑战"四大类分组。新增成就时在成就定义数组追加即可，判定函数拿到 `stats + myVisits` 上下文。
- **地图边界与瓦片缓存**：DataV GeoAtlas 边界由 Worker `/api/geo/:adcode` 代理并缓存 24h；浏览器侧再用 IndexedDB 保存 24h，打开地图时只拉取缺省的边界。瓦片用高德免 Key 内网直出、Leaflet 资源自托管到 `docs/vendor/`，避免外链失效与 CORS 折腾。
- **页面链接不带 `.html`**：GitHub Pages 对 `/xxx` 会自动回落到 `xxx.html`（`travel.qxwkstudio.top/account`、`/stats` 已线上验证），所以站内导航统一写裸路径（`/account`、`/visits`…），浏览器地址栏就不露后缀。`docs/app.js` 的 401 兜底与 `docs/robots.txt` 的路径都已按裸路径对齐 —— 改导航时别把后缀加回来。
- **响应式边距规范**：全站 6 页（index / account / visits / stats / news / setup）沿用同一套间距规范，新增页面或模块**务必遵守**，避免不同页面在手机/桌面上松紧不一。

  | 元素 | 桌面端（默认 CSS） | 手机端 `@media (max-width: 640px)` |
  |---|---|---|
  | `.navbar-inner` 左右内边距 | `0 24px` | `0 12px` |
  | `.main` 上 / 左右 / 下内边距 | `36px 24px 60px` | `20px 12px 32px` |
  | `.card` 内边距（普通卡片） | `24px` | `14px` |
  | `.back-link` 内边距 / 字号 | `8px 18px` / `0.88rem` | `6px 12px` / `0.82rem` |
  | `.footer` 上下 / 左右内边距 | `14px 24px`（所有页面已统一） | `12px 12px` |
  | `.page-header` 底部间距 | `28px` | `20px` |

  - 全屏页面（`index.html` 地图）的浮动元素（图例 / 管理员提示 / FAB 菜单 / Leaflet 弹窗）手机端也统一缩小：靠边 10–12px、FAB 直径 48px、弹窗最大宽度 300px
  - **不要用 768px 做手机断点**，全站统一 640px，保证与 account 项目断点对齐

---

## 🚀 部署指南

### 1️⃣ 创建 D1 数据库

Cloudflare 控制台 → **Workers & Pages** → **D1** → **创建数据库**（Create database）

- 名字填：`qxwk-data`
- 创建后复制 **database_id**（一串 UUID）

### 2️⃣ 建表（迁移）

在 `backend/` 目录里执行（会自动按顺序应用 `backend/migrations/` 下所有迁移）：

```bash
cd backend
npx wrangler d1 migrations apply qxwk-data --remote
```

> PowerShell 下请把 `npx` 写成 `npx.cmd`（执行策略会拦掉 `npx`；下同，所有 wrangler 命令都适用）。

迁移会创建本站所需的全部表：`users`（`is_admin` 管理员标志、`color` 颜色随通行证同步、`passport_id` 通行证 userId，两站共享）与 `cf_visits`（足迹，含 `is_private`，本站独占），并一并建出 `passport_id` 的唯一索引。


#### ⚠️ 线上已有库必须手工补 `passport_id` 列

线上 `users` 表已经存在（且是与 [Qxwk-Blog](https://github.com/Qxwk-Studio/Qxwk-Blog) **共享**的库 `qxwk-data`），`CREATE TABLE IF NOT EXISTS` 和 `npx wrangler d1 migrations apply qxwk-data --remote` **都补不了这个新列**，必须手工执行。**按下面顺序走**，命令都在 `backend/` 目录里跑（PowerShell 下 `npx` 写成 `npx.cmd`）：

```bash
# ① 补 passport_id 列（通行证 userId）
npx wrangler d1 execute qxwk-data --remote --command "ALTER TABLE users ADD COLUMN passport_id INTEGER"

# ② 补唯一索引：SQLite 的 ALTER TABLE 加不了 UNIQUE 列约束，
#    唯一性只能靠显式唯一索引兜住（多行 NULL 是允许的，不影响 Qxwk-Blog 的老行）
npx wrangler d1 execute qxwk-data --remote --command "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_passport ON users(passport_id)"

# ③ 核对结构：三条命令都该各回一行（列名 / 索引名 / 列名），查不到就是没补上。
#    avatar 是第三项：本站登录时会写它（把通行证头像同步给共用这张表的 Qxwk-Blog 的 feed），
#    缺列会让登录 500；线上一般早已由 Qxwk-Blog 侧补过，这里只是顺手确认
npx wrangler d1 execute qxwk-data --remote --command "SELECT name FROM pragma_table_info('users') WHERE name = 'passport_id'"
npx wrangler d1 execute qxwk-data --remote --command "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_users_passport'"
npx wrangler d1 execute qxwk-data --remote --command "SELECT name FROM pragma_table_info('users') WHERE name = 'avatar'"

# ④ 部署后端（必须在补列之后）
npx wrangler deploy
```

⑤ 部署后用通行证账号登录一次本站，再查存量行有没有被认领 —— `passport_id` 应从 NULL 变成你的通行证 userId（**仍在 `backend/` 目录**）：

```bash
npx wrangler d1 execute qxwk-data --remote --command "SELECT id, nickname, passport_id, is_admin FROM users ORDER BY id"
```

只有「昵称与通行证一致、且 `passport_id IS NULL`」的老行会被自动认领；昵称已经和通行证对不上、且此前从没走过新逻辑的老行认不出来，会在下次登录时新建一行，**旧行及其足迹需要人工在库里合并**。

漏加这一列会让所有需要登录的接口直接 500（`resolveViewer()` 的第一条 SQL 就查 `passport_id`）。

### 3️⃣ 在通行证注册本站

建议在通行证的 `apps` 表登记本站（仅用于登录时把 `client` 认成本站站点名；通行证 `/api/login`、`/api/me` 的 CORS 已全面放行，未登记站点也能正常登录与跨域验证 token，只是来源会被记为「未登记来源」）。在通行证项目执行：

> ⚠️ `apps` 表已不再用 `name` / `origin` 两列，改成 **`display_name`（展示名）** + **`match_type` / `match_key`（检测名称）**。网页来源填 `match_type='origin'`，`match_key` 必须是**规范 origin**（`scheme://host[:port]`，无路径、无末尾斜杠，要与浏览器请求里的 `Origin` 完全一致）。

```bash
cd c:\Code\Qxwk-Account
npx wrangler d1 execute qxwk-account --remote --command "INSERT OR IGNORE INTO apps (display_name, match_type, match_key, homepage) VALUES ('City Footprint', 'origin', 'https://travel.qxwkstudio.top', 'https://travel.qxwkstudio.top')"
```

本地联调另插一行 origin（填**本地页面**的地址，端口随你用的静态服务器而定；不登记也能登录，只是来源会被记成「未登记来源」）：

```bash
npx wrangler d1 execute qxwk-account --local --command "INSERT OR IGNORE INTO apps (display_name, match_type, match_key, homepage) VALUES ('City Footprint 本地', 'origin', 'http://localhost:8080', 'http://localhost:8080')"
```

### 4️⃣ 填入 database_id 并部署

打开 `backend/wrangler.toml`，把 `database_id` 替换成你的 D1 数据库 ID：

```toml
database_id = "你的-D1-数据库ID"
```

这个文件里**只有 D1 绑定** —— 页面已搬去 GitHub Pages（`docs/` 目录），所以没有 `[assets]` 静态资源段，Worker 只服务 `/api/*`。

> **⚠️ 前端搬走后，`env.ASSETS` 的回落必须一起删**：`worker.js` 末尾原先用 `env.ASSETS.fetch(request)` 兜底静态资源；既然这里不再声明 `[assets]`，`env.ASSETS` 就会是 `undefined`，忘了删那句的话任何未命中路由的请求都会抛错（Cloudflare Error 1101，本该 404 的路径变成 500）。现在那段已改为直接回 JSON 404。

提交并推送，然后在 `backend/` 目录执行部署：

```bash
cd backend
npx wrangler deploy
```

部署结果应为 **Worker + D1 绑定**（D1 绑定都写在 `backend/wrangler.toml` 里，无需再去网页配置）。

> 前端不在 Cloudflare 上：GitHub 仓库 → **Settings → Pages → Source** 选 `Deploy from a branch`，分支 `main` / 目录 `/docs`，保存后 `docs/` 里的页面就发布到 `travel.qxwkstudio.top`。

### 5️⃣ 自定义域名

前端与后端现在是**两个域**，各配各的：

- **前端（GitHub Pages）**：仓库 Settings → Pages → Custom domain 填 `travel.qxwkstudio.top`（同时按提示在 DNS 配好 CNAME）
- **后端（Worker）**：CF 控制台 → 该 Worker → Settings → Domains & Routes → 添加自定义域 `api.travel.qxwkstudio.top`

> **两处必须配对**：`docs/app.js` 的 `API_BASE` 写死指后端域，`backend/src/worker.js` 的 `ALLOWED_ORIGINS` 白名单只放行前端域。以后换域名要同时改这两处，否则浏览器侧会直接报 CORS 错误。

### 👑 管理员

管理员标志存在本站 users 表，由通行证登录后映射到本地用户时保留。直接改 D1：

```sql
-- 把某用户设为管理员
UPDATE users SET is_admin = 1 WHERE nickname = '你的昵称';

-- 取消管理员
UPDATE users SET is_admin = 0 WHERE nickname = '你的昵称';

-- 查看所有管理员
SELECT id, nickname, is_admin FROM users WHERE is_admin = 1;
```

### 💻 本地开发

在 `backend/` 目录里启动（PowerShell 下用 `npx.cmd`）：

```bash
cd backend
npx wrangler dev --port 8788
```

本地会读取 `backend/wrangler.toml` 里的 D1 绑定（需先建库并填 ID）。**本地只起后端** —— 页面已经不在 Worker 上了，
想看页面就另起一个静态服务器指向仓库的 `docs/`（例如 `npx.cmd -y serve docs`）。`docs/app.js` 的 `API_BASE`
这时仍指向线上后端，够用；要连本地后端就把它临时改成 `http://localhost:8788/api`，CORS 白名单已放行
localhost 的任意端口（见 `backend/src/worker.js` 的 `ALLOWED_ORIGINS`）。

**通行证登录本地联调**：把 `backend/src/lib.js` 与 `docs/app.js` 顶部 `PASSPORT_URL` 改成 `http://localhost:8787`，另起一个终端跑通行证 `cd c:\Code\Qxwk-Account && npx wrangler dev`（端口 8787），并在通行证本地 DB 插入本站 origin（见 3️⃣）。

## 🔧 自定义指南

**1. 补充城市数据**
- 编辑 `docs/cities.js`，往对应省份数组里加 `{ name, province, lat, lng }` 即可
- 想批量补全（自治州 / 地区 / 盟 / 直辖县级）或重算 adcode，用 `scripts/` 下的两个脚本：`node scripts/complete-cities.js`（补 `cities.js`）、`node scripts/gen-city-codes.js`（重生成 `city-codes.js`），数据都来自阿里 DataV GeoAtlas
- 改完 `docs/` 的城市数据后**必须重跑** `node app/tools/gen-cities.mjs` 并提交 `app/src/main/assets/cities.json`，否则安卓端的城市表与网页版分叉

**2. 更换地图瓦片**
- 默认使用**高德免 Key 瓦片**（国内加载快），Leaflet 库也已自托管到 `docs/vendor/`（与站点同源，走 GitHub Pages 的 CDN）
- 如需换回 OpenStreetMap 或其他官方瓦片源，修改 `docs/index.html` 里 `L.tileLayer` 的 URL 即可（高德/腾讯官方瓦片需申请 Key）

**3. 查看免费额度**
- Workers 每天 10 万次请求、D1 5GB 存储，个人使用完全足够
- 注意 D1 单查询上限为 10 万行读取；`cf_visits` 达到数万条后再考虑加接口缓存 / 预计算

**4. 不公开行程（`is_private`）**
- 勾选"不公开行程"的足迹仅本人可见，接口层通过 `is_private` 字段过滤
- 公开接口（地图 `/api/cities`、统计 `/api/stats`）默认不含私密；登录用户在地图上可见自己的私密行程，管理员可见全部
- 地图边界数据已做浏览器 IndexedDB 缓存（24h 过期），重复打开不重复请求

**5. 用户颜色来源**
- 本站 users.color 不再独立分配，每次用户访问时由通行证 `/api/me` 返回的 color 同步覆盖
- 颜色的源头是通行证侧：注册时按用户数 `count % 60` 顺序分配 60 个预设色，用户也可在通行证个人中心自定义
- 用户在通行证改了颜色，下次访问本站会自动同步过来

**6. 头像机制（WeAvatar）**
- 所有头像 URL 由通行证 Account 后端集中计算（基于 `sha256(lowercase(trim(email)))` → `https://weavatar.com/avatar/{hash}?s=400&d=404`），本站**不再持有任何哈希实现**
- 消费方式：`docs/app.js` 中定义的 `setAvatarFromUrl(el, avatarUrl, nickname, color)`（原 `avatar.js` 已删除并合并入 app.js）——加载失败自动回退到「昵称首字 + 专属颜色」的文字头像
- 通行证返回 `token` 后，本站调 `/api/me` 即带回 `avatar` 字段并写入本地用户缓存 `qxwf_user`；更换头像服务（如切到 QQ 官方头像或自托管 Gravatar）**只需改 Account 后端 `getAvatarUrl()` 一处**，本站零改动

---

🌐 在线地址：[https://travel.qxwkstudio.top](https://travel.qxwkstudio.top/)

🎫 通行证：[https://account.qxwkstudio.top](https://account.qxwkstudio.top/)

📧 联系邮箱：QxwkStudio@outlook.com

版权所有 2026 青翔未阔工作室
