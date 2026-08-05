# Qxwk-CityFootprint · 拾光迹

记录每个人去过的城市，地图上点亮属于自己的颜色。

- 所有访客可直接查看足迹大地图
- 注册后（昵称 + 密码）添加 / 修改 / 删除自己的足迹
- 每个用户一种专属颜色，同城多点自动散开

技术栈：Cloudflare Pages + Pages Functions + D1（SQLite）

---

## 项目结构

```
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
├── wrangler.local.toml     # 本地开发配置模板（复制成 wrangler.toml 用）
└── README.md
```

> **重要**：`wrangler.toml` 已被 `.gitignore` 忽略。部署到 Pages 不需要它，
> D1 绑定直接在网页控制台配置（见下方步骤 4）。它只用于本地开发。

---

## 部署步骤（全网页操作，无需命令行）

### 1. 创建 D1 数据库

Cloudflare 控制台 → **Workers & Pages** → **D1** → **创建数据库**（Create database）

- 名字填：`qxwk-cityfootprint`
- 创建后复制 **database_id**（一串 UUID）

### 2. 建表

在 D1 页面点进 `qxwk-cityfootprint` → **控制台**（Console）→ 把 `migrations/0001_init.sql`
的内容整个复制进去 → **运行**（Run）

（会创建 `users` / `visits` / `sessions` 三张表）

### 3. 部署到 Cloudflare Pages

CF 控制台 → **Workers & Pages** → **创建** → **Pages** → **连接到 Git** → 选择仓库，构建配置：

- **框架预设**：None
- **构建命令**：留空
- **构建输出目录**：`public`

> 项目里**不要放 `wrangler.toml`**，否则部署会报 "Missing entry-point" 错误。
> 它已被 `.gitignore` 忽略，正常推代码不会带上去。

### 4. 绑定 D1 数据库（网页配置）

Pages 项目 → **设置**（Settings）→ **函数**（Functions）→ **D1 数据库绑定** → **添加绑定**：

- **变量名**：`DB`（必须大写）
- **D1 数据库**：选择 `qxwk-cityfootprint`
- 保存后**重新部署一次**（部署 → 三个点 → 重试部署）才生效

### 5. 自定义域名（可选）

Pages 项目 → **自定义域**（Custom domains）→ 添加你的域名（如 `travel.qxwkstudio.top`）

然后在主站对应位置放一个跳转链接指向它即可。

---

## 本地开发

```bash
# 复制本地配置模板并填入 D1 database_id
cp wrangler.local.toml wrangler.toml
# 编辑 wrangler.toml，把 database_id 换成你的
wrangler pages dev public
```

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
