// City Footprint API（App 专用）
//
// 认证：POST /auth/direct（通行证服务端登录）+ GET /userinfo
// 数据：qxwk-cityfootprint D1
// 当前阶段只提供登录、资料、服务器信息和检查更新；同步接口下一步接。

import { handleSyncPull, handleSyncPush, handleSyncRegister } from './sync.js';
import { CITY_COORDINATES } from './city-coordinates.js';

const JSON_TYPE = 'application/json; charset=utf-8';

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': JSON_TYPE,
      'Cache-Control': 'no-store',
      'Access-Control-Allow-Origin': '*',
    },
  });
}

function fail(status, code, message) {
  return json({ error: message, code }, status);
}

function passportBase(env) {
  return String(env.PASSPORT_BASE || 'https://account.qxwkstudio.top').replace(/\/+$/, '');
}

function passportConfig(env) {
  const id = String(env.PASSPORT_CLIENT_ID || '');
  const secret = String(env.PASSPORT_CLIENT_SECRET || '');
  return id && secret ? { id, secret } : null;
}

async function passportDirect(env, account, password) {
  const cfg = passportConfig(env);
  if (!cfg) return { error: 'server_not_configured' };
  const basic = btoa(`${cfg.id}:${cfg.secret}`);
  const res = await fetch(`${passportBase(env)}/auth/direct`, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${basic}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ account, password }),
    signal: AbortSignal.timeout(10000),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    return { error: body.code || (res.status === 429 ? 'rate_limited' : 'invalid_credentials') };
  }
  return res.json();
}

async function passportUserinfo(env, token) {
  const res = await fetch(`${passportBase(env)}/userinfo`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(10000),
  });
  if (!res.ok) return null;
  return res.json().catch(() => null);
}

async function upsertUser(DB, info) {
  const sub = String(info.sub || '').trim();
  if (!sub) return null;
  const passportName = String(info.name || '').trim();
  const color = String(info.color || '#2563EB');
  const avatar = info.picture ? String(info.picture) : null;

  const existing = await DB.prepare('SELECT * FROM users WHERE passport_uuid = ?')
    .bind(sub)
    .first();

  if (existing) {
    let nickname = existing.nickname;
    if (passportName && passportName !== existing.nickname) {
      const conflict = await DB.prepare('SELECT id FROM users WHERE nickname = ? AND id <> ?')
        .bind(passportName, existing.id)
        .first();
      if (!conflict) nickname = passportName;
    }
    await DB.prepare(
      `UPDATE users
       SET nickname = ?, color = ?, avatar = ?, updated_at = datetime('now'), last_login_at = datetime('now')
       WHERE id = ?`,
    ).bind(nickname, color, avatar, existing.id).run();
    return DB.prepare('SELECT * FROM users WHERE id = ?').bind(existing.id).first();
  }

  let nickname = passportName || `user-${sub.slice(0, 8)}`;
  const conflict = await DB.prepare('SELECT id FROM users WHERE nickname = ?').bind(nickname).first();
  if (conflict) nickname = `${nickname}-${sub.slice(0, 8)}`;

  const result = await DB.prepare(
    `INSERT INTO users (passport_uuid, nickname, color, avatar, last_login_at)
     VALUES (?, ?, ?, ?, datetime('now'))`,
  ).bind(sub, nickname, color, avatar).run();
  return DB.prepare('SELECT * FROM users WHERE id = ?').bind(result.meta.last_row_id).first();
}

function publicUser(user) {
  return {
    uuid: user.passport_uuid,
    nickname: user.nickname,
    color: user.color,
    avatar: user.avatar,
    created_at: user.created_at,
  };
}

async function requireUser(request, env) {
  const header = request.headers.get('Authorization') || '';
  if (!header.startsWith('Bearer ')) return null;
  const info = await passportUserinfo(env, header.slice(7).trim());
  if (!info) return null;
  return upsertUser(env.DB, info);
}


function stripPhotoPaths(value) {
  if (Array.isArray(value)) {
    value.forEach(stripPhotoPaths);
    return;
  }
  if (!value || typeof value !== 'object') return;
  for (const key of Object.keys(value)) {
    if (key === 'photoPath' || key === 'photo_path' || key === 'photo') {
      delete value[key];
    } else {
      stripPhotoPaths(value[key]);
    }
  }
}

function publicTimeline(raw) {
  try {
    const value = typeof raw === 'string' ? JSON.parse(raw) : raw;
    stripPhotoPaths(value);
    return JSON.stringify(value);
  } catch {
    return '';
  }
}


