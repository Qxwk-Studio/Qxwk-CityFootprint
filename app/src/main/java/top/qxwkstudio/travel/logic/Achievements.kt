package top.qxwkstudio.travel.logic

/**
 * 成就定义与判定 —— 从网页版 frontend/achievements.js 原样搬过来的（同一份名称、图标、阈值）。
 *
 * 为什么值得逐条抄而不是「顺手重写一遍」：成就是**给人看的荣誉**，网页版已经发出去过，
 * 两端名字或阈值对不上，同一个人在 App 与网页上看到的徽章数量会不同，解释起来最伤信任。
 * 所以这里只把 JS 翻成 Kotlin，一所不改；要改就先改网页版，再同步这一份。
 *
 * 与 JS 的唯一差别是「去重」：JS 的 cityCount 是 new Set(cityNames).size（同一座城市打卡多次只算一座），
 * 这里在入口同样先 distinct()，保证「去过的城市数」口径一致。
 */
data class Achievement(val icon: String, val name: String, val desc: String, val done: Boolean)

data class AchievementGroup(val title: String, val items: List<Achievement>)

object Achievements {
    private val METRO = listOf("北京", "上海", "广州", "深圳")
    private val MUNICIPAL = listOf("北京", "天津", "上海", "重庆")
    private val PROV_CAPITALS = listOf(
        "石家庄", "太原", "呼和浩特", "沈阳", "长春", "哈尔滨", "南京", "杭州", "合肥", "福州",
        "南昌", "济南", "郑州", "武汉", "长沙", "广州", "南宁", "海口", "成都", "贵阳", "昆明",
        "拉萨", "西安", "兰州", "西宁", "银川", "乌鲁木齐"
    )
    private val PLATEAU = listOf("拉萨", "西宁", "格尔木", "日喀则", "甘南")
    private val COASTAL = listOf(
        "大连", "青岛", "宁波", "厦门", "深圳", "珠海", "汕头", "湛江", "秦皇岛", "烟台", "威海",
        "连云港", "南通", "温州", "台州", "福州", "泉州", "漳州", "北海", "防城港", "海口", "三亚",
        "唐山", "天津", "上海", "广州", "惠州", "江门", "阳江", "茂名", "盐城", "嘉兴", "舟山",
        "莆田", "宁德"
    )
    private val ANCIENT = listOf("西安", "洛阳", "北京", "南京", "开封", "杭州", "安阳", "郑州")
    private val FIVE_MOUNTAINS = listOf("泰安", "渭南", "衡阳", "大同", "郑州")
    private val GROTTOES = listOf("酒泉", "大同", "洛阳", "天水")
    private val SPECIAL_ZONE = listOf("深圳", "珠海", "汕头", "厦门")
    private val LAUNCH = listOf("酒泉", "太原", "西昌", "文昌")
    private val HAINAN = listOf(
        "海口", "三亚", "三沙", "儋州", "文昌", "琼海", "万宁", "东方", "五指山", "澄迈", "定安",
        "屯昌", "陵水", "昌江", "乐东", "保亭", "琼中", "白沙", "临高"
    )
    private val TAIWAN = listOf(
        "台湾", "台北", "新北", "桃园", "台中", "台南", "高雄", "基隆", "新竹", "嘉义"
    )
    private val HK_MACAU = listOf("香港", "澳门")
    private val GREAT_WALL = listOf(
        "北京", "秦皇岛", "酒泉", "张家口", "忻州", "榆林", "承德", "嘉峪关", "天津", "丹东", "阳泉"
    )

