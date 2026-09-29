package top.qxwkstudio.travel.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import top.qxwkstudio.travel.BuildConfig
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.VisitRepo
import top.qxwkstudio.travel.databinding.FragmentMapBinding
import top.qxwkstudio.travel.logic.CityVisit
import top.qxwkstudio.travel.logic.MapCity
import top.qxwkstudio.travel.logic.VisitDate

/**
 * 「地图」：把每个人去过的城市铺在 osmdroid 地图上，界面与交互对齐网页 docs/index.html。
 *
 * 选型理由（与 README 同一份）：osmdroid 是纯 Android View 的 OSM 地图，**不需要 API key**，
 * 与「界面和地图都原生、不引 WebView」的要求一致。被否掉的方案：
 *  - Google Maps SDK：要申请 API key 并绑包名/签名，个人项目多一处密钥管理；
 *  - WebView 套网页版地图：用户明确不要 WebView。
 *
 * 与网页逐条对齐的四件事：
 *  1. 底图换成高德免 Key 瓦片（同一套 URL，见 [AmapTileSource]）；
 *  2. 数据取 GET /api/cities（城市 + 坐标 + 去过的人），而不是只画自己的行程；
 *  3. 每个城市按「最新一位勾选者」的颜色**填成半透明多边形**（拿不到 adcode/边界就退回圆点）；
 *  4. 左上角图例按用户勾选筛选，点城市时底部卡片列出最近 10 条行程。
 *
 * 唯一做不到的是网页的 **hover 提亮**：触屏没有 hover，改为「点开明细卡」这一种反馈。
 *
 * 边界是「附加信息」，整条链路都**可降级**：解析失败/拿不到 adcode 都退回圆点，标记永远留着
 * （不把已经画好的东西清掉）。
 */