async function handlePublicGeo(request, env, adcode) {
  if (request.method !== 'GET') return fail(405, 'method_not_allowed', '仅支持 GET');
  if (!/^\d{6,9}$/.test(adcode)) return fail(400, 'invalid_adcode', 'adcode 不合法');
  const upstream = `https://geo.datav.aliyun.com/areas_v3/bound/${adcode}.json`;
  const cacheKey = new Request(upstream);
  let cached = null;
  try { cached = await caches.default.match(cacheKey); } catch { /* 缓存不可用时直连 */ }
  if (cached) {
    const body = await cached.text();
    return new Response(body, {
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'public, max-age=604800',
        'Access-Control-Allow-Origin': '*',
      },
    });
  }
  const res = await fetch(upstream, { signal: AbortSignal.timeout(10000) });
  if (!res.ok) return fail(404, 'not_found', '未找到该城市边界');
  const body = await res.text();
  try {
    const toCache = new Response(body, {
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'public, max-age=604800',
      },
    });
    await caches.default.put(cacheKey, toCache);
  } catch { /* 缓存失败不影响本次返回 */ }
  return new Response(body, {
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'public, max-age=604800',
      'Access-Control-Allow-Origin': '*',
    },
  });
}

async function handlePublicMap(request, env) {
  if (request.method !== 'GET') return fail(405, 'method_not_allowed', '仅支持 GET');
  const users = await env.DB.prepare('SELECT id, passport_uuid, nickname, color FROM users').all();
  const trips = await env.DB.prepare('SELECT user_id, timeline, start_time FROM trips WHERE deleted_at IS NULL').all();
  const checkins = await env.DB.prepare(
    'SELECT user_id, city_name, province, checkin_at, date_precision FROM checkins WHERE deleted_at IS NULL',
  ).all();

  const owners = new Map((users.results || []).map((u) => [u.id, u]));
  const cities = new Map();

  function add(cityName, province, userId, at, precision) {
    const name = String(cityName || '').trim();
    if (!name) return;
    const owner = owners.get(userId);
    if (!owner) return;
    let entry = cities.get(name);
    if (!entry) {
      const coord = CITY_COORDINATES[name] || {};
      entry = {
        city: name,
        province: province || coord.province || '',
        lat: Number.isFinite(coord.lat) ? coord.lat : null,
        lng: Number.isFinite(coord.lng) ? coord.lng : null,
        adcode: Number.isFinite(Number(coord.adcode)) ? Number(coord.adcode) : null,
        count: 0,
        users: new Map(),
        latest: [],
      };
      cities.set(name, entry);
    }
    entry.count += 1;
    entry.users.set(owner.passport_uuid, {
      uuid: owner.passport_uuid,
      nickname: owner.nickname,
      color: owner.color,
    });
    const p = Number(precision) || 3;
    entry.latest.push({
      nickname: owner.nickname,
      color: owner.color,
      // 时间不详没有真实日期：排序键按 9999-01-01 处理，排在最后（倒序时最前）。
      at: p === 3 ? 253402214400000 : (Number(at) || 0),
      precision: p,
    });
  }

  for (const c of checkins.results || []) {
    add(c.city_name, c.province, c.user_id, c.checkin_at, c.date_precision);
  }
  for (const trip of trips.results || []) {
    try {
      const timeline = typeof trip.timeline === 'string' ? JSON.parse(trip.timeline) : trip.timeline;
      for (const item of timeline?.items || []) {
        if (item?.type === 'visit' && item.text) {
          add(item.text, null, trip.user_id, trip.start_time, 2);
        }
      }
    } catch {
      // 损坏的时间线不影响其他行程
    }
  }

  const points = [...cities.values()]
    .filter((c) => c.lat !== null && c.lng !== null)
    .map((c) => {
      const latest = c.latest.sort((a, b) => b.at - a.at).slice(0, 10);
      return {
        city: c.city,
        province: c.province,
        lat: c.lat,
        lng: c.lng,
        adcode: c.adcode,
        count: c.count,
        color: latest[0]?.color || '#0891b2',
        users: [...c.users.values()],
        latest,
      };
    })
    .sort((a, b) => b.count - a.count || a.city.localeCompare(b.city, 'zh-CN'));

  return json({
    cities: points,
    total_cities: points.length,
    total_users: new Set(points.flatMap((p) => p.users.map((u) => u.uuid))).size,
  });
}

