# Qxwk-CityFootprint · 拾光迹

记录每个人去过的城市，地图上点亮属于自己的颜色。

- 所有访客可直接查看足迹大地图
- 注册后（昵称 + 密码）添加 / 修改 / 删除自己的足迹
- 每个用户一种专属颜色，同城多点自动散开

技术栈：Cloudflare Pages + Pages Functions + D1（SQLite）

---

## 项目结构

```
├── wrangler.toml           # D1 绑定配置
├── migrations/
│   └── 0001_init.sql       # 建表 SQL
├── functions/              # Pages Functions（API）
│   ├── _lib/               # 密码哈希、会话、工具
│   └── api/                # 接口
├── public/                 # 静态前端
│   ├── index.html          # 足迹大地图
│   ├── manage.html         # 个人管理（登录/注册/增删改）
│   ├── app.js              # API 客户端 + 会话
│   └── cities.js           # 国内地级市坐标数据
└── README.md
```

---

## 部署步骤

### 1. 创建 D1 数据库

Cloudflare 控制台 → **Workers & Pages** → **D1** → **Create database**

- 名字填：`qxwk-cityfootprint`
- 创建后复制 **database_id**（一串 UUID）

### 2. 配置 wrangler.toml

打开 `wrangler.toml`，把 `database_id` 替换成你的 D1 数据库 ID：

```toml
database_id = "你的-D1-数据库ID"
```

### 3. 应用数据库迁移

```bash
npm i -g wrangler
wrangler d1 migrations apply qxwk-cityfootprint
```

（迁移会创建 `users` / `visits` / `sessions` 三张表）

### 4. 部署到 Cloudflare Pages

方式 A（推荐）：把项目推到 GitHub，在 CF Pages 里 **Create project → Connect to Git**，选择该仓库，构建配置：

- **Framework preset**：None
- **Build command**：留空
- **Build output directory**：`public`

方式 B：本地直接上传

```bash
wrangler pages deploy public
```

### 5. 绑定 D1 数据库

Pages 项目 → **Settings → Functions → D1 database bindings** → Add binding：

- **Variable name**：`DB`
- **D1 database**：选择 `qxwk-cityfootprint`

### 6. 自定义域名

Pages 项目 → **Custom domains** → 添加你的域名（如 `travel.qxwkstudio.top`）

然后在主站对应位置放一个跳转链接指向它即可。

---

## 本地开发

```bash
wrangler pages dev public
```

本地也会读取 `wrangler.toml` 里的 D1 绑定（需先建库并填 ID）。

---

## API 接口

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/register` | 无 | 注册，返回 token |
| POST | `/api/login` | 无 | 登录，返回 token |
| GET | `/api/cities` | 无 | 所有城市 + 谁去过（地图用） |
| GET | `/api/user/:nickname` | 无 | 某人的足迹明细 |
| GET | `/api/my-visits` | Bearer | 自己的足迹 |
| POST | `/api/visits` | Bearer | 添加足迹 |
| PUT | `/api/visits/:id` | Bearer | 修改（仅本人） |
| DELETE | `/api/visits/:id` | Bearer | 删除（仅本人） |

## 补充说明

- **密码安全**：PBKDF2（10 万次迭代 + 随机盐）哈希存储，不落明文
- **城市数据**：内置国内地级市坐标（`cities.js`），需要补充城市往对应省份数组加即可
- **地图瓦片**：默认 OpenStreetMap，无需 Key；国内访问较慢可换成高德/腾讯地图（需申请 Key）
- **免费额度**：Pages Functions 每天 10 万次请求、D1 5GB 存储，个人使用完全足够
