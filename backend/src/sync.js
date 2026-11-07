// App 同步接口：设备注册、增量拉取、批量推送。
//
// 同步集合只有 trips（行程）与 checkins（手动打卡）；成就由客户端计算，照片不同步。
// 冲突策略：客户端带 base revision；与服务端一致才写入，否则返回 conflict + 服务端当前数据。

const MAX_CHANGES = 100;
const MAX_PULL = 200;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

function fail(status, code, message) {
  return json({ error: message, code }, status);
}

function asInt(value, fallback = 0) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.trunc(n) : fallback;
}

function asOptionalInt(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  if (!Number.isFinite(n)) return null;
  return Math.trunc(n);
}

function asText(value, fallback = '') {
  return value === null || value === undefined ? fallback : String(value);
}

function asOptionalText(value) {
  if (value === null || value === undefined) return null;
  const s = String(value);
  return s.length === 0 ? null : s;
}

function tripData(row) {
  return {
    uuid: row.uuid,
    title: row.title,
    note: row.note,
    timeline: row.timeline,
    start_time: row.start_time,
    end_time: row.end_time,
    total_cost_cents: row.total_cost_cents,
    trip_state: row.trip_state,
    client_modified_at: row.client_modified_at,
    server_modified_at: row.server_modified_at,
    deleted_at: row.deleted_at,
    revision: row.revision,
    last_device_id: row.last_device_id,
  };
}

function checkinData(row) {
  return {
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
    deleted_at: row.deleted_at,
    revision: row.revision,
    last_device_id: row.last_device_id,
  };
}

async function ensureDevice(DB, userId, body) {
  const uuid = asText(body?.device_uuid).trim();
  if (!uuid || uuid.length > 128) throw new Error('invalid_device');
  const name = asOptionalText(body?.name);
  const platform = asText(body?.platform, 'android').slice(0, 32);
  const appVersion = asOptionalText(body?.app_version);

  const existing = await DB.prepare(
    'SELECT id, last_pull_seq FROM devices WHERE user_id = ? AND device_uuid = ?',
  ).bind(userId, uuid).first();

  if (existing) {
    await DB.prepare(
      `UPDATE devices
       SET name = COALESCE(?, name), platform = ?, app_version = COALESCE(?, app_version),
           last_seen_at = datetime('now')
       WHERE id = ?`,
    ).bind(name, platform, appVersion, existing.id).run();
    return { id: existing.id, uuid, last_pull_seq: existing.last_pull_seq || 0 };
  }

  const result = await DB.prepare(
    `INSERT INTO devices (user_id, device_uuid, name, platform, app_version, last_seen_at)
     VALUES (?, ?, ?, ?, ?, datetime('now'))`,
  ).bind(userId, uuid, name, platform, appVersion).run();
  return { id: result.meta.last_row_id, uuid, last_pull_seq: 0 };
}

function badChange(message) {
  const e = new Error(message);
  e.code = 'invalid_change';
  return e;
}