async function handlePublicUser(request, env, uuid) {
  if (request.method !== 'GET') return fail(405, 'method_not_allowed', '仅支持 GET');
  const row = await env.DB.prepare(
    'SELECT passport_uuid, nickname, color, avatar, created_at FROM users WHERE passport_uuid = ?',
  ).bind(uuid).first();
  if (!row) return fail(404, 'not_found', '未找到该用户');
  const stats = await env.DB.prepare(
    `SELECT
       (SELECT COUNT(*) FROM trips WHERE user_id = (SELECT id FROM users WHERE passport_uuid = ?) AND deleted_at IS NULL) AS trip_count,
       (SELECT COUNT(DISTINCT city_name) FROM checkins WHERE user_id = (SELECT id FROM users WHERE passport_uuid = ?) AND deleted_at IS NULL) AS city_count`,
  ).bind(uuid, uuid).first();
  return json({
    user: {
      uuid: row.passport_uuid,
      nickname: row.nickname,
      color: row.color,
      avatar: row.avatar,
      created_at: row.created_at,
      trip_count: stats?.trip_count || 0,
      city_count: stats?.city_count || 0,
    },
  });
}

async function handlePublicTrip(request, env, uuid) {
  if (request.method !== 'GET') return fail(405, 'method_not_allowed', '仅支持 GET');
  const row = await env.DB.prepare(
    'SELECT * FROM trips WHERE uuid = ? AND deleted_at IS NULL LIMIT 1',
  ).bind(uuid).first();
  if (!row) return fail(404, 'not_found', '未找到该行程');
  const owner = await env.DB.prepare(
    'SELECT passport_uuid, nickname, color, avatar FROM users WHERE id = ?',
  ).bind(row.user_id).first();
  return json({
    trip: {
      uuid: row.uuid,
      title: row.title,
      note: row.note,
      timeline: publicTimeline(row.timeline),
      start_time: row.start_time,
      end_time: row.end_time,
      total_cost_cents: row.total_cost_cents,
      trip_state: row.trip_state,
      client_modified_at: row.client_modified_at,
      server_modified_at: row.server_modified_at,
    },
    user: owner ? {
      uuid: owner.passport_uuid,
      nickname: owner.nickname,
      color: owner.color,
      avatar: owner.avatar,
    } : null,
  });
}

async function handlePublicCheckin(request, env, uuid) {
  if (request.method !== 'GET') return fail(405, 'method_not_allowed', '仅支持 GET');
  const row = await env.DB.prepare(
    'SELECT * FROM checkins WHERE uuid = ? AND deleted_at IS NULL LIMIT 1',
  ).bind(uuid).first();
  if (!row) return fail(404, 'not_found', '未找到该打卡');
  const owner = await env.DB.prepare(
    'SELECT passport_uuid, nickname, color, avatar FROM users WHERE id = ?',
  ).bind(row.user_id).first();
  return json({
    checkin: {
      uuid: row.uuid,
      city_name: row.city_name,
      city_code: row.city_code,
      province: row.province,
      checkin_at: row.checkin_at,
      created_at: row.created_at,
      date_precision: row.date_precision,
      note: row.note,
      companions: row.companions,
      rating: row.rating,
      amount_cents: row.amount_cents,
      stay_days: row.stay_days,
      client_modified_at: row.client_modified_at,
      server_modified_at: row.server_modified_at,
    },
    user: owner ? {
      uuid: owner.passport_uuid,
      nickname: owner.nickname,
      color: owner.color,
      avatar: owner.avatar,
    } : null,
  });
}

async function withUser(request, env, fn) {
  const user = await requireUser(request, env);
  if (!user) return fail(401, 'unauthorized', '登录已失效');
  return fn(request, env, user);
}

async function readJson(request) {
  try {
    return await request.json();
  } catch {
    return null;
  }
}

async function handleLogin(request, env) {
  if (request.method !== 'POST') return fail(405, 'method_not_allowed', '仅支持 POST');
  const contentType = request.headers.get('Content-Type') || '';
  if (!contentType.toLowerCase().startsWith('application/json')) {
    return fail(415, 'unsupported_media_type', '请求格式不正确');
  }
  const body = await readJson(request);
  const account = String(body?.account || '').trim();
  const password = String(body?.password || '');
  if (!account || !password) return fail(400, 'invalid_credentials', '账号或密码为空');

  const token = await passportDirect(env, account, password);
  if (!token || token.error) {
    if (token?.error === 'server_not_configured') {
      return fail(500, 'server_not_configured', '服务器未配置通行证客户端');
    }
    return fail(401, 'invalid_credentials', '账号或密码错误');
  }

  const info = await passportUserinfo(env, token.access_token);
  if (!info) return fail(502, 'passport_unavailable', '通行证资料读取失败');
  const user = await upsertUser(env.DB, info);
  if (!user) return fail(502, 'passport_invalid', '通行证未返回用户标识');

  return json({
    access_token: token.access_token,
    token_type: token.token_type || 'Bearer',
    expires_in: token.expires_in || 0,
    user: publicUser(user),
  });
}

