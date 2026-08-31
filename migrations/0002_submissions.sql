-- 投稿：未阔月刊投稿（管理员审稿后手动发布到 Qxwk-Files / issues.json）
-- 投稿接口挂在 cityfoot Worker 下，复用通行证登录鉴权（Bearer token）
-- 审稿权限使用 users.is_admin 判定

CREATE TABLE IF NOT EXISTS submissions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,             -- 投稿人（映射到本地 users）
  title TEXT NOT NULL,                  -- 标题
  category TEXT NOT NULL DEFAULT '',    -- 分类（可空）
  body TEXT NOT NULL,                   -- 正文
  contact TEXT,                         -- 联系方式（可选）
  status TEXT NOT NULL DEFAULT 'pending', -- pending / approved / rejected
  reviewer_id INTEGER,                  -- 审稿管理员（users.id）
  review_note TEXT,                     -- 审稿意见
  created_at TEXT DEFAULT (datetime('now')),
  reviewed_at TEXT,                     -- 审稿时间
  FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_submissions_user ON submissions(user_id);
CREATE INDEX IF NOT EXISTS idx_submissions_status ON submissions(status);