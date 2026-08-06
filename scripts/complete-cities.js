// 补全 public/cities.js：从 DataV 拉取缺失的地级行政区（自治州/地区/盟/直辖县级等）
// 用法：node scripts/complete-cities.js
const fs = require('fs');
const path = require('path');

const PROVINCES = [["11", "北京"], ["12", "天津"], ["13", "河北"], ["14", "山西"], ["15", "内蒙古"],
  ["21", "辽宁"], ["22", "吉林"], ["23", "黑龙江"], ["31", "上海"], ["32", "江苏"], ["33", "浙江"],
  ["34", "安徽"], ["35", "福建"], ["36", "江西"], ["37", "山东"], ["41", "河南"], ["42", "湖北"],
  ["43", "湖南"], ["44", "广东"], ["45", "广西"], ["46", "海南"], ["50", "重庆"], ["51", "四川"],
  ["52", "贵州"], ["53", "云南"], ["54", "西藏"], ["61", "陕西"], ["62", "甘肃"], ["63", "青海"],
  ["64", "宁夏"], ["65", "新疆"], ["71", "台湾"], ["81", "香港"], ["82", "澳门"]];

const ETHNIC = "(?:汉|壮|满|回|苗|维吾尔|土家|彝|蒙古|藏|布依|侗|瑶|朝鲜|白|哈尼|哈萨克|黎|傣|畲|傈僳|仡佬|东乡|高山|拉祜|水|佤|纳西|羌|土|仫佬|锡伯|柯尔克孜|达斡尔|景颇|毛南|撒拉|布朗|塔吉克|阿昌|普米|鄂温克|怒|京|基诺|德昂|保安|俄罗斯|裕固|乌兹别克|门巴|鄂伦春|独龙|塔塔尔|赫哲|珞巴)+族";

// 归一化：去"X族自治州/县"、行政后缀、残余民族词，处理歧义
function norm(name) {
  let s = String(name || '')
    .replace(new RegExp('(?:' + ETHNIC + ')+自治州|(?:' + ETHNIC + ')+自治县|自治州|自治县|地区|盟|特别行政区|市|省|县|区|林区', 'g'), '')
    .replace(new RegExp('(?:' + ETHNIC + ')+', 'g'), '')
    .trim();
  if (s === '陵') s = '陵水';          // 陵水黎族自治县
  if (s === '海南') s = '海南州';       // 青海海南藏族自治州，避免与海南省混淆
  return s;
}

async function main() {
  const citiesPath = path.join(__dirname, '..', 'public', 'cities.js');
  const citiesJs = fs.readFileSync(citiesPath, 'utf8');
  const existing = new Set([...citiesJs.matchAll(/name: .([^,]+)./g)].map(m => m[1].replace(/['"]/g, '')));

  const all = new Map();
  for (const [p, prov] of PROVINCES) {
    try {
      const d = await (await fetch(`https://geo.datav.aliyun.com/areas_v3/bound/${p}0000_full.json`)).json();
      for (const f of d.features || []) {
        const pr = f.properties || {};
        if (pr.level === 'city') {
          const name = norm(pr.name);
          if (name) all.set(name, { province: prov, lat: (pr.center || [0, 0])[1], lng: (pr.center || [0, 0])[0] });
        }
      }
    } catch (e) { /* 跳过 */ }
  }

  const missing = [...all.keys()].filter(n => !existing.has(n)).sort();
  if (!missing.length) { console.log('cities.js 已完整，无需补全'); return; }

  const lines = missing.map(n => {
    const c = all.get(n);
    return `  { name: '${n}', province: '${c.province}', lat: ${c.lat}, lng: ${c.lng} },`;
  });

  // 在 ] 前插入
  const marker = '\n];';
  const insert = '\n\n  // ── 自动补全：缺失的地级行政区 ──\n' + lines.join('\n');
  const idx = citiesJs.lastIndexOf(marker);
  if (idx === -1) throw new Error('未找到 cities.js 数组结尾');
  fs.writeFileSync(citiesPath, citiesJs.slice(0, idx) + insert + marker + citiesJs.slice(idx + marker.length));

  console.log(`已补全 ${missing.length} 个地级行政区到 cities.js：`);
  console.log(missing.join('、'));
}

main().catch(e => { console.error('失败:', e.message); process.exit(1); });
