# 🗺️ City Footprint

> 记录每个人去过的城市，在足迹地图上点亮属于自己的颜色。
>
> 注册账号后把自己去过的城市打点在地图上，和朋友们一起拼出一张五彩斑斓的足迹大地图。

## ✨ 功能一览

### 🗺️ 足迹大地图 (`index.html`)
- **公开浏览**：所有访客无需登录即可查看完整地图
- **专属颜色**：每个用户一种标记色，同城多点自动散开
- **按人筛选**：点击图例中的昵称，只看某一个人的足迹
- **私密行程**：本人登录时可见自己的不公开足迹（弹窗带 🔒），他人不可见
- **管理员视图**：管理员可查看所有人的行程（含私密），右上角显示 👑 标识

### 👤 个人足迹管理 (`manage.html`)
- **注册登录**：昵称 + 密码即可使用（邀请码制）
- **足迹增删改**：添加 / 修改 / 删除自己的城市足迹
- **不公开行程**：可勾选"仅自己可见"，不出现在公开地图与统计中
- **时间与备注**：记录访问时间，留一句话回忆
- **邀请码栏**：所有登录用户可见当前可用邀请码，方便邀请朋友注册（用户邀请制）

### 🏆 成就系统（`manage.html` / `stats.html`）
- **4 大分类**：足迹丰碑 / 巡游四方 / 城市打卡 / 极限挑战
- **自动判定**：根据足迹自动解锁，分类可折叠、显示进度
- **成就统计**：统计页展示每个成就的达成人数

### 📊 全站统计 (`stats.html`)
- 总行程、总城市、城市次数排名 Top30
- 成就达成人数
- 管理员登录时统计全部行程（含私密）

### 🛡️ 安全设计
- **密码加密**：PBKDF2（10 万次迭代 + 随机盐）哈希存储，不落明文
- **会话鉴权**：Bearer Token，只能操作自己的足迹
- **私密数据**：不公开行程在接口层过滤，仅本人（或管理员）可见

## 🧱 技术栈

