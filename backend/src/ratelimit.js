// 接口限速：**分钟级**走 Cloudflare 的 Rate Limiting 绑定，**天级**走 D1（见 migrations/0002_rate_limit.sql）。
//
// 为什么拆成两套：
//   · 分钟级是「防突发」—— 高频、每次请求都要判，用边缘的绑定最省（不落库、不占 D1 额度，
//     也天然不受「Workers 多 isolate、进程内计数不可靠」的影响）。它只能给 10s / 60s 窗口，
//     做不了天级。
//   · 天级是「防总量」—— 必须持久化，本项目唯一持久存储是 D1，一次 upsert 就够。
//
// 维度：写接口按**登录用户**（写接口本来就要求登录，userId 稳定、不受 NAT 影响）；
// 读接口只能按**IP**（/api/cities、/api/stats 这些匿名就能访问，没有 userId 可用）。
// 每个请求只落一种 key，不是双重计数。
//
// 管理员**不豁免**，与普通用户同一档（这是有意选择，不是漏写）。

import { json } from './lib.js';

// 限速文案。两端都是**原样透出**后端 error 字段的（网页 docs/app.js 抛 new Error(data.error)、
// 安卓 data/ApiException.kt 优先取报文里的 error），所以这里写的就是用户最终看到的那句话，
// 别写成「rate limit exceeded」那类给开发看的措辞。
export const MSG_WRITE_MINUTE = '操作太频繁了，请稍后再试';
export const MSG_WRITE_DAY = '今天添加的行程太多了，请明天再试';
export const MSG_READ = '请求太频繁了，请稍后再试';

// 写接口的天级上限。分钟档同时写在 wrangler.toml 的 RL_WRITE（limit / period）里 —— 两处要一起改。
export const WRITE_DAY_LIMIT = 200;

/**
 * 分钟级计数：过一次绑定就消耗一个配额。key 由调用方拼（`u:<userId>` / `ip:<IP>`）。
 *
 * **fail open**：绑定没配（旧版 wrangler 静默丢弃了配置、或本地 dev）或调用自己抛错，
 * 一律放行 —— 限速是保护措施，它坏了不该把正常用户挡在门外。
 *
 * 但绑定缺失必须留痕：那种情况下分钟档是**整体失效**的（不只这一次），只在日志里说一次，
 * 免得每个被放行的请求都刷一行；`wrangler tail` 里看到这行就说明配置掉了，得去查 wrangler.toml。
 * 调用自己抛错不记 —— 那是运行时抖动，不是配置问题。
 *
 * @returns {Promise<boolean>} true = 放行
 */
export async function allowMinute(env, binding, key) {
  const limiter = env && env[binding];
  if (!limiter) {
    if (!bindingWarned.has(binding)) {
      bindingWarned.add(binding);
      console.warn(`[ratelimit] 绑定 ${binding} 未配置，分钟档已跳过（fail open）`);
    }
    return true;
  }
  try {
    const { success } = await limiter.limit({ key });
    return !!success;
  } catch {
    return true;
  }
}

// 已经就「绑定缺失」告警过的 binding。模块级 Set：每个 isolate 各记各的，只用来去重日志。
const bindingWarned = new Set();

/**
 * 天级窗口还剩多少秒到点：key 用的是 SQL 的 date('now')（UTC），下个窗口从**明天 UTC 零点**开始。
 * 别写死 60 —— 那等于骗客户端「一分钟后再试」，其实还是要被挡到第二天。
 */
function secondsUntilUtcMidnight() {
  const now = Date.now();
  const d = new Date(now);
  return Math.max(1, Math.ceil((Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1) - now) / 1000));
}

/**
 * 写接口的一次完整判定：**先判分钟、再动 D1**。
 * 顺序是有意的 —— 被分钟档挡下的请求不再去写天级计数，否则刷子照样能靠每次请求多写一行 D1 来消耗额度。
 *
 * @returns {Promise<Response|null>} 拦截时回一个可直接 return 的 429；放行回 null
 */
export async function checkWriteLimit(env, DB, userId) {
  if (!(await allowMinute(env, 'RL_WRITE', 'u:' + userId))) {
    return json({ error: MSG_WRITE_MINUTE }, 429, { 'Retry-After': '60' });
  }

  // 一步原子「先加再读回」：两个并发请求各加各的，各自读回自己的名次，谁都绕不过去。
  // 换成「先 SELECT 再 UPDATE」的话，并发时两边会双双读到「最后一个名额」而一起放行。
  // day 直接用 SQL 的 date('now')（UTC），不在 JS 里算 —— 保证与库里其它时间同一时钟口径。
  const row = await DB.prepare(
    `INSERT INTO cf_rate_daily (key, day, count) VALUES (?, date('now'), 1)
     ON CONFLICT(key, day) DO UPDATE SET count = count + 1
     RETURNING count`
  ).bind('w:' + userId).first();

  if (row && row.count > WRITE_DAY_LIMIT) {
    return json({ error: MSG_WRITE_DAY }, 429, { 'Retry-After': String(secondsUntilUtcMidnight()) });
  }
  return null;
}
