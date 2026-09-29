// 工具与通行证验证（Worker 版）
// 本站不再持有密码 / 会话，认证统一由通行证 account.qxwkstudio.top 处理
// 业务路由拿 Bearer token 去问通行证 /api/me，按通行证 userId（cf_users.passport_id）映射到本地用户

// 通行证地址（本地 dev 改 http://localhost:8787）
const PASSPORT_URL = 'https://account.qxwkstudio.top';

// headers 可选：给需要自带缓存声明的接口用（如 /api/geo 的 24h Cache-Control）。
// 默认不设任何缓存头 —— 其余接口带用户态数据，不该被缓存。
export function json(data, status = 200, headers = null) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...headers },
  });
}

export function error(message, status = 400) {
  return json({ error: message }, status);
}

// 「第一次到本站必须先验证邮箱」闸门（触发点在 resolveViewer ③）。
// 用抛错而不是返回值：resolveViewer 的调用方（getUserId / getViewer / /api/me）都只认
// 「有用户 / 无用户」两种结果，加第三种返回值得挨个改，消息还容易被中间层换成「未登录」；
// 抛出去由 worker.js 顶层统一翻成 403，每个路由都拿到同一句提示。
export class NeedEmailVerifyError extends Error {}

// 用 Bearer token 去通行证 /api/me 验证，返回 {userId, nickname, color, avatar, emailVerified} 或 null
async function getPassportUser(request) {
  const auth = request.headers.get('Authorization') || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!token) return null;
  try {
    const r = await fetch(`${PASSPORT_URL}/api/me`, {
      headers: { Authorization: 'Bearer ' + token },
    });
    if (!r.ok) return null;
    const u = await r.json();
    if (!u || !u.userId) return null;
    // emailVerified 一并带出来：本站首次登录的邮箱闸门要用（通行证 /api/me 就返回这个字段）
    return { userId: u.userId, nickname: u.nickname, color: u.color, avatar: u.avatar, emailVerified: !!u.email_verified };
  } catch {
    return null;
  }
}