- **运行时**：Cloudflare Workers + Static Assets
- **数据库**：D1（SQLite，Cloudflare 原生）
- **前端**：原生 HTML / JS + [Leaflet](https://leafletjs.com/) 地图库
- **托管平台**：Cloudflare Pages 自动部署（`npx wrangler deploy`）

## 📁 项目结构

```
├── migrations/
│   ├── 0001_init.sql       # 建表：users / visits / sessions
│   ├── 0002_invite_codes.sql # 邀请码表
│   ├── 0003_visits_private.sql # visits 增加 is_private（不公开行程）
│   ├── 0004_admin.sql      # users 增加 is_admin（管理员）
│   └── 0005_invite_created_by_settings.sql # 邀请码溯源 + 系统设置表（生成/注册开关）
├── src/
│   ├── worker.js           # Worker 入口（/api/* 接口 + 静态资源回退）
│   └── lib.js              # 密码哈希、会话、工具
├── public/                 # 静态前端
│   ├── index.html          # 足迹大地图
│   ├── manage.html         # 个人管理（登录/注册/增删改/成就/邀请码）
│   ├── stats.html          # 全站统计
│   ├── news.html           # 公告与更新日志
│   ├── achievements.js     # 成就定义与判定
│   ├── app.js              # API 客户端 + 会话
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

或者用 D1 控制台，把每个迁移文件的内容依次复制进去运行。
（会创建 `users` / `visits` / `sessions` / `invite_codes` 表，并加上 `is_private`、`is_admin` 列）

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

**用户获取（自动生成 + 溯源）**：个人中心邀请码栏调用 `GET /api/invite-code`，后端逻辑为——
- 查询该用户未使用的邀请码：**有则直接返回**（同一用户始终同一个码，便于溯源）
- **没有则后端自动生成一个**，并把 `created_by`（生成人）记为当前用户

**管理员手工补码**（此时 `created_by` 为 NULL）：在 D1 控制台（或 `npx wrangler d1 execute qxwk-cityfootprint --remote --command "..."`）执行：

```sql
-- 插入指定码
INSERT INTO invite_codes (code) VALUES ('ABC12345');

-- 查看所有邀请码：来源（created_by）与使用情况（used_at / used_by）
SELECT code, created_by, used_by, used_at FROM invite_codes;
```

> 💡 **可溯源**：每个邀请码都能查到「谁生成的（`created_by`）」和「谁用掉的（`used_by`、`used_at`）」。新用户注册时使用的是别人的码，`used_by` 会记为这个新用户。

**⏸️ 暂停邀请码生成 / 暂停注册**（两个独立开关，直接改数据库键值）：
- `invite_generate_enabled`：邀请码生成开关（`'0'` 暂停生成 → 邀请码栏显示"已暂停生成"，不再发新码）
- `invite_register_enabled`：注册开关（`'0'` 暂停注册 → 新注册被拒绝、注册表单禁用）

用 D1 控制台或 `npx wrangler d1 execute qxwk-cityfootprint --remote --command "..."` 修改：

```sql
-- 暂停邀请码生成（已有未用的码仍可被使用注册）
UPDATE settings SET value = '0' WHERE key = 'invite_generate_enabled';
-- 恢复邀请码生成
UPDATE settings SET value = '1' WHERE key = 'invite_generate_enabled';

-- 暂停注册（完全停止新注册）
UPDATE settings SET value = '0' WHERE key = 'invite_register_enabled';
-- 恢复注册
UPDATE settings SET value = '1' WHERE key = 'invite_register_enabled';
```

### 👑 管理员

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
wrangler dev
```

本地会读取 `wrangler.toml` 里的 D1 绑定（需先建库并填 ID），
Worker 会在 `localhost:8787` 同时提供页面和 API。

## 🔌 API 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/register` | 无 | 注册（需一次性邀请码），返回 token |
| POST | `/api/login` | 无 | 登录，返回 token（密码为空字符串时返回需设置新密码信号） |
| POST | `/api/set-password` | 无 | 密码为空字符串时设置新密码（管理员重置后使用） |
| GET | `/api/config` | 无 | 注册配置（含 inviteGenerateEnabled / inviteRegisterEnabled） |
| GET | `/api/me` | Bearer | 当前用户信息（含 is_admin） |
| GET | `/api/cities` | 可选 Bearer | 城市 + 谁去过（地图用；登录可见本人私密，管理员可见全部） |
| GET | `/api/user/:nickname` | 可选 Bearer | 某人足迹明细（管理员可见其私密） |
| GET | `/api/stats` | 可选 Bearer | 全站统计（管理员含私密行程） |
| GET | `/api/invite-code` | Bearer | 本人未使用的邀请码（没有则自动生成，记录生成人；暂停生成时返回 paused） |
| GET | `/api/my-visits` | Bearer | 自己的足迹（含 is_private） |
| POST | `/api/visits` | Bearer | 添加足迹（可带 is_private） |
| PUT | `/api/visits/:id` | Bearer | 修改（仅本人，可改 is_private） |
| DELETE | `/api/visits/:id` | Bearer | 删除（仅本人） |

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

**5. 重置用户密码（忘记密码）**
- 在 D1 控制台把该用户的 `password_hash` 改成**空字符串**（该列是 `NOT NULL`，不能写 `NULL`）：
  ```sql
  UPDATE users SET password_hash = '' WHERE nickname = '用户名';
  ```
- 该用户下次登录时会提示"设置新密码"，设置后即可正常登录
- ⚠️ 注意：此时任何知道该昵称的人都能设置密码，请仅在信任本人时操作

---

🌐 部署完成后，在此处填写在线地址（如 `https://travel.qxwkstudio.top`）

📧 联系邮箱：QxwkStudio@outlook.com

版权所有 2026 青翔未阔工作室
