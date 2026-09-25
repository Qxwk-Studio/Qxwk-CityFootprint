package top.qxwkstudio.travel.logic

/**
 * 城市数据模型。数据来自 assets/cities.json，那份文件由 tools/gen-cities.mjs 从
 * frontend/cities.js（名称/省份/坐标）与 frontend/city-codes.js（名称 → adcode）生成，可重复运行。
 *
 * adcode 可能是 null：源数据里有县级市（格尔木、伊宁、库尔勒…）在 city-codes.js 里没有记录，
 * 地图上就取不到边界，只能退回「只画标记」这种降级形态（见 ui/MapFragment）。
 */
data class City(val name: String, val province: String, val lat: Double, val lng: Double, val adcode: Int?)

/**
 * 城市搜索。刻意做成纯函数（不碰 Context / 不读 assets），单测才好直接喂数据进来。
 */
object CitySearch {
    /**
     * 按名称 / 省份模糊匹配。
     *
     * 两点约定：
     *  1. 空查询返回**全部**（城市选择页一进去就该看到完整列表，而不是一片空白）。
     *  2. 同时比对原名与小写形式。中文城市名大小写无意义，但源数据里混着拼音式的英文片段
     *     （台湾、香港那几个的省份名是中文，问题不大），留着 lowercase 是为了用户输入英文时
     *     不至于一条都搜不到 —— 代价只是一次 lowercase。
     *
     * 刻意没有做「拼音首字母」匹配：源数据（cities.js / city-codes.js）里没有拼音字段，
     * 要支持就得在仓库里再塞一张几千字的拼音表，收益（多打一个字母）与那份数据的维护成本不成比例。
     * 哪天 gen-cities.mjs 的源文件里出现了拼音字段，再在这里加一条比对即可。
     */
    fun filter(cities: List<City>, query: String): List<City> {
        val q = query.trim()
        if (q.isEmpty()) return cities
        val lower = q.lowercase()
        return cities.filter { city ->
            city.name.contains(q) || city.province.contains(q) ||
                city.name.lowercase().contains(lower) || city.province.lowercase().contains(lower)
        }
    }

    /** 按名字找城市（编辑足迹时用它把已存的城市名还原成坐标/adcode）。找不到就是源数据里没这座城。 */
    fun findByName(cities: List<City>, name: String): City? = cities.firstOrNull { it.name == name }
}