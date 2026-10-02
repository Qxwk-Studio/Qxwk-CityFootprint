// CityFootprint 成就定义与判定 —— **全站唯一一份**。
// 网页（docs/）与 app 都不再各存一份判定表，改为直接消费接口返回的结果：
//   GET /api/my-visits → achievements[].items[].done   （我的成就）
//   GET /api/stats     → achievements[].items[].count  （达成人数）
// 定义只留在这里，改阈值/加成就只改这一个文件，两端不会算出不同结果。
//
// code 是**稳定标识**：早期版本拿中文 name 当键（achCount[a.name]），
// 改一次展示文案，历史计数就静默错位。所以每个成就一个英文 code，展示文案可以随便改。

const METRO = ['北京', '上海', '广州', '深圳'];
const MUNICIPAL = ['北京', '天津', '上海', '重庆'];
const PROV_CAPITALS = ['石家庄', '太原', '呼和浩特', '沈阳', '长春', '哈尔滨', '南京', '杭州', '合肥', '福州', '南昌', '济南', '郑州', '武汉', '长沙', '广州', '南宁', '海口', '成都', '贵阳', '昆明', '拉萨', '西安', '兰州', '西宁', '银川', '乌鲁木齐'];
const PLATEAU = ['拉萨', '西宁', '格尔木', '日喀则', '甘南'];
const COASTAL = ['大连', '青岛', '宁波', '厦门', '深圳', '珠海', '汕头', '湛江', '秦皇岛', '烟台', '威海', '连云港', '南通', '温州', '台州', '福州', '泉州', '漳州', '北海', '防城港', '海口', '三亚', '唐山', '天津', '上海', '广州', '惠州', '江门', '阳江', '茂名', '盐城', '嘉兴', '舟山', '莆田', '宁德'];
const ANCIENT = ['西安', '洛阳', '北京', '南京', '开封', '杭州', '安阳', '郑州'];
const FIVE_MOUNTAINS = ['泰安', '渭南', '衡阳', '大同', '郑州'];
const GROTTOES = ['酒泉', '大同', '洛阳', '天水'];
const SPECIAL_ZONE = ['深圳', '珠海', '汕头', '厦门'];
const LAUNCH = ['酒泉', '太原', '西昌', '文昌'];
const HAINAN = ['海口', '三亚', '三沙', '儋州', '文昌', '琼海', '万宁', '东方', '五指山', '澄迈', '定安', '屯昌', '陵水', '昌江', '乐东', '保亭', '琼中', '白沙', '临高'];
const TAIWAN = ['台湾', '台北', '新北', '桃园', '台中', '台南', '高雄', '基隆', '新竹', '嘉义'];
const HK_MACAU = ['香港', '澳门'];
const GREAT_WALL = ['北京', '秦皇岛', '酒泉', '张家口', '忻州', '榆林', '承德', '嘉峪关', '天津', '丹东', '阳泉'];
// 热河省（1928–1955，省会承德）撤销后辖区并入了河北、内蒙古、辽宁 —— 这里按今天的地级市取三家：
// 承德、赤峰、朝阳（另有阜新等只占一部分，不计）。是「历史地理」类成就，不是现行行政区。
const REHE = ['承德', '赤峰', '朝阳'];

/**
 * 按「去过的城市名」判定全部成就。
 * @param {string[]} cityNames 城市名（可重复，内部会去重）
 * @returns {{title: string, items: {code, icon, name, desc, done}[]}[]}
 */
