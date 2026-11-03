package top.qxwkstudio.travel.logic

/**
 * 成就定义与判定 —— **2.0 的唯一一份**。
 *
 * 1.x 把这套放后端（`backend/src/achievements.js`），2.0 离线后搬到这里。
 * 迁移时**逐条照抄**了条件、文案与 code，只做两件事：
 *  - 去掉图标里的 emoji（口径 6/7），图标改由界面按分类给（见 ui/AchievementsFragment）；
 *  - 判定输入从「后端的行程行」改成「本地打卡的城市集合」。
 *
 * `code` 是稳定标识：它进 `achievement_states.code`，改名等于把所有人的这条成就状态清零。
 * 展示名（[AchievementDef.name]）可以随时改。
 *
 * 四种判定方式对应四条规则（见 [Rule]）：城市数里程碑、全部去过、任一去过、单城打卡。
 */
object Achievements {

    /** 判定规则。`target` 是进度条的分母。 */
    sealed interface Rule {
        /** 城市数 ≥ [min]（「到访过 N 座及以上城市」）。 */
        data class CityCount(val min: Int) : Rule

        /** [cities] 全部去过。 */
        data class AllOf(val cities: List<String>) : Rule

        /** [cities] 任一去过。 */
        data class AnyOf(val cities: List<String>) : Rule

        fun target(): Int = when (this) {
            is CityCount -> min
            is AllOf -> cities.size
            is AnyOf -> 1
        }

        fun satisfied(visited: Set<String>): Boolean = when (this) {
            is CityCount -> visited.size >= min
            is AllOf -> cities.all { it in visited }
            is AnyOf -> cities.any { it in visited }
        }

        /** 进度条分子：完成度，封顶到 target（避免 200/100 这种读数）。 */
        fun progress(visited: Set<String>): Int = when (this) {
            is CityCount -> visited.size.coerceAtMost(min)
            is AllOf -> cities.count { it in visited }
            is AnyOf -> if (cities.any { it in visited }) 1 else 0
        }
    }

    data class AchievementDef(
        val code: String,
        val name: String,
        val desc: String,
        val rule: Rule,
    )

    data class Category(val title: String, val items: List<AchievementDef>)

    /** 判定用的一条打卡（只要城市与时间；时间用于回溯解锁日期）。 */
    data class Sample(val cityName: String, val at: Long)

    /** 一条成就的判定结果。[unlockedAt] 是「刚好达成那一刻」的打卡时间，未达成为 null。 */
    data class State(
        val done: Boolean,
        val progress: Int,
        val target: Int,
        val unlockedAt: Long?,
    )

    // ── 城市清单（与 1.x 后端逐字一致；改一个就等于改判定口径）──

    private val METRO = listOf("北京", "上海", "广州", "深圳")
    private val MUNICIPAL = listOf("北京", "天津", "上海", "重庆")
    private val PROV_CAPITALS = listOf(
        "石家庄", "太原", "呼和浩特", "沈阳", "长春", "哈尔滨", "南京", "杭州", "合肥", "福州",
        "南昌", "济南", "郑州", "武汉", "长沙", "广州", "南宁", "海口", "成都", "贵阳", "昆明",
        "拉萨", "西安", "兰州", "西宁", "银川", "乌鲁木齐",
    )
    private val PLATEAU = listOf("拉萨", "西宁", "格尔木", "日喀则", "甘南")
    private val COASTAL = listOf(
        "大连", "青岛", "宁波", "厦门", "深圳", "珠海", "汕头", "湛江", "秦皇岛", "烟台", "威海",
        "连云港", "南通", "温州", "台州", "福州", "泉州", "漳州", "北海", "防城港", "海口", "三亚",
        "唐山", "天津", "上海", "广州", "惠州", "江门", "阳江", "茂名", "盐城", "嘉兴", "舟山",
        "莆田", "宁德",
    )
    private val ANCIENT = listOf("西安", "洛阳", "北京", "南京", "开封", "杭州", "安阳", "郑州")
    private val FIVE_MOUNTAINS = listOf("泰安", "渭南", "衡阳", "大同", "郑州")
    private val GROTTOES = listOf("酒泉", "大同", "洛阳", "天水")
    private val SPECIAL_ZONE = listOf("深圳", "珠海", "汕头", "厦门")
    private val LAUNCH = listOf("酒泉", "太原", "西昌", "文昌")
    private val HAINAN = listOf(
        "海口", "三亚", "三沙", "儋州", "文昌", "琼海", "万宁", "东方", "五指山", "澄迈",
        "定安", "屯昌", "陵水", "昌江", "乐东", "保亭", "琼中", "白沙", "临高",
    )
    private val TAIWAN = listOf("台湾", "台北", "新北", "桃园", "台中", "台南", "高雄", "基隆", "新竹", "嘉义")
    private val HK_MACAU = listOf("香港", "澳门")
    private val GREAT_WALL = listOf(
        "北京", "秦皇岛", "酒泉", "张家口", "忻州", "榆林", "承德", "嘉峪关", "天津", "丹东", "阳泉",
    )

