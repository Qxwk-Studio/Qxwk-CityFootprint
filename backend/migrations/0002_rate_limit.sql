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
