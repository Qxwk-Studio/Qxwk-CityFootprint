import { json, error } from '../../_lib/utils.js';
import { getUserId } from '../../_lib/auth.js';

// 需要登录：只允许操作自己的记录
export async function onRequestPut(context) {
  const { env, request, params } = context;
  const userId = await getUserId(env, request);
  if (!userId) return error('未登录', 401);

  const id = Number(params.id);
  if (!Number.isInteger(id)) return error('无效的记录 id', 400);

  const visit = await env.DB.prepare('SELECT * FROM visits WHERE id = ?').bind(id).first();
  if (!visit) return error('记录不存在', 404);
  if (visit.user_id !== userId) return error('无权修改他人的记录', 403);

  const body = await request.json().catch(() => ({}));
  const city = String(body.city || '').trim();
  const lat = Number(body.lat);
  const lng = Number(body.lng);
  const visitDate = body.visit_date || null;
  const note = String(body.note || '').trim().slice(0, 200);

  if (!city || city.length > 30) return error('请选择城市');
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return error('城市坐标无效');
  if (visitDate !== null && !/^\d{4}(-\d{2})?$/.test(visitDate)) return error('日期格式应为 2024 或 2024-08');

  await env.DB.prepare(
    'UPDATE visits SET city = ?, lat = ?, lng = ?, visit_date = ?, note = ? WHERE id = ?'
  ).bind(city, lat, lng, visitDate, note, id).run();

  return json({ id, city, lat, lng, visit_date: visitDate, note });
}

export async function onRequestDelete(context) {
  const { env, request, params } = context;
  const userId = await getUserId(env, request);
  if (!userId) return error('未登录', 401);

  const id = Number(params.id);
  if (!Number.isInteger(id)) return error('无效的记录 id', 400);

  const visit = await env.DB.prepare('SELECT * FROM visits WHERE id = ?').bind(id).first();
  if (!visit) return error('记录不存在', 404);
  if (visit.user_id !== userId) return error('无权删除他人的记录', 403);

  await env.DB.prepare('DELETE FROM visits WHERE id = ?').bind(id).run();
  return json({ ok: true });
}
