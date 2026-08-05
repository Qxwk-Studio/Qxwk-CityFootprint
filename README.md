# 🗺️ 拾光迹 · CityFootprint

> 记录每个人去过的城市，在足迹地图上点亮属于自己的颜色。
>
> 注册账号后把自己去过的城市打点在地图上，和朋友们一起拼出一张五彩斑斓的足迹大地图。

## ✨ 功能一览

### 🗺️ 足迹大地图 (`index.html`)
- **公开浏览**：所有访客无需登录即可查看完整地图
- **专属颜色**：每个用户一种标记色，同城多点自动散开
- **按人筛选**：点击图例中的昵称，只看某一个人的足迹

### 👤 个人足迹管理 (`manage.html`)
- **注册登录**：昵称 + 密码即可使用
- **足迹增删改**：添加 / 修改 / 删除自己的城市足迹
- **时间与备注**：记录访问时间，留一句话回忆

### 🛡️ 安全设计
- **密码加密**：PBKDF2（10 万次迭代 + 随机盐）哈希存储，不落明文
- **会话鉴权**：Bearer Token，只能操作自己的足迹

## 🧱 技术栈

- **运行时**：Cloudflare Workers + Static Assets
- **数据库**：D1（SQLite，Cloudflare 原生）
- **前端**：原生 HTML / JS + [Leaflet](https://leafletjs.com/) 地图库
- **托管平台**：Cloudflare Pages 自动部署（`npx wrangler deploy`）

## 📁 项目结构

```
├── migrations/
│   └── 0001_init.sql       # 建表 SQL
├── src/
│   ├── worker.js           # Worker 入口（/api/* 接口 + 静态资源回退）
│   └── lib.js              # 密码哈希、会话、工具
├── public/                 # 静态前端
│   ├── index.html          # 足迹大地图
│   ├── manage.html         # 个人管理（登录/注册/增删改）
│   ├── app.js              # API 客户端 + 会话
│   └── cities.js           # 国内地级市坐标数据
├── wrangler.toml           # Worker 配置（D1 绑定在这填 database_id）
└── README.md
```

---

## 🚀 部署指南

### 1️⃣ 创建 D1 数据库

Cloudflare 控制台 → **Workers & Pages** → **D1** → **创建数据库**（Create database）

- 名字填：`qxwk-cityfootprint`
- 创建后复制 **database_id**（一串 UUID）

### 2️⃣ 建表

在 D1 页面点进 `qxwk-cityfootprint` → **控制台**（Console）→ 依次把
`migrations/0001_init.sql` 和 `migrations/0002_invite_codes.sql` 的内容
整个复制进去 → 各点一次 **运行**（Run）

（会创建 `users` / `visits` / `sessions` 三张业务表 + `invite_codes` 邀请码表）

### 3️⃣ 填入 database_id 并部署

打开 `wrangler.toml`，把 `database_id` 替换成你的 D1 数据库 ID：

```toml
database_id = "你的-D1-数据库ID"
```

提交并推送。CF Pages 项目会自动执行 `npx wrangler deploy`，正确部署成
**Worker + 静态资源 + D1 绑定**（绑定写在 wrangler.toml 里，无需再去网页配置）。

> **⚠️ 历史问题备忘**：以下坑已解决，供参考——
> - `name` 已改为全小写
> - `main` + `[assets]` 已配置，不再报 "Missing entry-point"
> - 已删除 `functions/` 目录，不会再被误判成 Pages

### 4️⃣ 自定义域名（可选）

Pages 项目 → **自定义域**（Custom domains）→ 添加你的域名（如 `travel.qxwkstudio.top`）

然后在主站对应位置放一个跳转链接指向它即可。

### 🔑 注册邀请码

部署后注册必须使用**一次性邀请码**，一个码只能注册一次，用后即焚。

**1. 设置管理员密码**

打开 `wrangler.toml` 的 `[vars]`，把 `ADMIN_PASSWORD` 改成强密码：

```toml
[vars]
ADMIN_PASSWORD = "改成你的强密码"
```

线上想改码时不必重新部署，直接在 CF 控制台 → Worker → **设置** → **变量与机密** 里修改即可。

**2. 生成邀请码**

```bash
curl -X POST https://你的域名/api/invites \
  -H "Content-Type: application/json" \
  -d '{"admin_pass":"你的密码","count":5}'
```

返回：

```json
{ "codes": ["KX8Q3M7Z", "P2TD6FNA", ...] }
```

把生成的码发给朋友，每人用一个，注册成功即失效。

### 💻 本地开发

```bash
npm i -g wrangler
wrangler dev
```

本地会读取 `wrangler.toml` 里的 D1 绑定（需先建库并填 ID），
Worker 会在 `localhost:8787` 同时提供页面和 API。

## 🔌 API 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/register` | 无 | 注册（需一次性邀请码），返回 token |
| POST | `/api/login` | 无 | 登录，返回 token |
| POST | `/api/invites` | 管理员密码 | 生成一次性邀请码（count 1-20） |
| GET | `/api/config` | 无 | 注册配置（前端判断是否显示邀请码） |
| GET | `/api/cities` | 无 | 所有城市 + 谁去过（地图用） |
| GET | `/api/user/:nickname` | 无 | 某人的足迹明细 |
| GET | `/api/my-visits` | Bearer | 自己的足迹 |
| POST | `/api/visits` | Bearer | 添加足迹 |
| PUT | `/api/visits/:id` | Bearer | 修改（仅本人） |
| DELETE | `/api/visits/:id` | Bearer | 删除（仅本人） |

## 🔧 自定义指南

**1. 补充城市数据**
- 编辑 `public/cities.js`，往对应省份数组里加 `{ name, province, lat, lng }` 即可

**2. 更换地图瓦片**
- 默认 OpenStreetMap，无需 Key；国内访问较慢可换成高德/腾讯地图（需申请 Key）

**3. 查看免费额度**
- Workers 每天 10 万次请求、D1 5GB 存储，个人使用完全足够

---

🌐 部署完成后，在此处填写在线地址（如 `https://travel.qxwkstudio.top`）

📧 联系邮箱：QxwkStudio@outlook.com

版权所有 2026 青翔未阔工作室