    /** 参数是**去过的城市名**（同一座城市重复打卡只算一次，见文件头）。 */
    fun all(cityNames: Collection<String>): List<AchievementGroup> {
        val citySet = cityNames.toSet()
        val cityCount = citySet.size
        fun hasAll(cities: List<String>) = cities.all { citySet.contains(it) }
        fun hasAny(cities: List<String>) = cities.any { citySet.contains(it) }

        return listOf(
            AchievementGroup(
                "🌟 足迹丰碑",
                listOf(
                    Achievement("🚀", "初次启程", "到访过 2 座及以上城市", cityCount >= 2),
                    Achievement("🏙️", "城市漫游", "到访过 5 座及以上城市", cityCount >= 5),
                    Achievement("🗺️", "足迹猎手", "到访过 10 座及以上城市", cityCount >= 10),
                    Achievement("✈️", "旅行常客", "到访过 25 座及以上城市", cityCount >= 25),
                    Achievement("🌍", "环游达人", "到访过 50 座及以上城市", cityCount >= 50),
                    Achievement("🏆", "城市收藏家", "到访过 100 座及以上城市", cityCount >= 100),
                    Achievement("👑", "城市之王", "到访过 200 座及以上城市", cityCount >= 200),
                    Achievement("🌟", "全境巡礼", "到访过全国全部 293 个地级行政区", cityCount >= 293),
                )
            ),
            AchievementGroup(
                "🚩 巡游四方",
                listOf(
                    Achievement("🏙️", "都市集章者", "到访过北京、上海、广州、深圳全部四座城市", hasAll(METRO)),
                    Achievement("🏛️", "直辖市览胜", "到访过北京、天津、上海、重庆全部四座直辖市", hasAll(MUNICIPAL)),
                    Achievement("🏯", "省会巡礼", "到访过全部 27 个省会/首府城市", hasAll(PROV_CAPITALS)),
                    Achievement("🌉", "港澳穿梭", "到访过香港和澳门全部两地", hasAll(HK_MACAU)),
                    Achievement(
                        "🏯", "八大古都",
                        "到访过八大古都全部（西安、洛阳、北京、南京、开封、杭州、安阳、郑州）",
                        hasAll(ANCIENT)
                    ),
                    Achievement(
                        "⛰️", "五岳之巅",
                        "到访过五岳所在城市全部（泰山·泰安、华山·渭南、衡山·衡阳、恒山·大同、嵩山·郑州）",
                        hasAll(FIVE_MOUNTAINS)
                    ),
                    Achievement(
                        "🗿", "四大石窟",
                        "到访过四大石窟所在城市全部（莫高窟·酒泉、云冈·大同、龙门·洛阳、麦积山·天水）",
                        hasAll(GROTTOES)
                    ),
                    Achievement("🏔️", "高原之城", "到访过任意一座青藏高原城市（拉萨、西宁、格尔木等）", hasAny(PLATEAU)),
                    Achievement("🌊", "沿海之城", "到访过任意一座沿海地级市（大连、青岛、厦门等）", hasAny(COASTAL)),
                    Achievement(
                        "🏖️", "海岛风光",
                        "到访过海南或台湾任意一座城市（海口、三亚、台北等）",
                        hasAny(HAINAN + TAIWAN)
                    ),
                    Achievement(
                        "🧱", "不到长城非好汉",
                        "到访过任意一座长城名城（北京八达岭、承德金山岭、嘉峪关关城、秦皇岛山海关等）",
                        hasAny(GREAT_WALL)
                    ),
                    Achievement("🌴", "特区足迹", "到访过任意一座经济特区城市（深圳、珠海、汕头、厦门）", hasAny(SPECIAL_ZONE)),
                    Achievement("🚀", "飞天梦", "到访过任一卫星发射中心城市（酒泉、太原、西昌、文昌）", hasAny(LAUNCH)),
                )
            ),
            AchievementGroup(
                "📍 城市打卡",
                listOf(
                    Achievement("🎯", "优势在我", "到访过 徐州", citySet.contains("徐州")),
                    Achievement("☀️", "\\o/\\o/", "到访过 丹东", citySet.contains("丹东")),
                    Achievement("🏘️", "国际庄", "到访过 石家庄", citySet.contains("石家庄")),
                    Achievement("🗽", "New York", "到访过 新乡", citySet.contains("新乡")),
                    Achievement("🪐", "宇宙中心", "到访过 菏泽", citySet.contains("菏泽")),
                    Achievement("🎤", "西安人的歌", "到访过 西安", citySet.contains("西安")),
                    Achievement("🌙", "黄鹤楼下", "到访过 武汉", citySet.contains("武汉")),
                    Achievement("🏯", "滕王高阁", "到访过 南昌", citySet.contains("南昌")),
                    Achievement("🌅", "岳阳楼记", "到访过 岳阳", citySet.contains("岳阳")),
                    Achievement("🏝️", "橘子洲头", "到访过 长沙", citySet.contains("长沙")),
                    Achievement("🪁", "风筝之都", "到访过 潍坊", citySet.contains("潍坊")),
                    Achievement("🍢", "进淄赶烤", "到访过 淄博", citySet.contains("淄博")),
                    Achievement("🏰", "避暑山庄", "到访过 承德", citySet.contains("承德")),
                )
            ),
            AchievementGroup(
                "🧭 极限挑战",
                listOf(
                    Achievement("🌅", "极东破晓", "到访过中国最东端——佳木斯", citySet.contains("佳木斯")),
                    Achievement("🌄", "极西暮歌", "到访过中国最西端——克孜勒苏", citySet.contains("克孜勒苏")),
                    Achievement("🌊", "极南听涛", "到访过中国最南端——三沙", citySet.contains("三沙")),
                    Achievement("❄️", "极北寻光", "到访过中国最北端——大兴安岭", citySet.contains("大兴安岭")),
                    Achievement("🏔️", "云端之巅", "到访过海拔最高的地级行政区——那曲", citySet.contains("那曲")),
                    Achievement(
                        "🏜️", "盆地之渊",
                        "到访过中国海拔最低点（艾丁湖）所在地——吐鲁番",
                        citySet.contains("吐鲁番")
                    ),
                    Achievement("🔥", "火洲炼狱", "到访过中国最热的地方——吐鲁番", citySet.contains("吐鲁番")),
                    Achievement("❄️", "北境寒极", "到访过中国最冷的地方——呼伦贝尔", citySet.contains("呼伦贝尔")),
                )
            ),
        )
    }

    /** 已达成 / 总数（现在一共 42 条，单测钉住这个数），用于统计页那句「已点亮 12 / 42」。 */
    fun progress(groups: List<AchievementGroup>): Pair<Int, Int> {
        val items = groups.flatMap { it.items }
        return items.count { it.done } to items.size
    }
}