class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store

    /** 城市图层（多边形或回退圆点）。筛选一变就整体重建，见 [renderCities]。 */
    private val cityOverlays = mutableListOf<Overlay>()

    /** 图例里的用户与颜色（昵称 → 颜色），顺序即后端给的顺序。 */
    private val userInfo = LinkedHashMap<String, String>()

    /** 边界解析结果（adcode → 点环）。同一座城市反复筛选时不重复请求——
     *  这一层只管本次视图存活期间；跨进程那层是 data/GeoCache 的磁盘缓存。 */
    private val geoCache = mutableMapOf<Int, List<List<GeoPoint>>>()

    /** 城市明细（点开卡片时按需拉一次，之后走这份内存缓存）。 */
    private val cityDetailCache = mutableMapOf<String, List<CityVisit>>()

    private var cities: List<MapCity> = emptyList()

    /** 渲染批次号：筛选一改就自增，异步边界回来时对不上号就丢弃（免得旧筛选的结果盖上来）。 */
    private var renderSeq = 0

    /** 「全选」复选框。用户行单独勾选时要同步它的状态，见 [syncSelectAll]。 */
    private var selectAllBox: CheckBox? = null

    /** 数据装过一次就不再重复装。切 tab 回来**不重建视图**（重建会连用户缩放/平移过的视野一起重置），
     *  但数据可能被改过，那种情况要重拉 —— 见 [loadedVersion]。 */
    private var loaded = false

    /** [load] 那次拿到的是哪一版数据（[Store.dataVersion]）。写操作会让版本号变，切回地图页据此决定重拉。 */
    private var loadedVersion = -1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = Store(requireContext())

        // osmdroid 要一个 SharedPreferences 存缓存路径/瓦片配置。用独立的 "osmdroid" 文件：
        // 我们自己的 "city_footprint" 里放的是登录态，退出登录时会被逐个 remove，别混在一起
        Configuration.getInstance().load(
            requireContext(),
            requireContext().getSharedPreferences(OSM_PREFS, Context.MODE_PRIVATE)
        )
        // 瓦片服务要求 UA 能识别调用方；必须在请求瓦片之前设好，否则会因默认 UA 被拒
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID

        val b = binding
        b.map.setTileSource(AMAP_TILES)
        // 瓦片按屏幕 DPI 缩放。osmdroid 默认是 false —— 每块 256px 的瓦片只画 256 个**物理**像素，
        // 于是在高密度屏上瓦片被画成应有尺寸的 1/density：一行里挤进好几块、标注字号被一并缩小，
        // 看着就像「还没怎么放大就进了下一级」。开启后瓦片按 density 绘制，与网页 Leaflet（一块瓦片
        // 对应 256 CSS 像素）同一口径，标注字号才正常（代价：瓦片源本身只有 256px，边缘会略糊）。
        b.map.setTilesScaledToDpi(true)
        b.map.setMultiTouchControls(true)
        // 关掉 osmdroid 自带那对 +/- 缩放按钮：**6.0 起默认开启**，叠在底部中间，与网页版（Leaflet 只有
        // 左上角那颗）不一致，也会盖住底部城市明细卡。只关按钮，捏合缩放靠上面那行 setMultiTouchControls 照旧
        b.map.setBuiltInZoomControls(false)
        b.map.setMaxZoomLevel(18.0)
        b.map.controller.setZoom(4.0)
        // 先给一个能看见全国的视野（中国大致中心），用户再自己缩放
        b.map.controller.setCenter(GeoPoint(35.0, 105.0))

        b.legendHeader.setOnClickListener { toggleLegend() }
        b.cityCardClose.setOnClickListener { hideCityCard() }

        load()
    }

    override fun onResume() {
        super.onResume()
        // 隐藏着就别恢复：tab 是 hide/show，隐藏页仍是 RESUMED，Activity 每次 resume（比如从编辑页返回）
        // 都会连带把后台地图叫起来拉瓦片。切 tab 的显隐由下面的 onHiddenChanged 管
        if (!isHidden) _binding?.map?.onResume()
    }

    override fun onPause() {
        _binding?.map?.onPause()
        super.onPause()
    }

    /**
     * 切走时暂停地图、切回来恢复，并补一次数据。
     *
     * 暂停：光靠 onPause/onResume 不够，tab 是 add/hide/show 切换的，隐藏的 Fragment 仍是 RESUMED，
     * 不在这里停一下，地图会在后台白拉瓦片流量。
     *
     * 补数据：本页**没有下拉刷新**，[load] 又只在 onViewCreated 调一次，所以这里是唯一的自愈点 ——
     * 在足迹页增删改之后切回地图，若不在这里重拉，图例与城市会一直停在旧数据上，只能杀进程。
     * 重不重拉由 [load] 里的版本号判断，版本没变时它直接返回，不会每次切 tab 都打网络。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            _binding?.map?.onPause()
        } else {
            _binding?.map?.onResume()
            load()
        }
    }

    override fun onDestroyView() {
        // 必须 detach：MapView 内部有线程与瓦片缓存，不 detach 会泄漏（osmdroid 的硬性要求）
        _binding?.map?.onDetach()
        _binding = null
        super.onDestroyView()
    }

    // ────────────────────────────── 数据加载 ──────────────────────────────

    private fun load() {
        // 版本没变就直接返回。这样「写操作后切回地图」会自动刷新，又不会每次切 tab 都白拉一次
        if (loaded && loadedVersion == store.dataVersion) return
        val token = store.token
        if (token == null) {
            Session.expired(requireActivity())
            return
        }
        binding.progress.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.runIo({ VisitRepo.cities(token) }) { result ->
            binding.progress.visibility = View.GONE

            val data = result.getOrNull()
            if (data == null) {
                activity?.handleApiFailure(result.exceptionOrNull() ?: RuntimeException())
                return@runIo
            }
            loaded = true
            loadedVersion = store.dataVersion
            // 这是「数据被改过」才走到的分支（版本没变上面就返回了）：明细缓存与已经弹出的城市卡
            // 装的是改动前的旧行，卡里那几行不会自己更新，一并丢掉、收起来
            cityDetailCache.clear()
            hideCityCard()
            cities = data.cities
            binding.textEmpty.visibility = if (cities.isEmpty()) View.VISIBLE else View.GONE
            buildLegend()
            renderCities()
        }
    }

    // ────────────────────────────── 图例 ──────────────────────────────

    /**
     * 展开/收起图例的用户列表。默认**收起**（初值在 fragment_map.xml：legendScroll 为 gone、箭头为 ▸），
     * 与网页 docs/index.html 一致——先进地图看整体，要筛人再点标题行。
     * 判定看 legendScroll 当前可见性而非另存状态变量：唯一的改动入口就是这里，两边不会不同步。
     */
    private fun toggleLegend() {
        val b = binding
        val collapsing = b.legendScroll.visibility == View.VISIBLE
        b.legendScroll.visibility = if (collapsing) View.GONE else View.VISIBLE
        b.legendToggle.setText(if (collapsing) R.string.map_legend_toggle_collapsed else R.string.map_legend_toggle_expanded)
    }

    private fun buildLegend() {
        val b = binding
        val list = b.legendList
        list.removeAllViews()
        userInfo.clear()
        for (city in cities) for (person in city.people) userInfo[person.nickname] = person.color

        // 一座城市都没有（或都没人）就没得筛，图例整块不显示
        if (userInfo.isEmpty()) {
            b.legend.visibility = View.GONE
            return
        }
        b.legend.visibility = View.VISIBLE

        // 「全选」行：整行可点（复选框自己不吃点击，免得整行与复选框各触发一次）
        val allRow = legendRow(getString(R.string.map_legend_all), null)
        val allBox = allRow.getChildAt(0) as CheckBox
        allBox.isChecked = true
        selectAllBox = allBox
        allRow.setOnClickListener {
            val checked = !allBox.isChecked
            allBox.isChecked = checked
            userCheckBoxes().forEach { it.isChecked = checked }
            onFilterChanged()
        }
        list.addView(allRow)

        for ((name, color) in userInfo) {
            val row = legendRow(name, safeColor(color))
            val box = row.getChildAt(0) as CheckBox
            box.isChecked = true
            row.setOnClickListener {
                box.isChecked = !box.isChecked
                syncSelectAll()
                onFilterChanged()
            }
            list.addView(row)
        }
    }

    /**
     * 图例一行：复选框 + 颜色圆点 + 昵称。整行可点；昵称栏挂进复选框的 tag 里，
     * 供 [selectedUsers] 读回昵称（不去猜「第几个子 View 是文字」）。
     */
    private fun legendRow(name: String, color: Int?): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 纵向 1dp（网页 .legend .user 是 2px）：一行一个人是纯信息列表，行内留白别太厚
            setPadding(0, dp(1), 0, dp(1))
            isClickable = true
        }
        row.addView(CheckBox(ctx).apply {
            isClickable = false
            isFocusable = false
            tag = name
            // Material3 给复选框的 minWidth / minHeight 都是 48dp 触摸目标：
            // minHeight 会把行高顶到 48dp，minWidth 会在勾选框与圆点之间留出近 48dp 的空档。
            // 两个下限都钉掉，尺寸回到图标本身；整行仍可点，点击热区不受影响
            minimumHeight = 0
            minimumWidth = 0
        })
        if (color != null) {
            row.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(4) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            })
        }
        row.addView(TextView(ctx).apply {
            text = name
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(160)
            setPadding(dp(4), 0, 0, 0)
        })
        return row
    }

    /** 用户行的复选框（跳过 index 0 的「全选」行）。 */
    private fun userCheckBoxes(): List<CheckBox> =
        (1 until binding.legendList.childCount).mapNotNull { i ->
            (binding.legendList.getChildAt(i) as? LinearLayout)?.getChildAt(0) as? CheckBox
        }

    /** 用户行有变化时同步「全选」的勾选态（全勾上才算全选）。 */
    private fun syncSelectAll() {
        val boxes = userCheckBoxes()
        selectAllBox?.isChecked = boxes.isNotEmpty() && boxes.all { it.isChecked }
    }

    private fun selectedUsers(): Set<String> =
        userCheckBoxes().filter { it.isChecked }.mapNotNull { it.tag as? String }.toSet()

    private fun onFilterChanged() {
        // 筛选变了，已开的明细卡内容可能不再符合条件 —— 直接收起（与网页重绘图层时关掉弹窗一致）
        hideCityCard()
        renderCities()
    }

    // ────────────────────────────── 图层绘制 ──────────────────────────────

    private fun renderCities() {
        val seq = ++renderSeq
        binding.map.overlays.removeAll(cityOverlays)
        cityOverlays.clear()

        val selected = selectedUsers()
        val allChecked = selected.size == userInfo.size
        val sem = Semaphore(GEO_CONCURRENCY)
        val scope = viewLifecycleOwner.lifecycleScope
        // 先在主线程把 Context 取好：边界缓存（data/GeoCache）要在 IO 线程里用它读写
        val ctx = requireContext()

        for (city in cities) {
            val people = if (allChecked) city.people else city.people.filter { selected.contains(it.nickname) }
            if (people.isEmpty()) continue
            // 城市颜色取「最新一位勾选者」的颜色：people 由后端按 created_at 升序给出，末位 = 最新
            val color = safeColor(people.last().color)

            val adcode = city.adcode
            if (adcode == null) {
                drawDot(city, color) // 拿不到 adcode：这座城没有边界可画
                continue
            }
            val cached = geoCache[adcode]
            if (cached != null) {
                drawCity(city, cached, color)
                continue
            }
            scope.launch {
                // 并发上限：几十座城市同时开边界请求会把手机与后端一起压垮（网页同理，见 GEO_CONCURRENCY）
                val rings = sem.withPermit {
                    withContext(Dispatchers.IO) {
                        runCatching { GeoJson.polygons(VisitRepo.geoJson(ctx, adcode)) }.getOrDefault(emptyList())
                    }
                }
                // 先落缓存：即使这批渲染已被新筛选作废，也把结果留下来给下一次用
                geoCache[adcode] = rings
                if (seq != renderSeq) return@launch // 本次渲染已作废，别往图上画
                drawCity(city, rings, color)
            }
        }
        binding.map.invalidate()
    }

    /** 画一座城市：有点环就铺半透明多边形，否则退回圆点。 */
    private fun drawCity(city: MapCity, rings: List<List<GeoPoint>>, color: Int) {
        if (rings.isEmpty()) {
            drawDot(city, color)
            return
        }
        for (ring in rings) {
            val polygon = Polygon().apply {
                setPoints(ring)
                // 用 Paint 而不是 setFillColor/setStrokeColor 那几个：它们在 6.x 里已标记 @Deprecated，
                // 只是转发到 Paint（osmdroid 6.0.2 起 Fill/Outline 各是一支 Paint）。
                // 填充是同色 40% 透明、描边同色 —— 与网页 fillOpacity .4 + color 描边一致
                fillPaint.color = withAlpha(color, 0x66)
                outlinePaint.color = color
                outlinePaint.strokeWidth = 1.5f
                title = city.city
                setOnClickListener { p, _, _ ->
                    p.title?.let { showCity(it) }
                    true // 消费掉，不再走 osmdroid 默认那套
                }
            }
            cityOverlays.add(polygon)
            binding.map.overlays.add(polygon)
        }
        binding.map.invalidate()
    }

    /** 边界拿不到（无 adcode / 解析失败）时的回退：一颗按用户着色的圆点。 */
    private fun drawDot(city: MapCity, color: Int) {
        val marker = Marker(binding.map).apply {
            position = GeoPoint(city.lat, city.lng)
            title = city.city
            icon = dotIcon(color)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ ->
                showCity(city.city)
                true // 消费掉：不再弹 osmdroid 默认那个信息气泡（明细走底部卡片）
            }
        }
        cityOverlays.add(marker)
        binding.map.overlays.add(marker)
        binding.map.invalidate()
    }

    /** 图例上那颗圆点：白底 + 彩色实心圆，深一道浅一道的瓦片上都能看清（对齐网页 .dot 的白描边）。 */
    private fun dotIcon(color: Int): Drawable {
        val size = dp(DOT_SIZE_DP)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val r = size / 2f
        paint.color = Color.WHITE
        canvas.drawCircle(r, r, r, paint)
        paint.color = color
        canvas.drawCircle(r, r, r - dp(2), paint)
        return BitmapDrawable(resources, bitmap)
    }

    // ────────────────────────────── 城市明细卡 ──────────────────────────────

    /** 点城市 → 底部卡片列最近 10 条行程。明细按城市缓存，反复开关同一座城市不重复请求。 */
    private fun showCity(city: String) {
        val b = binding
        b.cityCard.visibility = View.VISIBLE
        b.cityCardTitle.text = city
        b.cityCardList.removeAllViews()

        cityDetailCache[city]?.let { fillCityCard(it); return }

        b.cityCardStatus.text = getString(R.string.map_city_loading)
        b.cityCardStatus.visibility = View.VISIBLE
        val token = store.token
        viewLifecycleOwner.lifecycleScope.runIo({ VisitRepo.cityVisits(city, token) }) { result ->
            val data = result.getOrNull()
            if (data == null) {
                b.cityCardStatus.text = getString(R.string.map_city_failed)
                b.cityCardStatus.visibility = View.VISIBLE
                return@runIo
            }
            cityDetailCache[city] = data.visits
            // 卡片可能已被关掉、或点去了别的城市：对不上就别把这份结果填进去
            if (b.cityCard.visibility != View.VISIBLE || b.cityCardTitle.text.toString() != city) return@runIo
            fillCityCard(data.visits)
        }
    }

    /** 明细卡内容：按当前图例勾选过滤，取最近 10 条（后端已按 created_at 倒序）。 */
    private fun fillCityCard(visits: List<CityVisit>) {
        val b = binding
        val selected = selectedUsers()
        val allChecked = selected.size == userInfo.size
        val filtered = if (allChecked) visits else visits.filter { selected.contains(it.nickname) }
        val shown = filtered.take(10)

        b.cityCardList.removeAllViews()
        if (shown.isEmpty()) {
            b.cityCardStatus.text = getString(R.string.map_city_empty)
            b.cityCardStatus.visibility = View.VISIBLE
            return
        }
        b.cityCardStatus.visibility = View.GONE
        shown.forEach { b.cityCardList.addView(cityRow(it)) }
        if (filtered.size > 10) b.cityCardList.addView(cityMoreRow())
    }

    /**
     * 一条行程：昵称（+私密锁）· 到访时间 · 备注**都在同一行**，且只占一行。
     * 与网页弹窗的 .popup-person（flex 一行、备注 ellipsis）同一形态：
     * 昵称与日期按内容取宽，备注吃掉剩下的宽度、超长省略。
     */
    private fun cityRow(visit: CityVisit): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(ctx).apply {
            // 私密锁跟在昵称后面、用同一颜色（与网页 `style="color:…">昵称 🔒` 一致）
            text = if (visit.isPrivate) "${visit.nickname} 🔒" else visit.nickname
            setTextColor(safeColor(visit.color))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        row.addView(TextView(ctx).apply {
            text = getString(R.string.visits_meta, VisitDate.display(visit.visitDate))
            // 与网页 .popup-person .meta 同档（tertiary），别用 secondary —— 比网页深一档
            setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
            textSize = 11f
            setPadding(dp(6), 0, 0, 0)
        })
        if (visit.note.isNotBlank()) {
            // 备注最长 100 字，同一行放不下就省略（weight=1：昵称/日期按内容占位，剩下的都归它）。
            // 与网页 .popup-person .note 的 overflow:hidden + ellipsis 同一口径；
            // 要看全文点开足迹页那条行程（详情弹窗不设 maxLines）
            row.addView(TextView(ctx).apply {
                text = visit.note
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                textSize = 12f
                setPadding(dp(6), 0, 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        return row
    }

    private fun cityMoreRow(): View = TextView(requireContext()).apply {
        text = getString(R.string.map_city_more)
        // 同网页 .popup-more（tertiary）
        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_tertiary))
        textSize = 11f
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun hideCityCard() {
        binding.cityCard.visibility = View.GONE
    }

    // ────────────────────────────── 小工具 ──────────────────────────────

    /** 用户颜色来自后端（形如 #4285F4）；万一是坏值就退回主色，别让一颗脏数据把地图画崩。 */
    private fun safeColor(value: String): Int =
        runCatching { Color.parseColor(value) }
            .getOrDefault(ContextCompat.getColor(requireContext(), R.color.accent))

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val OSM_PREFS = "osmdroid"

        /** 回退圆点的直径（dp）。 */
        const val DOT_SIZE_DP = 14

        /** 同时最多拉几条城市边界（与网页 GEO_CONCURRENCY 同值）。 */
        const val GEO_CONCURRENCY = 6

        val AMAP_TILES = AmapTileSource()
    }
}

/**
 * 高德免 Key 瓦片，URL 与网页 docs/index.html 的 Leaflet 图层完全一致（style=8 街道图）。
 *
 * 为什么不直接用 osmdroid 自带的 XYTileSource：它拼的是 `z/x/y.png` 那种路径式地址，
 * 套不进高德这种查询串 URL，所以覆写 [getTileURLString] 自己拼 —— 与 osmdroid 示例里
 * 接 Google/高德瓦片的做法一致。[getBaseUrl] 每次从数组里随机挑一个子域，等价于网页的 `{s}` 轮询。
 *
 * 四个子域**必须写成数组**：XYTileSource 的构造形参是 `String[]` 而不是可变参数（6.1.20 源码如此），
 * 拆成四个实参在 Kotlin 侧编译不过。
 */
private class AmapTileSource : XYTileSource(
    "Gaode",
    3, 18, 256, ".png",
    arrayOf(
        "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
    ),
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        getBaseUrl() + "&x=" + MapTileIndex.getX(pMapTileIndex) +
            "&y=" + MapTileIndex.getY(pMapTileIndex) +
            "&z=" + MapTileIndex.getZoom(pMapTileIndex)
}