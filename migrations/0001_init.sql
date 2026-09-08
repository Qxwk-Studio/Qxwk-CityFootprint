-- 初始建表：用户 / 足迹（已合并 SSO 切接后的最终结构）
-- 表名最终为 cf_users / cf_visits（与 Qxwk-Blog 的表区分）
-- 认证（密码/会话/邀请码/系统设置）统一由通行证 account.qxwkstudio.top 处理

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nickname TEXT UNIQUE NOT NULL,
  color TEXT NOT NULL,                  -- 地图打点颜色
  avatar TEXT,                          -- 头像链接（通行证算好的 WeAvatar，登录时同步；无邮箱为 NULL）
  is_admin INTEGER NOT NULL DEFAULT 0,  -- 1 = 管理员
  created_at TEXT DEFAULT (datetime('now'))
);

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