async function logChange(DB, userId, entityType, entityUuid, op, deviceId, changedAt) {
  return DB.prepare(
    `INSERT INTO sync_changes (user_id, entity_type, entity_uuid, op, device_id, changed_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
  ).bind(userId, entityType, entityUuid, op, deviceId, changedAt);
}


export function normalizeTripData(data) {
  if (!data || typeof data !== 'object') throw badChange('invalid_trip');
  const title = asText(data.title).trim();
  const note = asOptionalText(data.note);
  const timeline = asText(data.timeline);
  const startTime = asInt(data.start_time, NaN);
  const endTime = asInt(data.end_time, NaN);
  const totalCost = asInt(data.total_cost_cents, 0);
  const tripState = asInt(data.trip_state, 2);
  const clientModified = asInt(data.client_modified_at, NaN);
  if (!title || title.length > 23) throw badChange('invalid_title');
  if (note && note.length > 233) throw badChange('invalid_note');
  if (!timeline) throw badChange('invalid_timeline');
  try {
    const parsed = JSON.parse(timeline);
    if (!parsed || typeof parsed !== 'object') throw new Error('not object');
  } catch {
    throw badChange('invalid_timeline');
  }
  if (!Number.isFinite(startTime) || !Number.isFinite(endTime) || endTime < startTime) {
    throw badChange('invalid_time');
  }
  if (!Number.isFinite(clientModified)) throw badChange('invalid_client_modified_at');
  if (totalCost < 0) throw badChange('invalid_cost');
  if (tripState < 0 || tripState > 2) throw badChange('invalid_trip_state');
  return { title, note, timeline, startTime, endTime, totalCost, tripState, clientModified };
}

export function normalizeCheckinData(data) {
  if (!data || typeof data !== 'object') throw badChange('invalid_checkin');
  const cityName = asText(data.city_name).trim();
  const province = asText(data.province).trim();
  const checkinAt = asInt(data.checkin_at, NaN);
  const createdAt = asInt(data.created_at, NaN);
  const clientModified = asInt(data.client_modified_at, NaN);
  const datePrecision = asInt(data.date_precision, 2);
  const note = asOptionalText(data.note);
  const companions = asOptionalText(data.companions);
  const rating = asOptionalInt(data.rating);
  const amountCents = asOptionalInt(data.amount_cents);
  const stayDays = asOptionalInt(data.stay_days);
  const cityCode = asOptionalInt(data.city_code);
  if (cityName.length < 1 || cityName.length > 30) throw badChange('invalid_city');
  if (province.length < 1 || province.length > 30) throw badChange('invalid_province');
  if (!Number.isFinite(checkinAt) || !Number.isFinite(createdAt) || !Number.isFinite(clientModified)) {
    throw badChange('invalid_time');
  }
  if (datePrecision < 0 || datePrecision > 3) throw badChange('invalid_date_precision');
  if (note && note.length > 233) throw badChange('invalid_note');
  if (companions && companions.length > 23) throw badChange('invalid_companions');
  if (rating !== null && (rating < 1 || rating > 10)) throw badChange('invalid_rating');
  if (amountCents !== null && amountCents < 0) throw badChange('invalid_amount');
  if (stayDays !== null && (stayDays < 1 || stayDays > 3650)) throw badChange('invalid_stay_days');
  return {
    cityName, province, checkinAt, createdAt, clientModified, datePrecision,
    note, companions, rating, amountCents, stayDays, cityCode,
  };
}

async function applyTrip(env, user, device, change) {
  const uuid = asText(change.uuid).trim();
  if (!uuid) throw badChange('missing_uuid');
  const existing = await env.DB.prepare(
    'SELECT * FROM trips WHERE user_id = ? AND uuid = ?',
  ).bind(user.id, uuid).first();

  if (change.op === 'delete') {
    if (!existing) return { status: 'ignored', reason: 'not_found' };
    if (asInt(change.revision, -1) !== existing.revision) {
      return { status: 'conflict', server_revision: existing.revision, data: tripData(existing) };
    }
    const now = Date.now();
    const revision = existing.revision + 1;
    await env.DB.batch([
      env.DB.prepare(
        `UPDATE trips
         SET deleted_at = ?, server_modified_at = ?, revision = ?, last_device_id = ?
         WHERE id = ?`,
      ).bind(now, now, revision, device.id, existing.id),
      await logChange(env.DB, user.id, 'trip', uuid, 'delete', device.id, now),
    ]);
    return { status: 'applied', server_revision: revision, server_modified_at: now };
  }

  if (change.op !== 'upsert') throw badChange('invalid_op');
  const payload = normalizeTripData(change.data || {});
  const { title, note, timeline, startTime, endTime, totalCost, tripState, clientModified } = payload;
  if (existing && asInt(change.revision, -1) !== existing.revision) {
    return { status: 'conflict', server_revision: existing.revision, data: tripData(existing) };
  }

  const now = Date.now();
  const revision = existing ? existing.revision + 1 : 1;
  if (existing) {
    await env.DB.batch([
      env.DB.prepare(
        `UPDATE trips
         SET title = ?, note = ?, timeline = ?, start_time = ?, end_time = ?,
             total_cost_cents = ?, trip_state = ?, client_modified_at = ?,
             server_modified_at = ?, deleted_at = NULL, revision = ?, last_device_id = ?
         WHERE id = ?`,
      ).bind(
        title, note, timeline, startTime, endTime, totalCost, tripState,
        clientModified, now, revision, device.id, existing.id,
      ),
      await logChange(env.DB, user.id, 'trip', uuid, 'upsert', device.id, now),
    ]);
  } else {
    await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO trips
         (uuid, user_id, title, note, timeline, start_time, end_time, total_cost_cents,
          trip_state, client_modified_at, server_modified_at, revision, last_device_id)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(
        uuid, user.id, title, note, timeline, startTime, endTime, totalCost,
        tripState, clientModified, now, revision, device.id,
      ),
      await logChange(env.DB, user.id, 'trip', uuid, 'upsert', device.id, now),
    ]);
  }
  return { status: 'applied', server_revision: revision, server_modified_at: now };
}

async function applyCheckin(env, user, device, change) {
  const uuid = asText(change.uuid).trim();
  if (!uuid) throw badChange('missing_uuid');
  const existing = await env.DB.prepare(
    'SELECT * FROM checkins WHERE user_id = ? AND uuid = ?',
  ).bind(user.id, uuid).first();

  if (change.op === 'delete') {
    if (!existing) return { status: 'ignored', reason: 'not_found' };
    if (asInt(change.revision, -1) !== existing.revision) {
      return { status: 'conflict', server_revision: existing.revision, data: checkinData(existing) };
    }
    const now = Date.now();
    const revision = existing.revision + 1;
    await env.DB.batch([
      env.DB.prepare(
        `UPDATE checkins
         SET deleted_at = ?, server_modified_at = ?, revision = ?, last_device_id = ?
         WHERE id = ?`,
      ).bind(now, now, revision, device.id, existing.id),
      await logChange(env.DB, user.id, 'checkin', uuid, 'delete', device.id, now),
    ]);
    return { status: 'applied', server_revision: revision, server_modified_at: now };
  }

  if (change.op !== 'upsert') throw badChange('invalid_op');
  const payload = normalizeCheckinData(change.data || {});
  const {
    cityName, province, checkinAt, createdAt, clientModified, datePrecision,
    note, companions, rating, amountCents, stayDays, cityCode,
  } = payload;
  if (existing && asInt(change.revision, -1) !== existing.revision) {
    return { status: 'conflict', server_revision: existing.revision, data: checkinData(existing) };
  }

  const now = Date.now();
  const revision = existing ? existing.revision + 1 : 1;

  if (existing) {
    await env.DB.batch([
      env.DB.prepare(
        `UPDATE checkins
         SET city_name = ?, city_code = ?, province = ?, checkin_at = ?, created_at = ?,
             date_precision = ?, note = ?, companions = ?, rating = ?, amount_cents = ?,
             stay_days = ?, client_modified_at = ?, server_modified_at = ?,
             deleted_at = NULL, revision = ?, last_device_id = ?
         WHERE id = ?`,
      ).bind(
        cityName, cityCode, province, checkinAt, createdAt, datePrecision, note,
        companions, rating, amountCents, stayDays, clientModified, now,
        revision, device.id, existing.id,
      ),
      await logChange(env.DB, user.id, 'checkin', uuid, 'upsert', device.id, now),
    ]);
  } else {
    await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO checkins
         (uuid, user_id, city_name, city_code, province, checkin_at, created_at, date_precision,
          note, companions, rating, amount_cents, stay_days, client_modified_at,
          server_modified_at, revision, last_device_id)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(
        uuid, user.id, cityName, cityCode, province, checkinAt, createdAt, datePrecision,
        note, companions, rating, amountCents, stayDays, clientModified, now, revision, device.id,
      ),
      await logChange(env.DB, user.id, 'checkin', uuid, 'upsert', device.id, now),
    ]);
  }
  return { status: 'applied', server_revision: revision, server_modified_at: now };
}

async function currentSeq(env, userId) {
  const row = await env.DB.prepare(
    'SELECT COALESCE(MAX(seq), 0) AS seq FROM sync_changes WHERE user_id = ?',
  ).bind(userId).first();
  return row?.seq || 0;
}

export async function handleSyncRegister(request, env, user) {
  if (request.method !== 'POST') return fail(405, 'method_not_allowed', '仅支持 POST');
  const body = await request.json().catch(() => null);
  if (!body) return fail(400, 'invalid_body', '请求体不是 JSON');
  try {
    const device = await ensureDevice(env.DB, user.id, body);
    return json({ device_id: device.id, device_uuid: device.uuid, last_pull_seq: device.last_pull_seq });
  } catch {
    return fail(400, 'invalid_device', 'device_uuid 不合法');
  }
}

export async function handleSyncPush(request, env, user) {
  if (request.method !== 'POST') return fail(405, 'method_not_allowed', '仅支持 POST');
  const body = await request.json().catch(() => null);
  if (!body || !Array.isArray(body.changes)) return fail(400, 'invalid_body', '缺少 changes');
  if (body.changes.length > MAX_CHANGES) {
    return fail(413, 'too_many_changes', `单次最多 ${MAX_CHANGES} 条`);
  }

  let device;
  try {
    device = await ensureDevice(env.DB, user.id, body);
  } catch {
    return fail(400, 'invalid_device', 'device_uuid 不合法');
  }

  const results = [];
  for (const change of body.changes) {
    const type = asText(change?.entity_type);
    const uuid = asText(change?.uuid).trim();
    try {
      let r;
      if (type === 'trip') r = await applyTrip(env, user, device, change);
      else if (type === 'checkin') r = await applyCheckin(env, user, device, change);
      else return fail(400, 'invalid_entity_type', 'entity_type 只能是 trip / checkin');
      results.push({ entity_type: type, uuid, ...r });
    } catch (e) {
      if (e.code === 'invalid_change') {
        return fail(400, e.message, '变更数据不合法');
      }
      return fail(500, 'sync_failed', '同步写入失败');
    }
  }

  return json({
    results,
    last_seq: await currentSeq(env, user.id),
  });
}

export async function handleSyncPull(request, env, user) {
  if (request.method !== 'GET') return fail(405, 'method_not_allowed', '仅支持 GET');
  const url = new URL(request.url);
  const since = Math.max(0, asInt(url.searchParams.get('since'), 0));
  const limit = Math.min(MAX_PULL, Math.max(1, asInt(url.searchParams.get('limit'), 100)));
  // 新设备首次登录（cursor=0）直接下发当前快照。
  // 迁移进库的数据没有写 sync_changes，只靠增量会让新设备永远拉不到旧行程/打卡。
  if (since === 0) {
    const seq = await currentSeq(env, user.id);
    const trips = await env.DB.prepare(
      'SELECT * FROM trips WHERE user_id = ? AND deleted_at IS NULL ORDER BY start_time ASC',
    ).bind(user.id).all();
    const checkins = await env.DB.prepare(
      'SELECT * FROM checkins WHERE user_id = ? AND deleted_at IS NULL ORDER BY checkin_at ASC',
    ).bind(user.id).all();
    const snapshot = [];
    for (const row of trips.results || []) {
      snapshot.push({
        seq,
        entity_type: 'trip',
        uuid: row.uuid,
        op: 'upsert',
        changed_at: row.modified_at,
        data: tripData(row),
      });
    }
    for (const row of checkins.results || []) {
      snapshot.push({
        seq,
        entity_type: 'checkin',
        uuid: row.uuid,
        op: 'upsert',
        changed_at: row.modified_at,
        data: checkinData(row),
      });
    }
    return json({ changes: snapshot, next_since: seq, has_more: false });
  }

  const changeRows = await env.DB.prepare(
    `SELECT seq, entity_type, entity_uuid, op, changed_at
     FROM sync_changes
     WHERE user_id = ? AND seq > ?
     ORDER BY seq ASC LIMIT ?`,
  ).bind(user.id, since, limit).all();

  const changes = [];
  for (const change of changeRows.results || []) {
    let row = null;
    if (change.entity_type === 'trip') {
      row = await env.DB.prepare(
        'SELECT * FROM trips WHERE user_id = ? AND uuid = ?',
      ).bind(user.id, change.entity_uuid).first();
    } else if (change.entity_type === 'checkin') {
      row = await env.DB.prepare(
        'SELECT * FROM checkins WHERE user_id = ? AND uuid = ?',
      ).bind(user.id, change.entity_uuid).first();
    }
    changes.push({
      seq: change.seq,
      entity_type: change.entity_type,
      uuid: change.entity_uuid,
      op: change.op,
      changed_at: change.changed_at,
      data: row ? (change.entity_type === 'trip' ? tripData(row) : checkinData(row)) : null,
    });
  }

  return json({
    changes,
    next_since: changes.length ? changes[changes.length - 1].seq : since,
    has_more: changes.length === limit,
  });
}
