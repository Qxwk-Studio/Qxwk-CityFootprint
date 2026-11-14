package top.qxwkstudio.travel.logic

/**
 * 成就定义与判定 —— **2.0 的唯一一份**。
 *
 * `code` 是稳定标识：它进 `achievement_states.code`，改名等于把所有人的这条成就状态清零。
 * 展示名（[AchievementDef.name]）与描述可以随时改。
 *
 * 判定方式：城市数里程碑、全部去过、任一去过、单城打卡、打卡次数、足迹次数。
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

        /** 打卡总次数 ≥ [min]。 */
        data class CheckinCount(val min: Int) : Rule

        /** 足迹总条数 ≥ [min]。 */
        data class TripCount(val min: Int) : Rule

        fun target(): Int = when (this) {
            is CityCount -> min
            is AllOf -> cities.size
            is AnyOf -> 1
            is CheckinCount -> min
            is TripCount -> min
        }

        fun satisfied(visited: Set<String>): Boolean = when (this) {
            is CityCount -> visited.size >= min
            is AllOf -> cities.all { it in visited }
            is AnyOf -> cities.any { it in visited }
            is CheckinCount, is TripCount -> false
        }

        /** 进度条分子：完成度，封顶到 target（避免 200/100 这种读数）。 */
        fun progress(visited: Set<String>): Int = when (this) {
            is CityCount -> visited.size.coerceAtMost(min)
            is AllOf -> cities.count { it in visited }
            is AnyOf -> if (cities.any { it in visited }) 1 else 0
            is CheckinCount, is TripCount -> 0
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

    // ── 城市清单 ──

    private val METRO = listOf("北京", "上海", "广州", "深圳")
    private val MUNICIPAL = listOf("北京", "天津", "上海", "重庆")
    private val PROV_CAPITALS = listOf(
        "石家庄", "太原", "呼和浩特", "沈阳", "长春", "哈尔滨", "南京", "杭州", "合肥", "福州",
        "南昌", "济南", "郑州", "武汉", "长沙", "广州", "南宁", "海口", "成都", "贵阳", "昆明",
        "拉萨", "西安", "兰州", "西宁", "银川", "乌鲁木齐",
    )
    private val PLATEAU = listOf("拉萨", "西宁", "格尔木", "日喀则", "甘南", "那曲", "阿里")
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
    private val JIANGNAN = listOf("苏州", "杭州", "无锡", "嘉兴", "湖州")
    private val SHANHE = listOf("太原", "济南", "郑州", "石家庄")
    private val NORTHEAST = listOf("沈阳", "长春", "哈尔滨")
    private val SILK_ROAD = listOf("西安", "兰州", "酒泉", "吐鲁番", "乌鲁木齐", "喀什")
    private val GRAND_CANAL = listOf("北京", "天津", "济宁", "徐州", "淮安", "扬州", "无锡", "杭州")
    private val BUDDHIST_MOUNTAINS = listOf("忻州", "乐山", "舟山", "池州")
    private val RED_SITES = listOf("延安", "吉安", "遵义", "赣州", "石家庄")

    /** 热河省（1928–1955，省会承德）故地：按今天的地级市取承德、赤峰、朝阳。 */
    private val REHE = listOf("承德", "赤峰", "朝阳")

    // ── 定义（分类顺序 = 界面上的顺序）──

    val categories: List<Category> = listOf(
        Category(
            title = "足迹丰碑",
            items = listOf(
                // 城市数量
                def("first_step", "始于足下", "就此开始你的第一次旅行吧", Rule.CityCount(1)),
                def("first_trip", "双城记", "到访过 2 座及以上城市", Rule.CityCount(2)),
                def("city_roamer", "城市漫游", "到访过 5 座及以上城市", Rule.CityCount(5)),
                def("footprint_hunter", "十城十味", "到访过 10 座及以上城市", Rule.CityCount(10)),
                def("travel_regular", "旅行常客", "到访过 25 座及以上城市", Rule.CityCount(25)),
                def("globe_trotter", "城市收藏家", "到访过 50 座及以上城市", Rule.CityCount(50)),
                def("city_collector", "百城印记", "到访过 100 座及以上城市", Rule.CityCount(100)),
                def("grand_tour", "全境巡礼", "到访过全国 293 座地级市", Rule.CityCount(293)),
                // 打卡次数
                def("checkin_1", "首次打卡", "添加你的第一次打卡记录", Rule.CheckinCount(1)),
                def("checkin_10", "十章十美", "累计打卡 10 次", Rule.CheckinCount(10)),
                def("checkin_50", "马不停蹄", "累计打卡 50 次", Rule.CheckinCount(50)),
                def("checkin_100", "百章全书", "累计打卡 100 次", Rule.CheckinCount(100)),
                // 足迹次数
                def("trip_1", "开卷有宜", "添加你的第一次足迹记录", Rule.TripCount(1)),
                def("trip_10", "十卷远行", "累计创建 10 条足迹", Rule.TripCount(10)),
                def("trip_50", "日积月累", "累计创建 50 条足迹", Rule.TripCount(50)),
                def("trip_100", "遍历一方", "累计创建 100 条足迹", Rule.TripCount(100)),
            ),
        ),
        Category(
            title = "城市打卡",
            items = listOf(
                def("city_beijing", "皇城根下", "到访过 北京，哎哟喂~这叫一个地道~", Rule.AllOf(listOf("北京"))),
                def("city_shijiazhuang", "国际庄", "到访过 石家庄，食用河北正宗安徽牛肉板面", Rule.AllOf(listOf("石家庄"))),
                def("city_handan", "邯郸学步", "到访过 邯郸，尝试学当地人走路？", Rule.AllOf(listOf("邯郸"))),
                def("city_dalian", "浪漫之都", "到访过 大连，坐一趟海边有轨电车", Rule.AllOf(listOf("大连"))),
                def("city_dandong", "\\o/ \\o/", "到访过 丹东，打卡鸭绿江然后在河边蹦蹦跳跳", Rule.AllOf(listOf("丹东"))),
                def("city_harbin", "冰城", "到访过 哈尔滨，中央大街吃马迭尔", Rule.AllOf(listOf("哈尔滨"))),
                def("city_shanghai", "魔都夜色", "到访过 上海，要来一杯蜜雪吗？", Rule.AllOf(listOf("上海"))),
                def("city_suzhou", "园林一梦", "到访过 苏州，是坐飞机到的吗？", Rule.AllOf(listOf("苏州"))),
                def("city_xuzhou", "优势在我", "到访过 徐州，并说出那句著名的话", Rule.AllOf(listOf("徐州"))),
                def("city_hangzhou", "人间天堂", "到访过 杭州，上有天堂，下有……", Rule.AllOf(listOf("杭州"))),
                def("city_huangshan", "黄山归来", "到访过 黄山，五岳归来不看山", Rule.AllOf(listOf("黄山"))),
                def("city_xiamen", "海上花园", "到访过 厦门，在鼓浪屿迷路一次", Rule.AllOf(listOf("厦门"))),
                def("city_nanchang", "滕王高阁", "到访过 南昌，（完整）背诵一遍《滕王阁序》（", Rule.AllOf(listOf("南昌"))),
                def("city_jingdezhen", "瓷都", "到访过 景德镇，亲手制作自己的瓷器", Rule.AllOf(listOf("景德镇"))),
                def("city_qingdao", "啤酒泡沫", "到访过 青岛，塑料袋装啤酒才算来过", Rule.AllOf(listOf("青岛"))),
                def("city_weifang", "风筝之都", "到访过 潍坊，参加风筝节", Rule.AllOf(listOf("潍坊"))),
                def("city_luoyang", "风起神都", "到访过 洛阳，牡丹花开的时候来", Rule.AllOf(listOf("洛阳"))),
                def("city_kaifeng", "汴京", "到访过 开封，清明上河园里逛宋朝", Rule.AllOf(listOf("开封"))),
                def("city_xinxiang", "New York", "到访过 新乡，欢迎来到纽约！", Rule.AllOf(listOf("新乡"))),
                def("city_wuhan", "黄鹤楼下", "到访过 武汉，崔颢的《黄鹤楼》总会背吧？", Rule.AllOf(listOf("武汉"))),
                def("city_yichang", "三峡门户", "到访过 宜昌，看三峡大坝，感受一把“高峡出平湖”", Rule.AllOf(listOf("宜昌"))),
                def("city_changsha", "橘子洲头", "到访过 长沙，不去橘子洲头那不白去了吗？", Rule.AllOf(listOf("长沙"))),
                def("city_zhangjiajie", "奇峰三千", "到访过 张家界，看看阿凡达取景地", Rule.AllOf(listOf("张家界"))),
                def("city_yueyang", "岳阳楼记", "到访过 岳阳，完整背诵《岳阳楼记》", Rule.AllOf(listOf("岳阳"))),
                def("city_chaozhou", "工夫茶", "到访过 潮州，一泡三杯慢慢喝", Rule.AllOf(listOf("潮州"))),
                def("city_foshan", "家具中心", "到访过 佛山，佛山工厂造出来了！", Rule.AllOf(listOf("佛山"))),
                def("city_guilin", "山水甲天下", "到访过 桂林，桂林山水甲天下！", Rule.AllOf(listOf("桂林"))),
                def("city_liuzhou", "螺蛳粉", "到访过 柳州，螺蛳粉真的好吃吗……？", Rule.AllOf(listOf("柳州"))),
                def("city_sanya", "天涯海角", "到访过 三亚，冬天也能穿短袖", Rule.AllOf(listOf("三亚"))),
                def("city_chongqing", "8D 魔幻", "到访过 重庆，导航在这里也会迷路", Rule.AllOf(listOf("重庆"))),
                def("city_chengdu", "巴适得板", "到访过 成都，骑熊猫！骑熊猫！", Rule.AllOf(listOf("成都"))),
                def("city_kunming", "春城", "到访过 昆明，一年四季都像春天", Rule.AllOf(listOf("昆明"))),
                def("city_dali", "风花雪月", "到访过 大理，苍山洱海走一遭", Rule.AllOf(listOf("大理"))),
                def("city_lijiang", "古城慢时光", "到访过 丽江，四方街发一下午呆", Rule.AllOf(listOf("丽江"))),
                def("city_lhasa", "日光之城", "到访过 拉萨，布达拉宫下晒太阳", Rule.AllOf(listOf("拉萨"))),
                def("city_xian", "长安十二时辰", "到访过 西安，并听一曲西安人的歌", Rule.AllOf(listOf("西安"))),
                def("city_yanan", "革命圣地", "到访过 延安，宝塔山下感受红色精神", Rule.AllOf(listOf("延安"))),
                def("city_urumqi", "亚心之都", "到访过 乌鲁木齐，大盘鸡配皮带面", Rule.AllOf(listOf("乌鲁木齐"))),
                def("city_kashi", "西域风情", "到访过 喀什，老城茶馆坐一坐", Rule.AllOf(listOf("喀什"))),
            ),
        ),
        Category(
            title = "巡游四方",
            items = listOf(
                def("coastal_city", "沿海之城", "到访任意一个沿海城市", Rule.AnyOf(COASTAL)),
                def("special_zone", "特区足迹", "到访任意一个经济特区：深圳、珠海、汕头、厦门", Rule.AnyOf(SPECIAL_ZONE)),
                def("grand_canal", "大运河", "到访任意一个大运河节点：北京、天津、济宁、徐州、淮安、扬州、无锡、杭州", Rule.AnyOf(GRAND_CANAL)),
                def("jiangnan_all", "江南水乡", "到访任意一个江南水乡：苏州、杭州、无锡、嘉兴、湖州", Rule.AnyOf(JIANGNAN)),
                def(
                    "great_wall", "不到长城非好汉",
                    "点亮长城名城：北京、秦皇岛、酒泉、张家口、忻州、榆林、承德、嘉峪关、天津、丹东、阳泉，任一到访",
                    Rule.AnyOf(GREAT_WALL),
                ),
                def("silk_road", "丝绸之路", "到访任意一个丝路节点：西安、兰州、酒泉、吐鲁番、乌鲁木齐、喀什", Rule.AnyOf(SILK_ROAD)),
                def("space_launch", "飞天梦", "到访任意一个卫星发射中心城：酒泉、太原、西昌、文昌", Rule.AnyOf(LAUNCH)),
                def(
                    "island_city", "海岛风光", "到访任意一个海岛城市",
                    Rule.AnyOf(HAINAN + TAIWAN),
                ),
                def(
                    "plateau_city", "高原之城",
                    "点亮青藏高原城市：拉萨、西宁、格尔木、日喀则、甘南、那曲、阿里，任一到访",
                    Rule.AnyOf(PLATEAU),
                ),
                def("metro_all", "都市集章者", "走遍一线都市：北京、上海、广州、深圳，全部到访", Rule.AllOf(METRO)),
                def("municipality_all", "直辖市览胜", "走遍直辖市：北京、天津、上海、重庆，全部到访", Rule.AllOf(MUNICIPAL)),
                def("prov_capitals_all", "省会巡礼", "走遍所有的省会与首府", Rule.AllOf(PROV_CAPITALS)),
                def("hk_macau", "港澳穿梭", "走遍港澳：香港、澳门，全部到访", Rule.AllOf(HK_MACAU)),
                def(
                    "ancient_capitals", "八大古都",
                    "走遍八大古都：西安、洛阳、北京、南京、开封、杭州、安阳、郑州，全部到访",
                    Rule.AllOf(ANCIENT),
                ),
                def(
                    "shanhe_four", "山河四省",
                    "走遍山河四省省会：太原、济南、郑州、石家庄，全部到访",
                    Rule.AllOf(SHANHE),
                ),
                def("northeast_all", "东北三省", "走遍东北三省省会：沈阳、长春、哈尔滨，全部到访", Rule.AllOf(NORTHEAST)),
                def(
                    "five_mountains", "五岳之巅",
                    "走遍五岳所在城：泰安、渭南、衡阳、大同、郑州，全部到访",
                    Rule.AllOf(FIVE_MOUNTAINS),
                ),
                def(
                    "four_grottoes", "四大石窟",
                    "走遍四大石窟所在城：酒泉、大同、洛阳、天水，全部到访",
                    Rule.AllOf(GROTTOES),
                ),
                def(
                    "buddhist_mountains", "佛教名山",
                    "走遍佛教名山所在城：忻州、乐山、舟山、池州，全部到访",
                    Rule.AllOf(BUDDHIST_MOUNTAINS),
                ),
                def(
                    "red_sites", "红色圣地",
                    "走遍红色圣地所在城：延安、吉安、遵义、赣州、石家庄，全部到访",
                    Rule.AllOf(RED_SITES),
                ),
                def("rehe_province", "热河寻踪", "走遍热河故地：承德、赤峰、朝阳，全部到访", Rule.AllOf(REHE)),
            ),
        ),
        Category(
            title = "极限挑战",
            items = listOf(
                def("east_end_jiamusi", "极东破晓", "到访过中国最东端——佳木斯", Rule.AllOf(listOf("佳木斯"))),
                def("south_end_sansha", "极南听涛", "到访过中国最南端——三沙", Rule.AllOf(listOf("三沙"))),
                def("west_end_kezilesu", "极西暮歌", "到访过中国最西端——克孜勒苏", Rule.AllOf(listOf("克孜勒苏"))),
                def("north_end_daxinganling", "极北寻光", "到访过中国最北端——大兴安岭", Rule.AllOf(listOf("大兴安岭"))),
                def("arid_turpan", "火洲旱极", "到访过年降水量仅约 16 毫米的极端干旱地——吐鲁番", Rule.AllOf(listOf("吐鲁番"))),
                def("rainiest_yaan", "极雨听霖", "到访过中国雨极——雅安", Rule.AllOf(listOf("雅安"))),
                def("sunny_baotou", "极晴沐光", "到访过日照表现极突出的城市——包头", Rule.AllOf(listOf("包头"))),
                def("hottest_turpan", "火洲热极", "到访过中国最热的地方——吐鲁番", Rule.AllOf(listOf("吐鲁番"))),
                def("coldest_hulunbuir", "北境寒极", "到访过中国最冷的地方之一——呼伦贝尔", Rule.AllOf(listOf("呼伦贝尔"))),
                def("highest_naqu", "云端之巅", "到访过海拔最高的地级行政区——那曲", Rule.AllOf(listOf("那曲"))),
                def("lowest_turpan", "盆地之渊", "到访过中国海拔最低点（艾丁湖）所在地——吐鲁番", Rule.AllOf(listOf("吐鲁番"))),
                def("windy_alashankou", "极风啸隘", "到访过中国著名风口“黑风口”——阿拉山口", Rule.AllOf(listOf("博尔塔拉"))),
                def("dusty_akesu", "沙尘之极", "到访过沙尘天气曾超百天的城市——阿克苏", Rule.AllOf(listOf("阿克苏"))),
            ),
        ),
    )

    fun all(): List<AchievementDef> = categories.flatMap { it.items }

    /**
     * 判定全部成就，并按时间**回溯解锁日期**。
     *
     * [samples] 是打卡（重复城市也计入打卡次数，但城市类成就只算首次）；
     * [tripTimes] 是足迹的创建时间，用于足迹次数类成就。
     */
    fun evaluate(samples: List<Sample>, tripTimes: List<Long> = emptyList()): Map<String, State> {
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

        val checkinTimes = ordered.map { it.at }
        val tripTimesSorted = tripTimes.sorted()

        return defs.associate { def ->
            val state = when (val rule = def.rule) {
                is Rule.CheckinCount -> {
                    val count = checkinTimes.size
                    State(
                        done = count >= rule.min,
                        progress = count.coerceAtMost(rule.min),
                        target = rule.min,
                        unlockedAt = if (count >= rule.min) checkinTimes[rule.min - 1] else null,
                    )
                }
                is Rule.TripCount -> {
                    val count = tripTimesSorted.size
                    State(
                        done = count >= rule.min,
                        progress = count.coerceAtMost(rule.min),
                        target = rule.min,
                        unlockedAt = if (count >= rule.min) tripTimesSorted[rule.min - 1] else null,
                    )
                }
                else -> State(
                    done = rule.satisfied(visited),
                    progress = rule.progress(visited),
                    target = rule.target(),
                    unlockedAt = unlockedAt[def.code],
                )
            }
            def.code to state
        }
    }

    private fun def(code: String, name: String, desc: String, rule: Rule) =
        AchievementDef(code, name, desc, rule)
}
