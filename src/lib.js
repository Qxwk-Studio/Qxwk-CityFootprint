// 工具与通行证验证（Worker 版）
// 本站不再持有密码 / 会话，认证统一由通行证 account.qxwkstudio.top 处理
// 业务路由拿 Bearer token 去问通行证 /api/me，按通行证 userId（users.passport_id）映射到本地用户
// （跨站 SSO / 跳转授权已在通行证侧下线，本站改为前端直调通行证 /api/login 换 token）

// 通行证地址（本地 dev 改 http://localhost:8787）
const PASSPORT_URL = 'https://account.qxwkstudio.top';

export function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
}

export function error(message, status = 400) {
  return json({ error: message }, status);
}

// 用 Bearer token 去通行证 /api/me 验证，返回 {userId, nickname, color, avatar} 或 null
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
    return { userId: u.userId, nickname: u.nickname, color: u.color, avatar: u.avatar };
  } catch {
    return null;
  }
}

// 通行证用户 -> 本地 CF 用户：按通行证 userId（users.passport_id）认人，返回 {id, nickname, color, isAdmin, avatar} 或 null
async function resolveViewer(DB, request) {
  const p = await getPassportUser(request);
  if (!p) return null;
  // 昵称缺失/空时用稳定 fallback，避免 NULL 昵称行被反复新建导致 user 计数暴增
  // （users.nickname 的唯一约束允许多个 NULL）
  const nickname = (p.nickname && String(p.nickname).trim()) || 'user_' + p.userId;
  const COLS = 'id, nickname, color, is_admin';

  // ① 首选按通行证 userId 认人：通行证里改昵称不会改 userId，所以这一列才是稳定身份。
  //    早先按 nickname 映射，用户在通行证改一次名，本站就多出一条新行、旧足迹留在旧行。
  let u = await DB.prepare(`SELECT ${COLS} FROM users WHERE passport_id = ?`).bind(p.userId).first();

  // ② 存量归并：passport_id 上线前建的本地行这一列还是空的，首次用新逻辑登录时按昵称认领，
  //    把 passport_id 回填进去，保住这一行已有的足迹与 is_admin 标志。
  //    只认领 passport_id 为空的行；昵称在通行证全局唯一，同名的基本就是同一个人。
  //    已知不够（无需再想办法）：某人的本地昵称与通行证昵称已经不一致、且此前从没走过这条新逻辑时
  //    认不出来，会在 ③ 新建一行，旧行的足迹需要人工在库里合并。
  if (!u) {
    const legacy = await DB.prepare(`SELECT ${COLS} FROM users WHERE nickname = ? AND passport_id IS NULL`)
      .bind(nickname).first();
    if (legacy) {
      await DB.prepare('UPDATE users SET passport_id = ? WHERE id = ?').bind(p.userId, legacy.id).run();
      u = legacy;
    }
  }

  // ③ 真新用户才建行。不能用 `INSERT ... ON CONFLICT DO UPDATE` 做"幂等"：它即使走 UPDATE 分支
  //    也会消耗 AUTOINCREMENT 序列，登录页并发多个接口就会让 sqlite_sequence 虚增（users 行数却不变）。
  //    INSERT OR IGNORE：兜住并发首插竞争；冲突被忽略时不会消耗自增序列。
  if (!u) {
    // 建行前先腾昵称位：本站可能还挂着一行用着这个昵称、但它的主人已经改名（昵称在通行证里被本账号接手），
    // 那行要等它主人下次访问本站才由 ④ 改名。此处先把它改成 user_<它自己的通行证 id>——按 id 构造必然唯一、
    // 不会二次冲突；不改的话下面的 INSERT 会被 UNIQUE 挡掉，本账号就彻底登不进来了。
    const holder = await DB.prepare('SELECT id, passport_id FROM users WHERE nickname = ?').bind(nickname).first();
    if (holder) {
      await DB.prepare('UPDATE users SET nickname = ? WHERE id = ?')
        .bind('user_' + holder.passport_id, holder.id).run();
    }
    await DB.prepare('INSERT OR IGNORE INTO users (nickname, color, passport_id) VALUES (?, ?, ?)')
      .bind(nickname, p.color, p.userId).run();
    u = await DB.prepare(`SELECT ${COLS} FROM users WHERE passport_id = ?`).bind(p.userId).first();
    if (!u) return null; // 极端并发下仍失败，放弃本次映射
  }

  // ④ 昵称 / 颜色都由通行证说了算，每次访问同步覆盖，避免两端数据漂移。
  if (u.nickname !== nickname || u.color !== p.color) {
    try {
      await DB.prepare('UPDATE users SET nickname = ?, color = ? WHERE id = ?')
        .bind(nickname, p.color, u.id).run();
      u.nickname = nickname;
    } catch {
      // 昵称撞上 users.nickname 的 UNIQUE 约束：新昵称被本站另一行占着（对方也改了名、但还没访问过本站）。
      // 此时保留本站旧昵称、只同步颜色，不能让一次登录直接 500。
      await DB.prepare('UPDATE users SET color = ? WHERE id = ?').bind(p.color, u.id).run();
    }
    u.color = p.color;
  }
  return { id: u.id, nickname: u.nickname, color: u.color, isAdmin: !!u.is_admin, avatar: p.avatar };
}

// 从 Authorization 解析本地用户 id（业务路由用，无 token / 验证失败返回 null）
export async function getUserId(DB, request) {
  const v = await resolveViewer(DB, request);
  return v ? v.id : null;
}

export { resolveViewer };
