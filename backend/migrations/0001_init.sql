-- 建库：用户 / 足迹 / 限速 / App 清单 —— 全站**唯一一份**建表文件，全新库跑它一步到位。
--
-- 认证（密码/会话/邀请码/系统设置）统一由通行证 account.qxwkstudio.top 处理；
-- 通行证侧曾有跨站 SSO / 跳转授权，现已整条下线，本站改为前端直调 /api/login 换 token
--
-- 历史上分过几个文件，现都合进这一份：
--   · 原 0002_cf_users.sql —— 两版线上都已应用完，合并只为让全新库一步到位，其内容即下面的
--     cf_users —— 本站独占的用户表，登录时不再往共享 users 里覆盖昵称 / 颜色 / 头像
--     cf_visits —— 直接建成最终形态：外键指 cf_users，含 adcode / transport 与 CHECK 约束
--   · 原 0002_app_manifest.sql（App 清单三张表）—— 内容见文件末尾「App 清单」一段
--
-- ⚠ 合并的代价，改这个文件前先看：`wrangler d1 migrations apply` 只执行**没记录在 d1_migrations
-- 里的**文件。所以把新表并进这份**早已应用过**的文件后，线上的库不会再执行它 ——
-- 已有库要用 `d1 execute --file` 手动跑一次（见 README 部署一节）。
-- 好在整份文件都是 IF NOT EXISTS / INSERT OR IGNORE，幂等，重复跑没有副作用。

-- passport_id 的唯一性靠显式索引：SQLite 的 ALTER TABLE 加不了 UNIQUE 列约束，
-- 线上旧表补列时也只能补成普通列，靠这条索引兜住（多行 NULL 是允许的，不影响 Qxwk-Blog 的老行）
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_passport ON users(passport_id);

-- 本站独占的用户表
CREATE TABLE IF NOT EXISTS cf_users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nickname TEXT UNIQUE NOT NULL,
  color TEXT NOT NULL,                  -- 地图打点颜色（通行证统一分配，每次访问同步覆盖）
  avatar TEXT,                          -- 头像链接（通行证算好的 WeAvatar，无邮箱为 NULL）。
                                        -- 本站**不读**它（/api/me 直接用通行证实时返回的那份），
                                        -- 留着是给本站页面日后用的
  passport_id INTEGER,                  -- 通行证账号 userId：稳定身份，改昵称不变
  is_admin INTEGER NOT NULL DEFAULT 0,  -- 1 = 管理员（改动见 README「管理员」一节）
  created_at TEXT DEFAULT (datetime('now'))
);

-- 唯一性靠显式索引兜住（多行 NULL 是允许的）。
-- 这条索引必须存在：lib.js 的 resolveViewer 用 `WHERE passport_id = ?` + .first() 认人，
-- 少了它一旦出现重复 passport_id 就会认到别人头上（线上曾因建表没带上它而缺过）。
CREATE UNIQUE INDEX IF NOT EXISTS idx_cf_users_passport ON cf_users(passport_id);

CREATE TABLE IF NOT EXISTS cf_visits (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  city TEXT NOT NULL,
  lat REAL NOT NULL,
  lng REAL NOT NULL,
  visit_date TEXT,                      -- 可空：记不清时间
  note TEXT,                            -- 可选备注
  is_private INTEGER NOT NULL DEFAULT 0,-- 1 = 不公开（本人可见）
  created_at TEXT DEFAULT (datetime('now')),
  adcode TEXT,                          -- 行政区划代码（后端按城市名字典派生；字典未命中留 NULL）
  transport TEXT,                       -- 出行方式：JSON 数组存英文 code（可多选），如 ["plane","train"]
  -- 下面这些约束后端代码也兜着（给人话错误提示），这里落到库里做最后一道关。
  -- visit_date：GLOB 不支持 \d，用 [0-9] 逐位写死，等价于后端的 ^\d{4}(-\d{2})?$
  CHECK (visit_date IS NULL OR visit_date GLOB '[0-9][0-9][0-9][0-9]' OR visit_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]'),
  CHECK (note IS NULL OR length(note) <= 100),
  CHECK (is_private IN (0, 1)),
  CHECK (length(city) BETWEEN 1 AND 30),
  CHECK (transport IS NULL OR json_valid(transport)),
  -- 外键指 cf_users（本站独占用户表）：指 users 的话，只存在于 cf_users 的新用户会被外键挡下
  FOREIGN KEY (user_id) REFERENCES cf_users(id)
);

CREATE INDEX IF NOT EXISTS idx_visits_user ON cf_visits(user_id);
CREATE INDEX IF NOT EXISTS idx_visits_city ON cf_visits(city);