export function getAchievements(cityNames) {
  const citySet = new Set(cityNames);
  const cityCount = citySet.size;
  const hasAll = arr => arr.every(c => citySet.has(c));
  const hasAny = arr => arr.some(c => citySet.has(c));

  return [
    {
      title: '🌟 足迹丰碑',
      items: [
        { code: 'first_trip', icon: '🚀', name: '初次启程', desc: '到访过 2 座及以上城市', done: cityCount >= 2 },
        { code: 'city_roamer', icon: '🏙️', name: '城市漫游', desc: '到访过 5 座及以上城市', done: cityCount >= 5 },
        { code: 'footprint_hunter', icon: '🗺️', name: '足迹猎手', desc: '到访过 10 座及以上城市', done: cityCount >= 10 },
        { code: 'travel_regular', icon: '✈️', name: '旅行常客', desc: '到访过 25 座及以上城市', done: cityCount >= 25 },
        { code: 'globe_trotter', icon: '🌍', name: '环游达人', desc: '到访过 50 座及以上城市', done: cityCount >= 50 },
        { code: 'city_collector', icon: '🏆', name: '城市收藏家', desc: '到访过 100 座及以上城市', done: cityCount >= 100 },
        { code: 'city_king', icon: '👑', name: '城市之王', desc: '到访过 200 座及以上城市', done: cityCount >= 200 },
        { code: 'grand_tour', icon: '🌟', name: '全境巡礼', desc: '到访过 293 座及以上城市', done: cityCount >= 293 },
        { code: 'all_regions', icon: '🏁', name: '走遍全国', desc: '到访过全部 384 座城市', done: cityCount >= 384 },
      ],
    },
    {
      title: '🚩 巡游四方',
      items: [
        { code: 'metro_all', icon: '🏙️', name: '都市集章者', desc: '到访过北京、上海、广州、深圳全部四座城市', done: hasAll(METRO) },
        { code: 'municipality_all', icon: '🏛️', name: '直辖市览胜', desc: '到访过北京、天津、上海、重庆全部四座直辖市', done: hasAll(MUNICIPAL) },

        { code: 'prov_capitals_all', icon: '🏯', name: '省会巡礼', desc: '到访过全部 27 个省会/首府城市', done: hasAll(PROV_CAPITALS) },
        { code: 'hk_macau', icon: '🌉', name: '港澳穿梭', desc: '到访过香港和澳门全部两地', done: hasAll(HK_MACAU) },

        { code: 'ancient_capitals', icon: '🏯', name: '八大古都', desc: '到访过八大古都全部（西安、洛阳、北京、南京、开封、杭州、安阳、郑州）', done: hasAll(ANCIENT) },
        { code: 'five_mountains', icon: '⛰️', name: '五岳之巅', desc: '到访过五岳所在城市全部（泰山·泰安、华山·渭南、衡山·衡阳、恒山·大同、嵩山·郑州）', done: hasAll(FIVE_MOUNTAINS) },
        { code: 'four_grottoes', icon: '🗿', name: '四大石窟', desc: '到访过四大石窟所在城市全部（莫高窟·酒泉、云冈·大同、龙门·洛阳、麦积山·天水）', done: hasAll(GROTTOES) },
        { code: 'rehe_province', icon: '🗺️', name: '热河寻踪', desc: '到访过热河省故地全部（承德、赤峰、朝阳）', done: hasAll(REHE) },

        { code: 'plateau_city', icon: '🏔️', name: '高原之城', desc: '到访过任意一座青藏高原城市（拉萨、西宁、格尔木等）', done: hasAny(PLATEAU) },
        { code: 'coastal_city', icon: '🌊', name: '沿海之城', desc: '到访过任意一座沿海地级市（大连、青岛、厦门等）', done: hasAny(COASTAL) },
        { code: 'island_city', icon: '🏖️', name: '海岛风光', desc: '到访过海南或台湾任意一座城市（海口、三亚、台北等）', done: hasAny([...HAINAN, ...TAIWAN]) },
        { code: 'great_wall', icon: '🧱', name: '不到长城非好汉', desc: '到访过任意一座长城名城（北京八达岭、承德金山岭、嘉峪关关城、秦皇岛山海关等）', done: hasAny(GREAT_WALL) },
        { code: 'special_zone', icon: '🌴', name: '特区足迹', desc: '到访过任意一座经济特区城市（深圳、珠海、汕头、厦门）', done: hasAny(SPECIAL_ZONE) },
        { code: 'space_launch', icon: '🚀', name: '飞天梦', desc: '到访过任一卫星发射中心城市（酒泉、太原、西昌、文昌）', done: hasAny(LAUNCH) },
      ],
    },
    {
      title: '📍 城市打卡',
      items: [
        // 按城市所属省份聚类，省份顺序照数据集（docs/cities.js）的行政区划顺序；同省内保持原有先后。
        // 河北
        { code: 'city_shijiazhuang', icon: '🏘️', name: '国际庄', desc: '到访过 石家庄，食用河北正宗安徽牛肉板面', done: citySet.has('石家庄') },
        { code: 'city_handan', icon: '🚶', name: '邯郸学步', desc: '到访过 邯郸，尝试学当地人走路？', done: citySet.has('邯郸') },

        // 辽宁
        { code: 'city_dandong', icon: '☀️', name: '\\o/\\o/', desc: '到访过 丹东，打卡鸭绿江然后在河边蹦蹦跳跳', done: citySet.has('丹东') },

        // 黑龙江
        { code: 'city_harbin', icon: '⛄', name: '冰雪大世界', desc: '到访过 哈尔滨，冬天去冰雪大世界', done: citySet.has('哈尔滨') },

        // 江苏
        { code: 'city_xuzhou', icon: '🎯', name: '优势在我', desc: '到访过 徐州，并说出那句著名的话', done: citySet.has('徐州') },

        // 浙江
        { code: 'city_hangzhou', icon: '🛶', name: '人间天堂', desc: '到访过 杭州，上有天堂，下……', done: citySet.has('杭州') },

        // 福建
        { code: 'city_sanming', icon: '🍜', name: '沙县小吃', desc: '到访过 三明', done: citySet.has('三明') },

        // 江西
        { code: 'city_nanchang', icon: '🏯', name: '滕王高阁', desc: '到访过 南昌，（完整）背诵一遍滕王阁序（', done: citySet.has('南昌') },
        { code: 'city_jingdezhen', icon: '🏺', name: '瓷都', desc: '到访过 景德镇，制作自己的瓷器', done: citySet.has('景德镇') },

        // 山东
        { code: 'city_heze', icon: '🪐', name: '宇宙中心', desc: '到访过 菏泽', done: citySet.has('菏泽') },
        { code: 'city_weifang', icon: '🪁', name: '风筝之都', desc: '到访过 潍坊，参加风筝节', done: citySet.has('潍坊') },

        // 河南
        { code: 'city_xinxiang', icon: '🗽', name: 'New York', desc: '到访过 新乡，欢迎来到纽约！', done: citySet.has('新乡') },

        // 湖北
        { code: 'city_wuhan', icon: '🌙', name: '黄鹤楼下', desc: '到访过 武汉，崔颢的《黄鹤楼》总会背吧？', done: citySet.has('武汉') },
        { code: 'city_yichang', icon: '🚢', name: '三峡门户', desc: '到访过 宜昌', done: citySet.has('宜昌') },

        // 湖南
        { code: 'city_yueyang', icon: '🌅', name: '岳阳楼记', desc: '到访过 岳阳，完整背诵《岳阳楼记》', done: citySet.has('岳阳') },
        { code: 'city_changsha', icon: '🏝️', name: '橘子洲头', desc: '到访过 长沙，不去橘子洲头那不白去了吗？', done: citySet.has('长沙') },

        // 广东
        { code: 'city_foshan', icon: '🛷', name: '家具中心', desc: '到访过 佛山，佛山工厂造出来了！', done: citySet.has('佛山') },

        // 广西
        { code: 'city_guilin', icon: '🏞️', name: '甲天下', desc: '到访过 桂林，桂林山水甲天下！', done: citySet.has('桂林') },
        { code: 'city_liuzhou', icon: '🍜', name: '螺蛳粉', desc: '到访过 柳州，螺蛳粉真的好吃吗……？', done: citySet.has('柳州') },

        // 四川
        { code: 'city_chengdu', icon: '🐼', name: '天府之国', desc: '到访过 成都，也是刻板印象了（', done: citySet.has('成都') },

        // 云南
        { code: 'city_dali', icon: '🌸', name: '风花雪月', desc: '到访过 大理', done: citySet.has('大理') },

        // 陕西
        { code: 'city_xian', icon: '🎤', name: '西安人的歌', desc: '到访过 西安，并听一曲西安人的歌', done: citySet.has('西安') },
        { code: 'city_yanan', icon: '⭐', name: '革命圣地', desc: '到访过 延安', done: citySet.has('延安') },
      ],
    },
    {
      title: '🧭 极限挑战',
      items: [
        { code: 'east_end_jiamusi', icon: '🌅', name: '极东破晓', desc: '到访过中国最东端——佳木斯', done: citySet.has('佳木斯') },
        { code: 'west_end_kezilesu', icon: '🌄', name: '极西暮歌', desc: '到访过中国最西端——克孜勒苏', done: citySet.has('克孜勒苏') },
        { code: 'south_end_sansha', icon: '🌊', name: '极南听涛', desc: '到访过中国最南端——三沙', done: citySet.has('三沙') },
        { code: 'north_end_daxinganling', icon: '❄️', name: '极北寻光', desc: '到访过中国最北端——大兴安岭', done: citySet.has('大兴安岭') },
        
        { code: 'highest_naqu', icon: '🏔️', name: '云端之巅', desc: '到访过海拔最高的地级行政区——那曲', done: citySet.has('那曲') },
        { code: 'lowest_turpan', icon: '🏜️', name: '盆地之渊', desc: '到访过中国海拔最低点（艾丁湖）所在地——吐鲁番', done: citySet.has('吐鲁番') },
        { code: 'hottest_turpan', icon: '🔥', name: '火洲炼狱', desc: '到访过中国最热的地方——吐鲁番', done: citySet.has('吐鲁番') },
        { code: 'coldest_hulunbuir', icon: '❄️', name: '北境寒极', desc: '到访过中国最冷的地方——呼伦贝尔', done: citySet.has('呼伦贝尔') },
      ],
    },
  ];
}

/**
 * 全站「成就达成人数」：骨架仍走 getAchievements([])（拿到全部 55 条的 code/图标/文案），
 * 再叠上每个成就被多少人达成。网页与 app 的统计页都不再需要本地定义。
 * @param {string[][]} perUserCities 每个用户的「去过的城市名」数组
 * @returns {{title: string, items: {code, icon, name, desc, count}[]}[]}
 */
export function achievementCounts(perUserCities) {
  const counts = {};
  for (const cities of perUserCities) {
    for (const cat of getAchievements(cities)) {
      for (const a of cat.items) {
        if (a.done) counts[a.code] = (counts[a.code] || 0) + 1;
      }
    }
  }
  return getAchievements([]).map(cat => ({
    title: cat.title,
    items: cat.items.map(a => ({ code: a.code, icon: a.icon, name: a.name, desc: a.desc, count: counts[a.code] || 0 })),
  }));
}