// CityFootprint 成就定义与判定（个人中心、统计页共用）
// 用法：getAchievements(cityNames) → [{ title, items: [{ icon, name, desc, done }] }]

function getAchievements(cityNames) {
  const citySet = new Set(cityNames);
  const cityCount = citySet.size;
  const hasAll = arr => arr.every(c => citySet.has(c));
  const hasAny = arr => arr.some(c => citySet.has(c));

  // 城市集合常量
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

  return [
    {
      title: '🌟 足迹丰碑',
      items: [
        { icon: '🚀', name: '初次启程', desc: '到访城市 ≥ 2', done: cityCount >= 2 },
        { icon: '🏙️', name: '城市漫游', desc: '到访城市 ≥ 5', done: cityCount >= 5 },
        { icon: '🗺️', name: '足迹猎手', desc: '到访城市 ≥ 10', done: cityCount >= 10 },
        { icon: '✈️', name: '旅行常客', desc: '到访城市 ≥ 25', done: cityCount >= 25 },
        { icon: '🌍', name: '环游达人', desc: '到访城市 ≥ 50', done: cityCount >= 50 },
        { icon: '🏆', name: '城市收藏家', desc: '到访城市 ≥ 100', done: cityCount >= 100 },
        { icon: '👑', name: '城市之王', desc: '到访城市 ≥ 200', done: cityCount >= 200 },
        { icon: '🌟', name: '全境巡礼', desc: '到访全部 293 个地级行政区', done: cityCount >= 293 },
      ],
    },
    {
      title: '🚩 巡游四方',
      items: [
        { icon: '🏙️', name: '都市集章者', desc: '到访 北京、上海、广州、深圳 全部', done: hasAll(METRO) },
        { icon: '🏛️', name: '直辖市览胜', desc: '到访 北京、天津、上海、重庆 全部', done: hasAll(MUNICIPAL) },
        { icon: '🏯', name: '省会巡礼', desc: '到访所有省会/首府（27 个）', done: hasAll(PROV_CAPITALS) },
        { icon: '🏔️', name: '高原之城', desc: '到访任意青藏高原城市', done: hasAny(PLATEAU) },
        { icon: '🌊', name: '沿海之城', desc: '到访任意沿海地级市', done: hasAny(COASTAL) },
        { icon: '🏯', name: '八大古都', desc: '到访 西安、洛阳、北京、南京、开封、杭州、安阳、郑州', done: hasAll(ANCIENT) },
        { icon: '⛰️', name: '五岳之巅', desc: '到访五岳所在城市（泰安/渭南/衡阳/大同/郑州）', done: hasAll(FIVE_MOUNTAINS) },
        { icon: '🗿', name: '四大石窟', desc: '到访 酒泉、大同、洛阳、天水', done: hasAll(GROTTOES) },
        { icon: '🌴', name: '特区足迹', desc: '到访 深圳、珠海、汕头、厦门 之一', done: hasAny(SPECIAL_ZONE) },
        { icon: '🚀', name: '飞天梦', desc: '到访四个卫星发射中心之一', done: hasAny(LAUNCH) },
      ],
    },
    {
      title: '📍 城市打卡',
      items: [
        { icon: '🎯', name: '优势在我', desc: '到访 徐州', done: citySet.has('徐州') },
        { icon: '🏘️', name: '国际庄', desc: '到访 石家庄', done: citySet.has('石家庄') },
        { icon: '🗽', name: 'New York', desc: '到访 新乡', done: citySet.has('新乡') },
        { icon: '🪐', name: '宇宙中心', desc: '到访 菏泽', done: citySet.has('菏泽') },
        { icon: '🌙', name: '黄鹤楼下', desc: '到访 武汉', done: citySet.has('武汉') },
        { icon: '🏯', name: '滕王高阁', desc: '到访 南昌', done: citySet.has('南昌') },
        { icon: '🌅', name: '岳阳楼记', desc: '到访 岳阳', done: citySet.has('岳阳') },
        { icon: '🌉', name: '长江第一桥', desc: '到访 武汉', done: citySet.has('武汉') },
        { icon: '🎤', name: '西安人的歌', desc: '到访 西安', done: citySet.has('西安') },
      ],
    },
    {
      title: '🧭 极限挑战',
      items: [
        { icon: '🌅', name: '极东破晓', desc: '到访 佳木斯（最东端）', done: citySet.has('佳木斯') },
        { icon: '🌄', name: '极西暮歌', desc: '到访 克孜勒苏（最西端）', done: citySet.has('克孜勒苏') },
        { icon: '🌊', name: '极南听涛', desc: '到访 三沙（最南端）', done: citySet.has('三沙') },
        { icon: '❄️', name: '极北寻光', desc: '到访 大兴安岭（最北端）', done: citySet.has('大兴安岭') },
        { icon: '🏔️', name: '云端之巅', desc: '到访 那曲（海拔最高的地级行政区）', done: citySet.has('那曲') },
        { icon: '🏜️', name: '盆地之渊', desc: '到访 吐鲁番（海拔最低，艾丁湖 -154 米）', done: citySet.has('吐鲁番') },
        { icon: '🔥', name: '火洲炼狱', desc: '到访 吐鲁番（历史最高温 50℃+）', done: citySet.has('吐鲁番') },
        { icon: '❄️', name: '北境寒极', desc: '到访 呼伦贝尔（历史最低温 -50℃ 以下）', done: citySet.has('呼伦贝尔') },
      ],
    },
  ];
}

// 若在浏览器环境，挂到全局供其他脚本使用
if (typeof window !== 'undefined') {
  window.getAchievements = getAchievements;
}
