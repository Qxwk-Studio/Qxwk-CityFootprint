-- 初始建表：用户 / 足迹
-- 认证（密码/会话/邀请码/系统设置）统一由通行证 account.qxwkstudio.top 处理；
-- 通行证侧曾有跨站 SSO / 跳转授权，现已整条下线，本站改为前端直调 /api/login 换 token
--
-- 本文件已合并原 0002_cf_users.sql（两版线上都已应用完，合并只为让全新库一步到位）：
--   · cf_users —— 本站独占的用户表，登录时不再往共享 users 里覆盖昵称 / 颜色 / 头像
--   · cf_visits —— 直接建成最终形态：外键指 cf_users，含 adcode / transport 与 CHECK 约束；

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
