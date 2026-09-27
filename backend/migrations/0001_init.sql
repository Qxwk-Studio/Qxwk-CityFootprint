-- 初始建表：用户 / 足迹
-- 表名：users（与 Qxwk-Blog **共享同一张表**，两仓共同维护）+ cf_visits（足迹，本站独占）
-- 认证（密码/会话/邀请码/系统设置）统一由通行证 account.qxwkstudio.top 处理；
-- 通行证侧曾有跨站 SSO / 跳转授权，现已整条下线，本站改为前端直调 /api/login 换 token

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nickname TEXT UNIQUE NOT NULL,
  color TEXT NOT NULL,                  -- 地图打点颜色
  avatar TEXT,                          -- 头像链接（通行证算好的 WeAvatar，无邮箱为 NULL）。
                                        -- 本站登录路径**不写**这一列（resolver 的 COLS / INSERT 都不含它），
                                        -- 由 Qxwk-Blog 侧登录时同步；/api/me 的头像直接取自通行证实时返回
  passport_id INTEGER,                  -- 通行证账号 userId：稳定身份，改昵称不变；早先按 nickname 映射，一改名就多一条本地行
  is_admin INTEGER NOT NULL DEFAULT 0,  -- 1 = 管理员
  created_at TEXT DEFAULT (datetime('now'))
);

-- passport_id 的唯一性靠显式索引：SQLite 的 ALTER TABLE 加不了 UNIQUE 列约束，
-- 线上旧表补列时也只能补成普通列，靠这条索引兜住（多行 NULL 是允许的，不影响 Qxwk-Blog 的老行）
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_passport ON users(passport_id);

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
  FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_visits_user ON cf_visits(user_id);
CREATE INDEX IF NOT EXISTS idx_visits_city ON cf_visits(city);