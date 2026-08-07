-- 行程可见性：is_private=1 表示不公开
-- 不公开的行程仅本人可见，不出现在地图 / 公开资料 / 全站统计中
ALTER TABLE visits ADD COLUMN is_private INTEGER NOT NULL DEFAULT 0;