// 通行证用户 -> 本地 CF 用户：按通行证 userId（cf_users.passport_id）认人，返回 {id, nickname, color, isAdmin, avatar} 或 null
async function resolveViewer(DB, request) {
  const p = await getPassportUser(request);
  if (!p) return null;
  // 昵称缺失/空时用稳定 fallback，避免 NULL 昵称行被反复新建导致 user 计数暴增
  // （cf_users.nickname 的唯一约束允许多个 NULL）
  const nickname = (p.nickname && String(p.nickname).trim()) || 'user_' + p.userId;
  const COLS = 'id, nickname, color, is_admin, avatar';

  // ① 首选按通行证 userId 认人：通行证里改昵称不会改 userId，所以这一列才是稳定身份。
  //    早先按 nickname 映射，用户在通行证改一次名，本站就多出一条新行、旧足迹留在旧行。
  let u = await DB.prepare(`SELECT ${COLS} FROM cf_users WHERE passport_id = ?`).bind(p.userId).first();

  // ② 存量归并：passport_id 上线前建的本地行这一列还是空的，首次用新逻辑登录时按昵称认领，
  //    把 passport_id 回填进去，保住这一行已有的足迹与 is_admin 标志。
  //    只认领 passport_id 为空的行；昵称在通行证全局唯一，同名的基本就是同一个人。
  //    已知不够（无需再想办法）：某人的本地昵称与通行证昵称已经不一致、且此前从没走过这条新逻辑时
  //    认不出来，会在 ③ 新建一行，旧行的足迹需要人工在库里合并。
  if (!u) {
    const legacy = await DB.prepare(`SELECT ${COLS} FROM cf_users WHERE nickname = ? AND passport_id IS NULL`)
      .bind(nickname).first();
    if (legacy) {
      await DB.prepare('UPDATE cf_users SET passport_id = ? WHERE id = ?').bind(p.userId, legacy.id).run();
      u = legacy;
    }
  }

  // ③ 真新用户才建行。不能用 `INSERT ... ON CONFLICT DO UPDATE` 做"幂等"：它即使走 UPDATE 分支
  //    也会消耗 AUTOINCREMENT 序列，登录页并发多个接口就会让 sqlite_sequence 虚增（行数却不变）。
  //    INSERT OR IGNORE：兜住并发首插竞争；冲突被忽略时不会消耗自增序列。
  if (!u) {
    // 首次登录本站（①② 都没命中，cf_users 里没有这一行）要求邮箱已验证。
    // 「之前登录过」以 **cf_users 里有没有行** 为准：② 认领回的老行也算，所以这条闸门
    // 只挡本站从没见过的新账号，现有用户不会被关在门外（他们在 ①② 就被认出来了）。
    // 邮箱验证只在通行证做（account.qxwkstudio.top），本站只是读 /api/me 带回的标记。
    // 提示语要写清去哪儿验证：用户在 App / 网页里只看到这一句，没有别的入口可点。
    if (!p.emailVerified) {
      throw new NeedEmailVerifyError('首次登录需要先验证邮箱：请到通行证（account.qxwkstudio.top）绑定邮箱并完成验证后再试');
    }
    // 建行前先腾昵称位：本站可能还挂着一行用着这个昵称、但它的主人已经改名（昵称在通行证里被本账号接手），
    // 那行要等它主人下次访问本站才由 ④ 改名。此处先把它改成 user_<它自己的通行证 id>——按 id 构造必然唯一、
    // 不会二次冲突；不改的话下面的 INSERT 会被 UNIQUE 挡掉，本账号就彻底登不进来了。
    const holder = await DB.prepare('SELECT id, passport_id FROM cf_users WHERE nickname = ?').bind(nickname).first();
    if (holder) {
      await DB.prepare('UPDATE cf_users SET nickname = ? WHERE id = ?')
        .bind('user_' + holder.passport_id, holder.id).run();
    }
    await DB.prepare('INSERT OR IGNORE INTO cf_users (nickname, color, passport_id, avatar) VALUES (?, ?, ?, ?)')
      .bind(nickname, p.color, p.userId, p.avatar).run();
    u = await DB.prepare(`SELECT ${COLS} FROM cf_users WHERE passport_id = ?`).bind(p.userId).first();
    if (!u) return null; // 极端并发下仍失败，放弃本次映射
  }

  // ④ 昵称 / 颜色 / 头像都由通行证说了算，每次访问同步覆盖，避免两端数据漂移。
  //    头像只写进本站自己的 cf_users（本站不读它，/api/me 直接用通行证实时返回的那份）——
  //    cf_users 独占后不再往与 Qxwk-Blog 共享的 users 表同步，博客 feed 的头像要那边自己接通行证。
  if (u.nickname !== nickname || u.color !== p.color || u.avatar !== p.avatar) {
    try {
      await DB.prepare('UPDATE cf_users SET nickname = ?, color = ?, avatar = ? WHERE id = ?')
        .bind(nickname, p.color, p.avatar, u.id).run();
      u.nickname = nickname;
    } catch {
      // 昵称撞上 cf_users.nickname 的 UNIQUE 约束：新昵称被本站另一行占着（对方也改了名、但还没访问过本站）。
      // 此时保留本站旧昵称、只同步颜色与头像，不能让一次登录直接 500。
      await DB.prepare('UPDATE cf_users SET color = ?, avatar = ? WHERE id = ?').bind(p.color, p.avatar, u.id).run();
    }
    u.color = p.color;
    u.avatar = p.avatar;
  }
  return { id: u.id, nickname: u.nickname, color: u.color, isAdmin: !!u.is_admin, avatar: p.avatar };
}

// 从 Authorization 解析本地用户 id（业务路由用，无 token / 验证失败返回 null）
export async function getUserId(DB, request) {
  const v = await resolveViewer(DB, request);
  return v ? v.id : null;
}

/**
 * 私密可见性过滤片段 —— 全站唯一一处（cities / city/{city} / stats 共用）。
 * 管理员无过滤；其余只放行「非私密的」或「自己的」。未登录时 viewer.userId 为 0，
 * 而 user_id 不会是 0，等价于只看非私密。
 *
 * 返回的 sql **不带** WHERE / AND 关键字 —— 因为落点有两处：
 *   - WHERE（普通查询）：`WHERE ${vis.sql}`
 *   - JOIN 的 ON：`LEFT JOIN cf_visits v ON v.user_id = u.id AND ${vis.sql}`
 *     落 ON 才不会把「零足迹的用户」从 LEFT JOIN 里整行滤掉（WHERE 里 NULL 比较为假）
 *
 * @param {{userId: number, isAdmin: boolean}} viewer
 * @param {string} isPrivateCol 限定过的列名，如 'v.is_private'
 * @param {string} userIdCol    限定过的列名，如 'v.user_id'
 * @returns {{sql: string, params: number[]}}
 */
export function visibility(viewer, isPrivateCol = 'is_private', userIdCol = 'user_id') {
  if (viewer.isAdmin) return { sql: '', params: [] };
  return { sql: `(${isPrivateCol} = 0 OR ${userIdCol} = ?)`, params: [viewer.userId] };
}

export { resolveViewer };