    /** 热河省（1928–1955，省会承德）故地：按今天的地级市取承德、赤峰、朝阳。 */
    private val REHE = listOf("承德", "赤峰", "朝阳")

    // ── 54 条定义（分类顺序 = 界面上的顺序）──

    val categories: List<Category> = listOf(
        Category(
            title = "足迹丰碑",
            items = listOf(
                def("first_trip", "初次启程", "到访过 2 座及以上城市", Rule.CityCount(2)),
                def("city_roamer", "城市漫游", "到访过 5 座及以上城市", Rule.CityCount(5)),
                def("footprint_hunter", "足迹猎手", "到访过 10 座及以上城市", Rule.CityCount(10)),
                def("travel_regular", "旅行常客", "到访过 25 座及以上城市", Rule.CityCount(25)),
                def("globe_trotter", "环游达人", "到访过 50 座及以上城市", Rule.CityCount(50)),
                def("city_collector", "城市收藏家", "到访过 100 座及以上城市", Rule.CityCount(100)),
                def("city_king", "城市之王", "到访过 200 座及以上城市", Rule.CityCount(200)),
                def("grand_tour", "全境巡礼", "到访过 293 座及以上城市", Rule.CityCount(293)),
                def("all_regions", "走遍全国", "到访过全部 384 座城市", Rule.CityCount(384)),
            ),
        ),
        Category(
            title = "巡游四方",
            items = listOf(
                def("metro_all", "都市集章者", "到访过北京、上海、广州、深圳全部四座城市", Rule.AllOf(METRO)),
                def("municipality_all", "直辖市览胜", "到访过北京、天津、上海、重庆全部四座直辖市", Rule.AllOf(MUNICIPAL)),
                def("prov_capitals_all", "省会巡礼", "到访过全部 27 个省会/首府城市", Rule.AllOf(PROV_CAPITALS)),
                def("hk_macau", "港澳穿梭", "到访过香港和澳门全部两地", Rule.AllOf(HK_MACAU)),
                def(
                    "ancient_capitals", "八大古都",
                    "到访过八大古都全部（西安、洛阳、北京、南京、开封、杭州、安阳、郑州）",
                    Rule.AllOf(ANCIENT),
                ),
                def(
                    "five_mountains", "五岳之巅",
                    "到访过五岳所在城市全部（泰山·泰安、华山·渭南、衡山·衡阳、恒山·大同、嵩山·郑州）",
                    Rule.AllOf(FIVE_MOUNTAINS),
                ),
                def(
                    "four_grottoes", "四大石窟",
                    "到访过四大石窟所在城市全部（莫高窟·酒泉、云冈·大同、龙门·洛阳、麦积山·天水）",
                    Rule.AllOf(GROTTOES),
                ),
                def("rehe_province", "热河寻踪", "到访过热河省故地全部（承德、赤峰、朝阳）", Rule.AllOf(REHE)),
                def("plateau_city", "高原之城", "到访过任意一座青藏高原城市（拉萨、西宁、格尔木等）", Rule.AnyOf(PLATEAU)),
                def("coastal_city", "沿海之城", "到访过任意一座沿海地级市（大连、青岛、厦门等）", Rule.AnyOf(COASTAL)),
                def("island_city", "海岛风光", "到访过海南或台湾任意一座城市（海口、三亚、台北等）", Rule.AnyOf(HAINAN + TAIWAN)),
                def(
                    "great_wall", "不到长城非好汉",
                    "到访过任意一座长城名城（北京八达岭、承德金山岭、嘉峪关关城、秦皇岛山海关等）",
                    Rule.AnyOf(GREAT_WALL),
                ),
                def("special_zone", "特区足迹", "到访过任意一座经济特区城市（深圳、珠海、汕头、厦门）", Rule.AnyOf(SPECIAL_ZONE)),
                def("space_launch", "飞天梦", "到访过任一卫星发射中心城市（酒泉、太原、西昌、文昌）", Rule.AnyOf(LAUNCH)),
            ),
        ),
        Category(
            title = "城市打卡",
            items = listOf(
                def("city_shijiazhuang", "国际庄", "到访过 石家庄，食用河北正宗安徽牛肉板面", Rule.AllOf(listOf("石家庄"))),
                def("city_handan", "邯郸学步", "到访过 邯郸，尝试学当地人走路？", Rule.AllOf(listOf("邯郸"))),
                def("city_dandong", "\\o/\\o/", "到访过 丹东，打卡鸭绿江然后在河边蹦蹦跳跳", Rule.AllOf(listOf("丹东"))),
                def("city_harbin", "冰雪大世界", "到访过 哈尔滨，冬天去冰雪大世界", Rule.AllOf(listOf("哈尔滨"))),
                def("city_xuzhou", "优势在我", "到访过 徐州，并说出那句著名的话", Rule.AllOf(listOf("徐州"))),
                def("city_hangzhou", "人间天堂", "到访过 杭州，上有天堂，下……", Rule.AllOf(listOf("杭州"))),
                def("city_sanming", "沙县小吃", "到访过 三明", Rule.AllOf(listOf("三明"))),
                def("city_nanchang", "滕王高阁", "到访过 南昌，（完整）背诵一遍滕王阁序（", Rule.AllOf(listOf("南昌"))),
                def("city_jingdezhen", "瓷都", "到访过 景德镇，制作自己的瓷器", Rule.AllOf(listOf("景德镇"))),
                def("city_heze", "宇宙中心", "到访过 菏泽", Rule.AllOf(listOf("菏泽"))),
                def("city_weifang", "风筝之都", "到访过 潍坊，参加风筝节", Rule.AllOf(listOf("潍坊"))),
                def("city_xinxiang", "New York", "到访过 新乡，欢迎来到纽约！", Rule.AllOf(listOf("新乡"))),
                def("city_wuhan", "黄鹤楼下", "到访过 武汉，崔颢的《黄鹤楼》总会背吧？", Rule.AllOf(listOf("武汉"))),
                def("city_yichang", "三峡门户", "到访过 宜昌", Rule.AllOf(listOf("宜昌"))),
                def("city_yueyang", "岳阳楼记", "到访过 岳阳，完整背诵《岳阳楼记》", Rule.AllOf(listOf("岳阳"))),
                def("city_changsha", "橘子洲头", "到访过 长沙，不去橘子洲头那不白去了吗？", Rule.AllOf(listOf("长沙"))),
                def("city_foshan", "家具中心", "到访过 佛山，佛山工厂造出来了！", Rule.AllOf(listOf("佛山"))),
                def("city_guilin", "甲天下", "到访过 桂林，桂林山水甲天下！", Rule.AllOf(listOf("桂林"))),
                def("city_liuzhou", "螺蛳粉", "到访过 柳州，螺蛳粉真的好吃吗……？", Rule.AllOf(listOf("柳州"))),
                def("city_chengdu", "天府之国", "到访过 成都，也是刻板印象了（", Rule.AllOf(listOf("成都"))),
                def("city_dali", "风花雪月", "到访过 大理", Rule.AllOf(listOf("大理"))),
                def("city_xian", "西安人的歌", "到访过 西安，并听一曲西安人的歌", Rule.AllOf(listOf("西安"))),
                def("city_yanan", "革命圣地", "到访过 延安", Rule.AllOf(listOf("延安"))),
            ),
        ),
        Category(
            title = "极限挑战",
            items = listOf(
                def("east_end_jiamusi", "极东破晓", "到访过中国最东端——佳木斯", Rule.AllOf(listOf("佳木斯"))),
                def("west_end_kezilesu", "极西暮歌", "到访过中国最西端——克孜勒苏", Rule.AllOf(listOf("克孜勒苏"))),
                def("south_end_sansha", "极南听涛", "到访过中国最南端——三沙", Rule.AllOf(listOf("三沙"))),
                def("north_end_daxinganling", "极北寻光", "到访过中国最北端——大兴安岭", Rule.AllOf(listOf("大兴安岭"))),
                def("highest_naqu", "云端之巅", "到访过海拔最高的地级行政区——那曲", Rule.AllOf(listOf("那曲"))),
                def("lowest_turpan", "盆地之渊", "到访过中国海拔最低点（艾丁湖）所在地——吐鲁番", Rule.AllOf(listOf("吐鲁番"))),
                def("hottest_turpan", "火洲炼狱", "到访过中国最热的地方——吐鲁番", Rule.AllOf(listOf("吐鲁番"))),
                def("coldest_hulunbuir", "北境寒极", "到访过中国最冷的地方——呼伦贝尔", Rule.AllOf(listOf("呼伦贝尔"))),
            ),
        ),
    )

