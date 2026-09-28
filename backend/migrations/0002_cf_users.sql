-- 用户表独立：users -> cf_users
--
-- 背景：users 原先与 Qxwk-Blog **共享**（那边把 users.avatar 当作者头像读），两站的行混在一张表里。
-- 本站改用自己独占的 cf_users：登录时不再覆盖共享行的昵称 / 颜色 / 头像，用户查询与统计也不必再
-- 绕开博客那边与本站无关的行。
--
-- 注意：users 表**保留不动**（博客还在用），只是本站从此不再读写它。副作用是博客 feed 拿不到
-- 本站用户的头像了（原先靠本站登录时把通行证头像同步进共享表），需要博客那边自己接通行证。
--
-- cf_visits.user_id 的外键必须跟着改指 cf_users：不重建的话，本站新建的用户（只存在于 cf_users）
-- 添加足迹时会被 `REFERENCES users(id)` 挡下（D1 默认开启外键检查）。

-- 重建表要「先删旧、再改名」，中间那一刻外键是悬空的，靠延迟检查兜住（D1 里重建带外键的表必须这么写）
PRAGMA defer_foreign_keys = on;

CREATE TABLE IF NOT EXISTS cf_users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nickname TEXT UNIQUE NOT NULL,
  color TEXT NOT NULL,                  -- 地图打点颜色（通行证统一分配，每次访问同步覆盖）
  avatar TEXT,                          -- 头像链接（通行证算好的 WeAvatar，无邮箱为 NULL）。
                                        -- 本站**不读**它（/api/me 直接用通行证实时返回的那份），
                                        -- 留着是给本站页面日后用的；同步给共享表的使命已随本次迁移结束
  passport_id INTEGER,                  -- 通行证账号 userId：稳定身份，改昵称不变
  is_admin INTEGER NOT NULL DEFAULT 0,  -- 1 = 管理员（改动见 README「管理员」一节）
  created_at TEXT DEFAULT (datetime('now'))
);

-- 同 users：SQLite 的 ALTER TABLE 加不了 UNIQUE 列约束，唯一性靠显式索引兜住（多行 NULL 是允许的）
CREATE UNIQUE INDEX IF NOT EXISTS idx_cf_users_passport ON cf_users(passport_id);

-- 只搬「本站自己的人」：users 里还躺着大量博客那边、与本站无关的行，全搬进来既没必要、
-- 也会让本站的 totalUsers 统计虚高。两类要留下：
--   ① 有足迹的人 —— cf_visits.user_id 指向他们，不搬的话外键立刻断
--   ② 管理员（is_admin = 1）—— 哪怕一条足迹都还没有也得留，否则标志丢了要重新去库里加
-- 显式带上 id，让 cf_visits.user_id 继续指得准
-- （往 AUTOINCREMENT 表插显式 id 会把自增序列抬到 max(id)，之后新建不会撞号）。
INSERT OR IGNORE INTO cf_users (id, nickname, color, avatar, passport_id, is_admin, created_at)
SELECT id, nickname, color, avatar, passport_id, is_admin, created_at
FROM users
WHERE is_admin = 1
   OR id IN (SELECT DISTINCT user_id FROM cf_visits);

-- 重建 cf_visits：只为改外键指向（SQLite 改不了已有表的外键）。
-- 列顺序与 0001_init.sql 一致，用列名显式列出而不是 SELECT *，将来加列也不会错位。
CREATE TABLE cf_visits_new (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  city TEXT NOT NULL,
  lat REAL NOT NULL,
  lng REAL NOT NULL,
  visit_date TEXT,                      -- 可空：记不清时间
  note TEXT,                            -- 可选备注
  is_private INTEGER NOT NULL DEFAULT 0,-- 1 = 不公开（本人可见）
  created_at TEXT DEFAULT (datetime('now')),
  FOREIGN KEY (user_id) REFERENCES cf_users(id)
);
INSERT INTO cf_visits_new (id, user_id, city, lat, lng, visit_date, note, is_private, created_at)
SELECT id, user_id, city, lat, lng, visit_date, note, is_private, created_at FROM cf_visits;
DROP TABLE cf_visits;
ALTER TABLE cf_visits_new RENAME TO cf_visits;

CREATE INDEX IF NOT EXISTS idx_visits_user ON cf_visits(user_id);
CREATE INDEX IF NOT EXISTS idx_visits_city ON cf_visits(city);