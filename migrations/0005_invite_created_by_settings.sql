-- 邀请码生成人溯源：created_by = 生成该码的用户 id（NULL = 管理员手工插入）
ALTER TABLE invite_codes ADD COLUMN created_by INTEGER;
CREATE INDEX IF NOT EXISTS idx_invite_created ON invite_codes(created_by);

-- 系统设置表（键值对）
CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
-- 邀请码生成开关（'1' 允许生成，'0' 暂停生成）
INSERT OR IGNORE INTO settings (key, value) VALUES ('invite_generate_enabled', '1');
-- 注册开关（'1' 允许注册，'0' 暂停注册）
INSERT OR IGNORE INTO settings (key, value) VALUES ('invite_register_enabled', '1');