    fun all(): List<AchievementDef> = categories.flatMap { it.items }

    /**
     * 判定全部成就，并按时间**回溯解锁日期**。
     *
     * 回溯的意义：重装、清库、以后导入备份之后重新算，得到的解锁日期必须是当初那条打卡的时间，
     * 而不是「现在」。做法是按时间升序喂打卡，某条成就第一次成立时记下那一刻 ——
     * 所以 [samples] 的顺序在这里被显式排序，调用方给什么顺序都不影响结果。
     */
    fun evaluate(samples: List<Sample>): Map<String, State> {
        val defs = all()
        val visited = LinkedHashSet<String>()
        val unlockedAt = HashMap<String, Long>()
        val ordered = samples.sortedBy { it.at }

        for (sample in ordered) {
            if (!visited.add(sample.cityName)) continue
            for (def in defs) {
                if (def.code in unlockedAt) continue
                if (def.rule.satisfied(visited)) unlockedAt[def.code] = sample.at
            }
        }

        return defs.associate { def ->
            def.code to State(
                done = def.rule.satisfied(visited),
                progress = def.rule.progress(visited),
                target = def.rule.target(),
                unlockedAt = unlockedAt[def.code],
            )
        }
    }

    private fun def(code: String, name: String, desc: String, rule: Rule) =
        AchievementDef(code, name, desc, rule)
}
