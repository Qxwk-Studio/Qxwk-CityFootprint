import { json } from '../_lib/utils.js';

// 公开接口：所有足迹，前端地图直接用
export async function onRequestGet({ env }) {
  const rows = await env.DB.prepare(
    `SELECT v.id, v.city, v.lat, v.lng, v.visit_date, v.note,
            u.id AS user_id, u.nickname, u.color
     FROM visits v JOIN users u ON v.user_id = u.id
     ORDER BY v.created_at ASC`
  ).all();

  // 按城市聚合
  const cityMap = new Map();
  for (const r of rows.results) {
    if (!cityMap.has(r.city)) {
      cityMap.set(r.city, {
        city: r.city,
        lat: r.lat,
        lng: r.lng,
        people: [],
      });
    }
    cityMap.get(r.city).people.push({
      nickname: r.nickname,
      color: r.color,
      visit_date: r.visit_date,
      note: r.note,
    });
  }

  return json({ cities: [...cityMap.values()] });
}
