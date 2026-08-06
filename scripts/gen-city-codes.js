// 生成 public/city-codes.js：城市名 -> 行政区划代码(adcode) 映射
// 数据源：阿里 DataV GeoAtlas（https://geo.datav.aliyun.com/areas_v3/bound/{adcode}_full.json）
// 用法：node scripts/gen-city-codes.js
const fs = require('fs');
const path = require('path');

// 34 个省级 adcode
const PROVINCES = [
  [110000, '北京'], [120000, '天津'], [130000, '河北'], [140000, '山西'], [150000, '内蒙古'],
  [210000, '辽宁'], [220000, '吉林'], [230000, '黑龙江'], [310000, '上海'], [320000, '江苏'],
  [330000, '浙江'], [340000, '安徽'], [350000, '福建'], [360000, '江西'], [370000, '山东'],
  [410000, '河南'], [420000, '湖北'], [430000, '湖南'], [440000, '广东'], [450000, '广西'],
  [460000, '海南'], [500000, '重庆'], [510000, '四川'], [520000, '贵州'], [530000, '云南'],
  [540000, '西藏'], [610000, '陕西'], [620000, '甘肃'], [630000, '青海'], [640000, '宁夏'],
  [650000, '新疆'], [710000, '台湾'], [810000, '香港'], [820000, '澳门'],
];

async function fetchJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}

// 民族词列表（含简写与常见组合），用于去掉"X族自治州"里的民族词
const ETHNIC = '(?:汉|壮|满|回|苗|维吾尔|土家|彝|蒙古|藏|布依|侗|瑶|朝鲜|白|哈尼|哈萨克|黎|傣|畲|傈僳|仡佬|东乡|高山|拉祜|水|佤|纳西|羌|土|仫佬|锡伯|柯尔克孜|达斡尔|景颇|毛南|撒拉|布朗|塔吉克|阿昌|普米|鄂温克|怒|京|基诺|德昂|保安|俄罗斯|裕固|乌兹别克|门巴|鄂伦春|独龙|塔塔尔|赫哲|珞巴)+族';

// 名称归一化：去掉行政后缀与民族词（如"大理白族自治州"→"大理"），便于匹配
function normalize(name) {
  let s = String(name || '')
    .replace(new RegExp('(?:' + ETHNIC + ')+自治州|(?:' + ETHNIC + ')+自治县|自治州|自治县|地区|盟|特别行政区|市|省|县|区|林区', 'g'), '')
    .replace(new RegExp('(?:' + ETHNIC + ')+', 'g'), '') // 去残余民族词（如"柯尔克孜"）
    .trim();
  if (s === '陵') s = '陵水';    // 陵水黎族自治县
  if (s === '海南') s = '海南州'; // 青海海南藏族自治州，避免与海南省混淆
  return s;
}

async function main() {
  const nameToAdcode = new Map(); // DataV 完整名 -> adcode

  for (const [provAdcode, provName] of PROVINCES) {
    try {
      const data = await fetchJson(`https://geo.datav.aliyun.com/areas_v3/bound/${provAdcode}_full.json`);
      for (const f of data.features || []) {
        const p = f.properties || {};
        if (p.level === 'city') nameToAdcode.set(p.name, p.adcode);
      }
    } catch (e) {
      console.error(`获取 ${provName} 失败:`, e.message);
    }
  }
  // 直辖市：省级 adcode 即市本级
  for (const [adcode, name] of [[110000, '北京市'], [120000, '天津市'], [310000, '上海市'], [500000, '重庆市']]) {
    nameToAdcode.set(name, adcode);
  }
  console.log('从 DataV 获取市级记录:', nameToAdcode.size, '条');

  // 读取 cities.js 城市名
  const citiesJs = fs.readFileSync(path.join(__dirname, '..', 'public', 'cities.js'), 'utf8');
  const names = [...citiesJs.matchAll(/name:\s*'([^']+)'/g)].map(m => m[1]);
  // 过滤注释里的示例名（非真实城市）
  const uniqueNames = [...new Set(names)].filter(n => n && n !== '市名');
  console.log('cities.js 城市数:', uniqueNames.length);

  // 匹配（先精确、再归一化）
  const codes = {};
  const missing = [];
  for (const name of uniqueNames) {
    let adcode = nameToAdcode.get(name);
    if (!adcode) {
      const norm = normalize(name);
      for (const [k, v] of nameToAdcode) {
        if (normalize(k) === norm) { adcode = v; break; }
      }
    }
    if (adcode) codes[name] = adcode;
    else missing.push(name);
  }

  // 港澳台补充映射（DataV 不提供台湾省市县，但可用省级/整体边界）
  const EXTRA_CODES = { '台湾': 710000, '香港': 810000, '澳门': 820000, '大兴安岭': 232700, '克孜勒苏': 653000, '文昌': 469005, '那曲': 540600, '伊犁': 654000, '博尔塔拉': 652700, '巴音郭楞': 652800 };
  Object.assign(codes, EXTRA_CODES);

  // 输出
  const out = '// 城市名 -> 行政区划代码(adcode) 映射\n// 由 scripts/gen-city-codes.js 从阿里 DataV GeoAtlas 生成，可重复运行更新\nconst CITY_CODES = ' + JSON.stringify(codes, null, 2) + ';\n';
  fs.writeFileSync(path.join(__dirname, '..', 'public', 'city-codes.js'), out);
  console.log('已生成 public/city-codes.js，映射', Object.keys(codes).length, '个城市');
  console.log('未匹配（将回退为圆点）:', missing.length ? missing.join(', ') : '无');
}

main().catch(e => { console.error('脚本失败:', e); process.exit(1); });