-- 接口限速的**天级**计数表。
--
-- 分钟级窗口不落库：走 Cloudflare 的 Rate Limiting 绑定（见 wrangler.toml 的 RL_WRITE / RL_READ）。
-- 两套机制的分工与理由，见 backend/src/ratelimit.js 的文件头注释。
--
-- 只有**写接口**（POST/PUT/DELETE /api/visits）用这张表：
--   · 读接口刻意不设天级上限 —— 读请求量大，逐条往这里写计数会白白吃掉 D1 的
--     每日写入额度（免费计划 10 万行/天），换来的是「防一个已经登录的人多翻几页」，不划算；
--     读侧的突发由分钟级绑定挡住就够了。
--   · 写请求本来就少，一天一个用户最多一行，所以**刻意不做清理任务**（TTL / 定时删除都没有）。
--
-- 一天一行，靠 upsert 原地累加（见 ratelimit.js 的 checkWriteLimit）：
-- 单条语句里「先加再读回」才是原子的，并发的两个请求各加各的，谁都绕不过去。
CREATE TABLE IF NOT EXISTS cf_rate_daily (
  key TEXT NOT NULL,                    -- 'w:<userId>'：写接口 + 登录用户。前缀留给日后别的限速维度（如 'ip:'）
  day TEXT NOT NULL,                    -- UTC 日 YYYY-MM-DD：由 datetime('now') 派生，与边缘其它窗口同一口径，不做本地时区换算
  count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (key, day)                -- 主键即索引，判定时按 (key, 今天) 一次点查
);

-- ═══════════════════════════════════════════════════════════════════════════
-- App 清单（原 0002_app_manifest.sql，合并进本文件）
--
-- 公告 / 安卓版本（含更新说明）/ 主页菜单栏目。这三块原先都不在库里 —— 整份清单是网页静态文件
-- docs/version.json（只有 App 读它，见 app/README.md）。搬进 D1 是为了改内容不必再
-- 「改仓库 → push → 等 GitHub Pages 构建」：现在一条 wrangler d1 execute 就生效
-- （后端 GET /api/manifest 读这三张表）。
--
-- 表名沿用 cf_ 前缀（本站独占）。它们都很小、各十来行，所以**刻意不做**一张通用的 key-value 表：
-- 分开建能带上类型与 NOT NULL 约束，手写 SQL 时填错列名 / 少给一列会当场报错，
-- 而不是悄悄存进去一个坏值、等 App 解析时才发现。
-- ═══════════════════════════════════════════════════════════════════════════

-- id 一律 INTEGER PRIMARY KEY（SQLite 的 rowid 别名，不给值就自动取 max+1）：
--   · cf_notices 的 id 是**业务字段**：App 用它记「读到哪一条了」（Store.noticeReadId），
--     所以**只能往上加、不要改已有的值** —— 改小会让读过的公告重新变成未读。
--   · cf_menu 的 id 只用于排序（读接口 ORDER BY id ASC，数组顺序 = id 顺序），没有业务含义。
--   · cf_app_version 的 id 只是主键：挑「最新版」看的是 version_code（见下面那张表的注释），不看 id；
--     但手动改某一行时请照 id 定位，别用「改最后一行」这种位置说法 —— id 才是稳定的。

CREATE TABLE IF NOT EXISTS cf_notices (
  id INTEGER PRIMARY KEY,
  title TEXT NOT NULL,
  date TEXT,                -- 只做展示，不参与任何判断（没填时 App 把那一行整行收起）
  body TEXT NOT NULL        -- 一整段正文：稿子要分段就拆成两条公告，不做多段 / 富文本
);

-- 安卓版本信息：**一行一个版本**，历史都留着。发新版 = INSERT 一行新记录，**不去改已有的行** ——
-- 改旧行等于篡改历史，而且手滑改错就把线上「最新版」退回上一版了。
--
-- 读接口挑「最新」的那行 = `ORDER BY version_code DESC, id DESC LIMIT 1`：
-- version_code 就是 App 里那个 versionCode、约定单调递增，所以「最大」即「最新」；
-- 次级键 id DESC 只是让「同一 version_code 被插了两次」这种意外下结果唯一确定，不靠它选版本。
--
-- 想改某一版的下载地址 / 补一条说明，就照 id 直接 UPDATE 那一行（见 app/README.md 的「怎么改」），
-- 别新插一行 —— 新插会多出一个和旧版同号的版本，反倒说不清哪行算数。
--
-- 更新说明（notes）做成每行的**一列**而不是另开一张表：它只属于那一版、没有独立生命周期，
-- 单独建表还得靠 version_code 关联回来，纯属多余。
-- 一列里存多行文本，约定**一行一条**（读接口按 \n 拆成数组）。手写 SQL 时不必写 \n 转义，
-- 用 '第一行' || char(10) || '第二行' 拼接即可（示例见 app/README.md 的「怎么改」）。
CREATE TABLE IF NOT EXISTS cf_app_version (
  id INTEGER PRIMARY KEY,
  version_name TEXT NOT NULL,    -- 给人看的版本名（如 1.3.2）
  version_code INTEGER NOT NULL, -- 比大小用这个：App 拿它与 BuildConfig.VERSION_CODE 比，严格更大才算有新版本
  download_url TEXT,             -- 可空：没定下载地址时 App 点「前往下载」只提示一句
  notes TEXT                     -- 更新说明，一行一条；可空 —— 空或没写时弹窗里整段省掉（连「更新说明」小标题一起）
);

-- 主页右上角菜单里的栏目，**一行一个网页**：title 与 url 都必填。
CREATE TABLE IF NOT EXISTS cf_menu (
  id INTEGER PRIMARY KEY,
  title TEXT NOT NULL,      -- 行上显示的文字（emoji 直接写在里面）
  url TEXT NOT NULL         -- 点一下在 App 内 WebView 里打开的地址
);
