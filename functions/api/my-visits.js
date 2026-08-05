import { json } from '../../_lib/utils.js';
import { getUserId } from '../../_lib/auth.js';

// 需要登录：自己的足迹记录
export async function onRequestGet(context) {
  const { env, request } = context;
  const userId = await getUserId(env, request);
  if (!userId) return json({ error: '未登录' }, 401);

  const visits = await env.DB.prepare(
    'SELECT id, city, lat, lng, visit_date, note FROM visits WHERE user_id = ? ORDER BY created_at ASC'
  ).bind(userId).all();

  return json({ visits: visits.results });
}
