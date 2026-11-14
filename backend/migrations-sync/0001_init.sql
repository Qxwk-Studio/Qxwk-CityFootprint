-- City Footprint 同步库（独立 D1：qxwk-cityfootprint）
--
-- 只服务 App 同步与检查更新：
--   · 账户身份来自新版通行证 /auth/direct + /userinfo 的 sub UUID
--   · 不同步成就、照片文件和设备级设置
--   · 行程与手动打卡用 uuid 做跨端稳定标识，deleted_at 做墓碑
--   · sync_changes 提供增量拉取游标

CREATE TABLE IF NOT EXISTS users (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  passport_uuid  TEXT    NOT NULL,
  nickname       TEXT    NOT NULL,
  color          TEXT    NOT NULL,
  avatar         TEXT,
  created_at     TEXT    NOT NULL DEFAULT (datetime('now')),
  updated_at     TEXT    NOT NULL DEFAULT (datetime('now')),
  last_login_at  TEXT,
  CONSTRAINT uq_users_passport_uuid UNIQUE (passport_uuid),
  CONSTRAINT uq_users_nickname UNIQUE (nickname)
);

CREATE TABLE IF NOT EXISTS devices (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id       INTEGER NOT NULL,
  device_uuid   TEXT    NOT NULL,
  name          TEXT,
  platform      TEXT    NOT NULL DEFAULT 'android',
  app_version   TEXT,
  last_pull_seq INTEGER NOT NULL DEFAULT 0,
  created_at    TEXT    NOT NULL DEFAULT (datetime('now')),
  last_seen_at  TEXT    NOT NULL DEFAULT (datetime('now')),
  CONSTRAINT uq_devices_user_device UNIQUE (user_id, device_uuid),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS trips (
  id                 INTEGER PRIMARY KEY AUTOINCREMENT,
  uuid               TEXT    NOT NULL,
  user_id            INTEGER NOT NULL,
  title              TEXT    NOT NULL,
  note               TEXT,
  timeline           TEXT    NOT NULL,
  start_time         INTEGER NOT NULL,
  end_time           INTEGER NOT NULL,
  total_cost_cents   INTEGER NOT NULL DEFAULT 0,
  trip_state         INTEGER NOT NULL DEFAULT 2,
  client_modified_at INTEGER NOT NULL,
  server_modified_at INTEGER NOT NULL,
  deleted_at         INTEGER,
  revision           INTEGER NOT NULL DEFAULT 1,
  last_device_id     INTEGER,
  CONSTRAINT uq_trips_user_uuid UNIQUE (user_id, uuid),
  CHECK (length(title) BETWEEN 1 AND 23),
  CHECK (note IS NULL OR length(note) <= 233),
  CHECK (json_valid(timeline)),
  CHECK (end_time >= start_time),
  CHECK (total_cost_cents >= 0),
  CHECK (trip_state IN (0, 1, 2)),
  CHECK (revision >= 1),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (last_device_id) REFERENCES devices(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_trips_user_modified ON trips(user_id, client_modified_at);
CREATE INDEX IF NOT EXISTS idx_trips_user_deleted ON trips(user_id, deleted_at);

CREATE TABLE IF NOT EXISTS checkins (
  id                 INTEGER PRIMARY KEY AUTOINCREMENT,
  uuid               TEXT    NOT NULL,
  user_id            INTEGER NOT NULL,
  city_name          TEXT    NOT NULL,
  city_code          INTEGER,
  province           TEXT    NOT NULL,
  checkin_at         INTEGER NOT NULL,
  created_at         INTEGER NOT NULL,
  date_precision     INTEGER NOT NULL DEFAULT 2,
  note               TEXT,
  companions         TEXT,
  rating             INTEGER,
  amount_cents       INTEGER,
  stay_days          INTEGER,
  client_modified_at INTEGER NOT NULL,
  server_modified_at INTEGER NOT NULL,
  deleted_at         INTEGER,
  revision           INTEGER NOT NULL DEFAULT 1,
  last_device_id     INTEGER,
  CONSTRAINT uq_checkins_user_uuid UNIQUE (user_id, uuid),
  CHECK (length(city_name) BETWEEN 1 AND 30),
  CHECK (length(province) BETWEEN 1 AND 30),
  CHECK (date_precision IN (0, 1, 2, 3)),
  CHECK (note IS NULL OR length(note) <= 233),
  CHECK (companions IS NULL OR length(companions) <= 23),
  CHECK (rating IS NULL OR rating BETWEEN 1 AND 10),
  CHECK (amount_cents IS NULL OR amount_cents >= 0),
  CHECK (stay_days IS NULL OR stay_days BETWEEN 1 AND 3650),
  CHECK (revision >= 1),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (last_device_id) REFERENCES devices(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_checkins_user_checkin ON checkins(user_id, checkin_at);
CREATE INDEX IF NOT EXISTS idx_checkins_user_modified ON checkins(user_id, client_modified_at);
CREATE INDEX IF NOT EXISTS idx_checkins_user_deleted ON checkins(user_id, deleted_at);

CREATE TABLE IF NOT EXISTS sync_changes (
  seq         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER NOT NULL,
  entity_type TEXT    NOT NULL,
  entity_uuid TEXT    NOT NULL,
  op          TEXT    NOT NULL,
  device_id   INTEGER,
  changed_at  INTEGER NOT NULL,
  CHECK (entity_type IN ('trip', 'checkin')),
  CHECK (op IN ('upsert', 'delete')),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_sync_changes_user_seq ON sync_changes(user_id, seq);

CREATE TABLE IF NOT EXISTS server_info (
  id                   INTEGER PRIMARY KEY CHECK (id = 1),
  server_name          TEXT    NOT NULL,
  supports_photo_upload INTEGER NOT NULL DEFAULT 0,
  supported_versions   TEXT    NOT NULL DEFAULT '[20301,20303]',
  updated_at           TEXT    NOT NULL DEFAULT (datetime('now')),
  CHECK (supports_photo_upload IN (0, 1))
);

CREATE TABLE IF NOT EXISTS app_versions (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  version_name TEXT    NOT NULL,
  version_code INTEGER NOT NULL,
  download_url TEXT    NOT NULL,
  notes        TEXT,
  importance   INTEGER NOT NULL DEFAULT 0,
  CONSTRAINT uq_app_versions_code UNIQUE (version_code),
  CHECK (importance IN (0, 1, 2))
);

INSERT OR IGNORE INTO server_info (id, server_name, supports_photo_upload, supported_versions)
VALUES (1, 'City Footprint', 0, '[20301,20303]');

INSERT OR IGNORE INTO app_versions (version_name, version_code, download_url, notes, importance)
VALUES ('2.1.4', 20104, 'https://github.com/Qxwk-Studio/Qxwk-CityFootprint/releases/download/v2.1.4/CityFootprint-2.1.4.apk',
  '权限管理、存储空间与数据导出
中英文界面与城市、成就英文
底图中文地名覆盖全部城市
修复地图渲染、清理照片与语言切换问题', 0);
