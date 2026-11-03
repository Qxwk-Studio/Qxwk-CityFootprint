#!/usr/bin/env node
/**
 * 城市边界生成脚本：把 DataV 的逐市边界预处理成 App 直接打包的单一 GeoJSON。
 *
 * 处理链路：
 *   DataV 原始（tools/.cache/geo/{adcode}.json，**GCJ-02**）→ 转 WGS-84 → 合并 → mapshaper 简化 → 单一文件
 *
 * 为什么必须转 WGS-84：DataV 的边界是 GCJ-02（火星坐标），而定位拿到的是 WGS-84。
 * 不转的话城市判定整体偏 500–640 m（P0 实测：境内 27,799 个采样点里 6.5% 会判错，
 * 全在边界与海岸线一带）。反向变换迭代两次，误差 < 1 m。
 *
 * 为什么简化到 20%：逐市简化会出现几十到几百米的缝隙（相邻边界不是同一条弧段），
 * 20% 档实测判不到 0.12–0.39%、判错 0.22–0.26%，再往下体积省不了多少、误差却开始涨。
 *
 * 用法：
 *   node app/tools/build-city-geo.mjs [--simplify 20%]
 * 产物（**入库**）：app/src/main/assets/data/city_boundaries.geojson
 * 中间文件：app/tools/.out/（已 gitignore）
 *
 * 前置：原始边界要先缓存到 tools/.cache/geo/。没缓存时用下面的地址逐市拉：
 *   https://geo.datav.aliyun.com/areas_v3/bound/{adcode}_full.json
 * 依赖：npx mapshaper（零全局安装，第一次会临时下载）。
 */

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const HERE = path.dirname(fileURLToPath(import.meta.url));       // app/tools
const APP = path.resolve(HERE, '..');                             // app
const REPO = path.resolve(APP, '..');                             // 仓库根
const CACHE = path.join(HERE, '.cache', 'geo');
const OUT = path.join(HERE, '.out');
const ASSETS = path.join(APP, 'src', 'main', 'assets', 'data');

// ── GCJ-02 → WGS-84（迭代两次，误差 < 1 m）──
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

function loadCityCodes() {
  const src = fs.readFileSync(path.join(REPO, 'docs', 'city-codes.js'), 'utf8');
  const map = {};
  for (const m of src.matchAll(/['"]([^'"]+)['"]\s*:\s*(\d+)/g)) map[m[1]] = Number(m[2]);
  return map;
}

const provinceOf = adcode => Number(String(adcode).slice(0, 2) + '0000');

const convertRing = ring => ring.map(([lng, lat]) => {
  const [la, ln] = gcj02ToWgs84(lat, lng);
  return [Number(ln.toFixed(6)), Number(la.toFixed(6))];
});
function convertGeometry(g) {
  if (g.type === 'Polygon') return { type: 'Polygon', coordinates: g.coordinates.map(convertRing) };
  if (g.type === 'MultiPolygon') return { type: 'MultiPolygon', coordinates: g.coordinates.map(p => p.map(convertRing)) };
  return null;
}

const countVerts = g => {
  const pts = [];
  (function walk(c) { if (typeof c[0] === 'number') { pts.push(c); return; } c.forEach(walk); })(g.coordinates);
  return pts.length;
};

function main() {
  const simplifyArg = process.argv.indexOf('--simplify');
  const simplify = simplifyArg > 0 ? process.argv[simplifyArg + 1] : '20%';
  const codes = loadCityCodes();
  const features = [];
  const missing = [];

  for (const [name, adcode] of Object.entries(codes)) {
    const file = path.join(CACHE, `${adcode}.json`);
    if (!fs.existsSync(file)) { missing.push(`${name}(${adcode})`); continue; }
    const geo = JSON.parse(fs.readFileSync(file, 'utf8'));
    for (const f of geo.features || []) {
      const props = f.properties || {};
      if (props.adcode && Number(props.adcode) !== adcode) continue;
      const geometry = convertGeometry(f.geometry);
      if (!geometry) continue;
      features.push({
        type: 'Feature',
        // name 是判定结果与城市表的唯一连接字段（app 侧 logic/CityShapes.kt 按它查城市）
        properties: { adcode, name, provinceAdcode: provinceOf(adcode) },
        geometry,
      });
    }
  }

  fs.mkdirSync(OUT, { recursive: true });
  const rawPath = path.join(OUT, 'cities.raw.geojson');
  fs.writeFileSync(rawPath, JSON.stringify({ type: 'FeatureCollection', features }));
  const totalVerts = features.reduce((n, f) => n + countVerts(f.geometry), 0);
  console.log(`合并：${features.length} 个要素，顶点 ${totalVerts.toLocaleString()}，`
    + `raw ${(fs.statSync(rawPath).size / 1048576).toFixed(2)} MB`);
  if (missing.length) {
    console.log(`缓存缺失 ${missing.length} 个：${missing.slice(0, 5).join(' ')}…`);
    console.log('  这些城市不会出现在边界文件里（App 里会退化成只画圆点）');
  }

  fs.mkdirSync(ASSETS, { recursive: true });
  const outPath = path.join(ASSETS, 'city_boundaries.geojson');
  console.log(`\n简化：-simplify weighted ${simplify} keep-shapes precision=0.0001`);
  try {
    // Windows 下 spawn 一个 .cmd 需要 shell；用 cmd /c 包一层，避免 EINVAL
    execFileSync('cmd.exe', ['/c', 'npx.cmd', '-y', 'mapshaper', rawPath,
      '-simplify', 'weighted', simplify, 'keep-shapes',
      '-o', outPath, 'precision=0.0001', 'format=geojson',
    ], { stdio: 'inherit', cwd: REPO });
  } catch (e) {
    console.error('mapshaper 失败：' + e.message);
    process.exit(1);
  }

  const simplified = JSON.parse(fs.readFileSync(outPath, 'utf8'));
  const simpVerts = simplified.features.reduce((n, f) => n + countVerts(f.geometry), 0);
  const size = fs.statSync(outPath).size;
  console.log(`简化后：顶点 ${simpVerts.toLocaleString()}（${(simpVerts / totalVerts * 100).toFixed(1)}%），`
    + `${(size / 1048576).toFixed(2)} MB，gzip ≈ ${(zlib.gzipSync(fs.readFileSync(outPath), { level: 9 }).length / 1024).toFixed(0)} KB`);
  console.log(`→ ${path.relative(REPO, outPath)}`);
}

main();
