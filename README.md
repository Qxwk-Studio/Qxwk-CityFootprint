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
- **通行证登录**：本站不再自建账号体系，登录/注册/改密/邀请码全部移交通行证 account.qxwkstudio.top
- **一次登录处处通行**：在通行证登录后回跳本站自动落地，本地存 token，后续访问免登录
- **账户信息**：专属颜色大头像（**直接使用通行证返回的 avatar URL，前端不再自己计算邮箱 MD5**）、UID、注册时间、管理员徽章
- **通行证中心入口**：个人中心右列提供通行证跳转卡，方便改昵称/颜色/密码、生成邀请码、查看最近登录

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
- **Bearer Token**：业务请求带通行证下发的 token，本站后端拿 token 去问通行证 `/api/me` 验证（结果按 token 缓存 120 秒）
- **私密数据**：不公开行程在接口层过滤，仅本人（或管理员）可见

## 🔗 通行证 SSO 接入说明

本站是 [Qxwk 通行证](https://account.qxwkstudio.top/) 的接入站点之一，认证流程：

1. 用户访问本站任意页，前端无 token 时引导跳通行证 `login.html?redirect=<回跳地址>`
2. 用户在通行证完成登录/注册，通行证校验 `redirect` origin 是否在 `apps` 白名单
3. 命中白名单后，通行证回跳本站并附 `?token=<通行证token>&uid=<通行证uid>&nick=<昵称>`
4. 本站 `app.js` 的 `handleSsoLanding()` 落地：存 token 到 localStorage、清 URL 参数、调本站 `/api/me` 写本地用户缓存
5. 后续业务请求带 `Authorization: Bearer <token>`，本站后端 `resolveViewer()` 拿 token 去通行证 `/api/me` 验证，按 nickname 映射到本地 users 表（首次自动建号，颜色随通行证同步）

**本地联调**：CF `lib.js` 与 `app.js` 顶部的 `PASSPORT_URL` 改成 `http://localhost:8787`，通行证本地 DB 需在 `apps` 表插入 `http://localhost:8788` origin。

## 🧱 技术栈

- **运行时**：Cloudflare Workers + Static Assets
- **数据库**：D1（SQLite，Cloudflare 原生）
- **认证**：Qxwk 通行证 SSO（Bearer Token 跨站校验，按 token 缓存）
- **前端**：原生 HTML / JS + [Leaflet](https://leafletjs.com/) 地图库
- **托管平台**：Cloudflare Pages 自动部署（`npx wrangler deploy`）

## 📁 项目结构

```
├── migrations/
│   ├── 0001_init.sql       # 建表：users / visits
├── src/
│   ├── worker.js           # Worker 入口（/api/* 接口 + 静态资源回退）
│   └── lib.js              # 通行证 token 验证 + 本地用户映射 + 工具
├── public/                 # 静态前端
│   ├── vendor/             # 自托管前端依赖（Leaflet JS + CSS + 标记图标，避免外链与 CORS）
│   ├── index.html          # 足迹大地图
│   ├── account.html        # 个人中心（通行证登录入口 + 资料卡）
│   ├── visits.html         # 足迹管理（增删改/统计/成就）
│   ├── setup.html          # 欢迎动画页（嵌入 account 未登录左侧，跟随主题同步）
│   ├── stats.html          # 全站统计
│   ├── news.html           # 公告与更新日志
│   ├── achievements.js     # 成就定义与判定
│   ├── app.js              # API 客户端 + SSO 会话（落地/跳转/401 兜底） + setAvatarFromUrl()（头像渲染）
│   ├── cities.js           # 国内地级市坐标数据
│   └── city-codes.js       # 城市 adcode（地图边界用）
├── wrangler.toml           # Worker 配置（D1 绑定在这填 database_id）
└── README.md
```

---

## 🚀 部署指南

### 1️⃣ 创建 D1 数据库

Cloudflare 控制台 → **Workers & Pages** → **D1** → **创建数据库**（Create database）

- 名字填：`qxwk-cityfootprint`
- 创建后复制 **database_id**（一串 UUID）

### 2️⃣ 建表（迁移）

在项目根目录执行（会自动按顺序应用 `migrations/` 下所有迁移）：

```bash
npx wrangler d1 migrations apply qxwk-cityfootprint --remote
```

迁移会创建本站所需的全部表：`users`（`is_private` 由 visits 侧携带、`is_admin` 管理员标志、`color` 颜色随通行证同步）与 `visits`（足迹，含 `is_private`）。  
&gt; 注：本站自 SSO 改造起不再自建账号与密码体系，注册/改密/邀请码等均移交通行证，因此迁移文件中**不包含** sessions / invite_codes / settings 表。

### 3️⃣ 在通行证注册本站

本站接入通行证 SSO 必须在通行证的 `apps` 表登记 origin（白名单 + SSO 回跳校验）。在通行证项目执行：

```bash
cd c:\Code\Qxwk-Account
npx wrangler d1 execute qxwk-account --remote --command "INSERT OR IGNORE INTO apps (name, origin, homepage) VALUES ('City Footprint', 'https://travel.qxwkstudio.top', 'https://travel.qxwkstudio.top')"
```

本地联调另插一行 origin（dev 端口 8788）：

```bash
npx wrangler d1 execute qxwk-account --local --command "INSERT OR IGNORE INTO apps (name, origin, homepage) VALUES ('City Footprint 本地', 'http://localhost:8788', 'http://localhost:8788')"
```

### 4️⃣ 填入 database_id 并部署

打开 `wrangler.toml`，把 `database_id` 替换成你的 D1 数据库 ID：

```toml
database_id = "你的-D1-数据库ID"
```

提交并推送。CF Pages 项目会自动执行 `npx wrangler deploy`，正确部署成
**Worker + 静态资源 + D1 绑定**（绑定写在 wrangler.toml 里，无需再去网页配置）。

### 5️⃣ 自定义域名（可选）

Pages 项目 → **自定义域**（Custom domains）→ 添加你的域名（如 `travel.qxwkstudio.top`）

然后在主站对应位置放一个跳转链接指向它即可。

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

```bash
npm i -g wrangler
wrangler dev --port 8788
```

本地会读取 `wrangler.toml` 里的 D1 绑定（需先建库并填 ID），
Worker 会在 `localhost:8788` 同时提供页面和 API。

**SSO 本地联调**：把 `src/lib.js` 与 `public/app.js` 顶部 `PASSPORT_URL` 改成 `http://localhost:8787`，另起一个终端跑通行证 `cd c:\Code\Qxwk-Account && npx wrangler dev`（端口 8787），并在通行证本地 DB 插入本站 origin（见 3️⃣）。

## 🔌 API 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/api/me` | Bearer | 当前用户信息（token 经通行证验证，返回 `userId/nickname/color/avatar/is_admin/created_at`） |
| GET | `/api/cities` | 可选 Bearer | 城市 + 谁去过（地图用；登录可见本人私密，管理员可见全部） |
| GET | `/api/user/:nickname` | 可选 Bearer | 某人足迹明细（管理员可见其私密） |
| GET | `/api/stats` | 可选 Bearer | 全站统计（管理员含私密行程） |
| GET | `/api/my-visits` | Bearer | 自己的足迹（含 is_private） |
| POST | `/api/visits` | Bearer | 添加足迹（可带 is_private） |
| PUT | `/api/visits/:id` | Bearer | 修改（仅本人，可改 is_private） |
| DELETE | `/api/visits/:id` | Bearer | 删除（仅本人） |
| GET | `/api/geo/:adcode` | 无 | 代理 DataV 边界接口（规避浏览器跨域，结果缓存 24h） |

> 注册 / 登录 / 改密 / 邀请码 / 最近登录 等账号能力已全部移交通行证 account.qxwkstudio.top，本站不再提供。

## 🔧 自定义指南

**1. 补充城市数据**
- 编辑 `public/cities.js`，往对应省份数组里加 `{ name, province, lat, lng }` 即可

**2. 更换地图瓦片**
- 默认使用**高德免 Key 瓦片**（国内加载快），Leaflet 库也已自托管到 `public/vendor/`（与站点同源，走 Cloudflare CDN）
- 如需换回 OpenStreetMap 或其他官方瓦片源，修改 `public/index.html` 里 `L.tileLayer` 的 URL 即可（高德/腾讯官方瓦片需申请 Key）

**3. 查看免费额度**
- Workers 每天 10 万次请求、D1 5GB 存储，个人使用完全足够
- 注意 D1 单查询上限为 10 万行读取；`visits` 达到数万条后再考虑加接口缓存 / 预计算

**4. 不公开行程（`is_private`）**
- 勾选"不公开行程"的足迹仅本人可见，接口层通过 `is_private` 字段过滤
- 公开接口（地图 `/api/cities`、统计 `/api/stats`、公开资料 `/api/user/:nickname`）默认不含私密；登录用户在地图上可见自己的私密行程，管理员可见全部
- 地图边界数据已做浏览器 IndexedDB 缓存（24h 过期），重复打开不重复请求

**5. 用户颜色来源**
- 本站 users.color 不再独立分配，每次用户访问时由通行证 `/api/me` 返回的 color 同步覆盖
- 颜色的源头是通行证侧：注册时按用户数 `count % 60` 顺序分配 60 个预设色，用户也可在通行证个人中心自定义
- 用户在通行证改了颜色，下次访问本站会自动同步过来

**6. 头像机制（WeAvatar）**
- 所有头像 URL 由通行证 Account 后端集中计算（基于 `md5(lowercase(trim(email)))` → `https://weavatar.com/avatar/{hash}?s=400&d=404`），本站**不再持有任何 MD5 代码**
- 消费方式：`public/app.js` 中定义的 `setAvatarFromUrl(el, avatarUrl, nickname, color)`（原 `avatar.js` 已删除并合并入 app.js）——加载失败自动回退到「昵称首字 + 专属颜色」的文字头像
- SSO 落地与 `/api/me` 接口均返回 `avatar` 字段并存入本地会话 `s.avatar`；更换头像服务（如切到 QQ 官方头像或自托管 Gravatar）**只需改 Account 后端 `getAvatarUrl()` 一处**，本站零改动

**7. 响应式边距规范（设计一致性）**
全站 6 页（index / account / visits / stats / news / setup）沿用同一套间距规范，新增页面或模块**务必遵守**，避免不同页面在手机/桌面上松紧不一。

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

## 🛠 设计说明

- **认证完全移交通行证**：本站不持有密码、不签发会话、不生成 token。所有身份来源都由 `account.qxwkstudio.top` 负责。前端收到 401 直接跳通行证；后端业务接口的 Bearer Token 必须经通行证 `/api/me` 二次验证（结果按 token 缓存 120s，避免一次请求一次跨站验证）。
- **颜色与头像都由 Account 输出**：`users.color` 和用户头像 URL 都是通行证"单一事实源"，本站每次用户访问时同步覆盖。这样用户在通行证改颜色 / 改邮箱（头像 hash 变化）后，访问本站自动生效，避免两端数据漂移。
- **私密行程接口层过滤**：`is_private` 过滤在 Worker 侧（`lib.js` / worker 查询）做，而不是前端，防止有人抓接口构造出别人的私密足迹。管理员用 `is_admin=1` 标志绕过过滤查看全部。
- **成就系统**：判定逻辑在 `achievements.js` 前端执行，按"足迹丰碑 / 巡游四方 / 城市打卡 / 极限挑战"四大类分组。新增成就时在成就定义数组追加即可，判定函数拿到 `stats + myVisits` 上下文。
- **地图边界与瓦片缓存**：DataV GeoAtlas 边界由 Worker `/api/geo/:adcode` 代理并缓存 24h；浏览器侧再用 IndexedDB 保存 24h，打开地图时只拉取缺省的边界。瓦片用高德免 Key 内网直出、Leaflet 资源自托管到 `public/vendor/`，避免外链失效与 CORS 折腾。

---


🌐 在线地址：[https://travel.qxwkstudio.top](https://travel.qxwkstudio.top/)

🎫 通行证：[https://account.qxwkstudio.top](https://account.qxwkstudio.top/)

📧 联系邮箱：QxwkStudio@outlook.com

版权所有 2026 青翔未阔工作室