async function handleMe(request, env) {
  if (request.method !== 'GET') return fail(405, 'method_not_allowed', '仅支持 GET');
  const user = await requireUser(request, env);
  if (!user) return fail(401, 'unauthorized', '登录已失效');
  return json({ user: publicUser(user) });
}

async function handleServer(request, env) {
  if (request.method !== 'GET') return fail(405, 'method_not_allowed', '仅支持 GET');
  const row = await env.DB.prepare(
    'SELECT server_name, supports_photo_upload FROM server_info WHERE id = 1',
  ).first();
  return json({
    server_name: row?.server_name || 'City Footprint',
    supports_photo_upload: !!row?.supports_photo_upload,
  });
}

async function handleManifest(request, env) {
  if (request.method !== 'GET') return fail(405, 'method_not_allowed', '仅支持 GET');
  const row = await env.DB.prepare(
    `SELECT version_name, version_code, download_url, notes, importance
     FROM app_versions ORDER BY version_code DESC LIMIT 1`,
  ).first();
  const notes = row?.notes ? String(row.notes).split(/\r?\n/).filter(Boolean) : [];
  return json({
    android: {
      version_name: row?.version_name || '',
      version_code: row?.version_code || 0,
      download_url: row?.download_url || '',
      notes,
      importance: row?.importance || 0,
    },
  });
}

async function handleHealth(request, env) {
  if (request.method !== 'GET') return fail(405, 'method_not_allowed', '仅支持 GET');
  const row = await env.DB.prepare('SELECT 1 AS ok').first();
  return json({ ok: row?.ok === 1 });
}

function route(request, env) {
  const url = new URL(request.url);
  if (request.method === 'OPTIONS') {
    return new Response(null, {
      status: 204,
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'Authorization, Content-Type',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      },
    });
  }
  if (url.pathname === '/api/auth/login') return handleLogin(request, env);
  if (url.pathname === '/api/auth/me') return handleMe(request, env);
  if (url.pathname === '/api/server') return handleServer(request, env);
  if (url.pathname === '/api/map') return handlePublicMap(request, env);

  const geoMatch = url.pathname.match(/^\/api\/geo\/([^/]+)$/);
  if (geoMatch) {
    const adcode = geoMatch[1];
    return handlePublicGeo(request, env, adcode);
  }

  const userMatch = url.pathname.match(/^\/api\/users\/([^/]+)$/);
  if (userMatch) {
    let uuid = '';
    try { uuid = decodeURIComponent(userMatch[1]); } catch { return fail(400, 'invalid_uuid', 'UUID 不合法'); }
    return handlePublicUser(request, env, uuid);
  }

  const tripMatch = url.pathname.match(/^\/api\/trips\/([^/]+)$/);
  if (tripMatch) {
    let uuid = '';
    try { uuid = decodeURIComponent(tripMatch[1]); } catch { return fail(400, 'invalid_uuid', 'UUID 不合法'); }
    return handlePublicTrip(request, env, uuid);
  }

  const checkinMatch = url.pathname.match(/^\/api\/checkins\/([^/]+)$/);
  if (checkinMatch) {
    let uuid = '';
    try { uuid = decodeURIComponent(checkinMatch[1]); } catch { return fail(400, 'invalid_uuid', 'UUID 不合法'); }
    return handlePublicCheckin(request, env, uuid);
  }
  if (url.pathname === '/api/manifest') return handleManifest(request, env);
  if (url.pathname === '/api/health') return handleHealth(request, env);
  if (url.pathname.startsWith('/api/sync/')) {
    return withUser(request, env, async (req, e, user) => {
      if (url.pathname === '/api/sync/register') return handleSyncRegister(req, e, user);
      if (url.pathname === '/api/sync/push') return handleSyncPush(req, e, user);
      if (url.pathname === '/api/sync/pull') return handleSyncPull(req, e, user);
      return fail(404, 'not_found', '同步接口不存在');
    });
  }
  return fail(404, 'not_found', '接口不存在');
}

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (e) {
      console.error('unhandled error:', e && e.stack ? e.stack : e);
      return fail(500, 'internal_error', '服务器内部错误');
    }
  },
};
