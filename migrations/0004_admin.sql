-- 管理员标记：is_admin=1 表示管理员
ALTER TABLE users ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0;
