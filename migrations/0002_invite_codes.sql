-- 一次性注册邀请码表
-- code 唯一；used_at 为空 = 未使用，注册成功后被标记为已使用（用后即焚）
CREATE TABLE IF NOT EXISTS invite_codes (
  code TEXT PRIMARY KEY,
  used_by INTEGER,                      -- 使用的用户 id
  used_at TEXT,                         -- 使用时间，NULL 表示未使用
  created_at TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_invite_used ON invite_codes(used_at);
