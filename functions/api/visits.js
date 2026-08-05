import { json, error } from '../_lib/utils.js';
import { getUserId } from '../_lib/auth.js';

// 需要登录：添加一条足迹
export async function onRequestPost(context) {
  const { env, request } = context;
  const userId = await getUserId(env, request);
  if (!userId) return error('未登录', 401);

  const body = await request.json().catch(() => ({}));
  const city = String(body.city || '').trim();
  const lat = Number(body.lat);
  const lng = Number(body.lng);
  const visitDate = body.visit_date || null;   // null = 记不清时间
  const note = String(body.note || '').trim().slice(0, 200);

  if (!city || city.length > 30) return error('请选择城市');
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return error('城市坐标无效');
  if (visitDate !== null && !/^\d{4}(-\d{2})?$/.test(visitDate)) return error('日期格式应为 2024 或 2024-08');

  const res = await env.DB.prepare(
    'INSERT INTO visits (user_id, city, lat, lng, visit_date, note) VALUES (?, ?, ?, ?, ?, ?)'
  ).bind(userId, city, lat, lng, visitDate, note).run();

  return json({ id: res.meta.last_row_id, city, lat, lng, visit_date: visitDate, note }, 201);
}
