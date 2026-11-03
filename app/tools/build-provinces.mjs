#!/usr/bin/env node
/**
 * 省级边界生成脚本（DataV `100000_full` → GCJ-02 转 WGS-84 → 简化 20% → 单一 GeoJSON）。
 *
 * 地图上省界只是**背景线**：点亮的城市面盖在上面，省界负责让人看出「这是哪一片」。
 * 所以它比城市边界更可以激进简化，20% 档的体积只有一百来 KB。
 *
 * 用法：node app/tools/build-provinces.mjs
 * 产物（**入库**）：app/src/main/assets/data/provinces.geojson
 * 中间文件：app/tools/.cache/（已 gitignore）
 *
 * 坐标系与转换算法同 build-city-geo.mjs —— 两份必须是同一套，否则省界与城市面会错位。
 */

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const HERE = path.dirname(fileURLToPath(import.meta.url));       // app/tools
const APP = path.resolve(HERE, '..');
const REPO = path.resolve(APP, '..');
const CACHE = path.join(HERE, '.cache');
const RAW = path.join(CACHE, 'provinces.raw.json');
const TMP = path.join(CACHE, 'provinces.wgs.geojson');
const ASSETS = path.join(APP, 'src', 'main', 'assets', 'data');

const PI = Math.PI, A = 6378245.0, EE = 0.00669342162296594323;
const outOfChina = (lat, lng) => !(lng > 73.66 && lng < 135.05 && lat > 3.86 && lat < 53.55);
function tLat(x, y) {
  let r = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  r += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3;
  r += (20 * Math.sin(y * PI) + 40 * Math.sin(y / 3 * PI)) * 2 / 3;
  r += (160 * Math.sin(y / 12 * PI) + 320 * Math.sin(y * PI / 30)) * 2 / 3;
  return r;
}
function tLng(x, y) {
  let r = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  r += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3;
  r += (20 * Math.sin(x * PI) + 40 * Math.sin(x / 3 * PI)) * 2 / 3;
  r += (150 * Math.sin(x / 12 * PI) + 300 * Math.sin(x / 30 * PI)) * 2 / 3;
  return r;
}
function wgs84ToGcj02(lat, lng) {
  if (outOfChina(lat, lng)) return [lat, lng];
  let dLat = tLat(lng - 105, lat - 35), dLng = tLng(lng - 105, lat - 35);
  const radLat = lat / 180 * PI;
  let magic = Math.sin(radLat); magic = 1 - EE * magic * magic;
  const sm = Math.sqrt(magic);
  dLat = (dLat * 180) / ((A * (1 - EE)) / (magic * sm) * PI);
  dLng = (dLng * 180) / (A / sm * Math.cos(radLat) * PI);
  return [lat + dLat, lng + dLng];
}
function gcj02ToWgs84(lat, lng) {
  let rLat = lat, rLng = lng;
  for (let i = 0; i < 2; i++) {
    const [gLat, gLng] = wgs84ToGcj02(rLat, rLng);
    rLat -= gLat - lat; rLng -= gLng - lng;
  }
  return [rLat, rLng];
}

const convRing = ring => ring.map(([lng, lat]) => {
  const [la, ln] = gcj02ToWgs84(lat, lng);
  return [Number(ln.toFixed(6)), Number(la.toFixed(6))];
});
function convGeom(g) {
  if (g.type === 'Polygon') return { type: 'Polygon', coordinates: g.coordinates.map(convRing) };
  return { type: 'MultiPolygon', coordinates: g.coordinates.map(p => p.map(convRing)) };
}

// 原始数据没缓存就现拉（DataV 的 100000_full 是省级边界全集）
if (!fs.existsSync(RAW)) {
  fs.mkdirSync(path.dirname(RAW), { recursive: true });
  const url = 'https://geo.datav.aliyun.com/areas_v3/bound/100000_full.json';
  console.log(`拉取 ${url}`);
  const res = await fetch(url, { headers: { 'User-Agent': 'CityFootprint-tools/1.0' } });
  if (!res.ok) throw new Error(`拉取失败 HTTP ${res.status}`);
  fs.writeFileSync(RAW, await res.text());
}

const raw = JSON.parse(fs.readFileSync(RAW, 'utf8'));
const features = raw.features
  .filter(f => f.geometry && (f.geometry.type === 'Polygon' || f.geometry.type === 'MultiPolygon'))
  .map(f => ({
    type: 'Feature',
    // 只留地图要用的：adcode（过滤/点亮）+ name（将来做标注）
    properties: { adcode: Number(f.properties.adcode), name: f.properties.name },
    geometry: convGeom(f.geometry),
  }));

fs.writeFileSync(TMP, JSON.stringify({ type: 'FeatureCollection', features }));
console.log(`省级要素 ${features.length}，raw ${(fs.statSync(TMP).size / 1048576).toFixed(2)} MB`);

fs.mkdirSync(ASSETS, { recursive: true });
const outPath = path.join(ASSETS, 'provinces.geojson');
execFileSync('cmd.exe', ['/c', 'npx.cmd', '-y', 'mapshaper', TMP,
  '-simplify', 'weighted', '20%', 'keep-shapes',
  '-o', outPath, 'precision=0.0001', 'format=geojson',
], { stdio: 'inherit', cwd: REPO });

const size = fs.statSync(outPath).size;
const gz = zlib.gzipSync(fs.readFileSync(outPath), { level: 9 }).length;
console.log(`简化后 ${(size / 1024).toFixed(0)} KB（gzip ${(gz / 1024).toFixed(0)} KB）→ ${path.relative(REPO, outPath)}`);
