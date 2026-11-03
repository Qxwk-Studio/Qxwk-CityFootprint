package top.qxwkstudio.travel.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.sources.GeoJsonSource
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.BasemapStore
import top.qxwkstudio.travel.data.CheckinRepo
import top.qxwkstudio.travel.data.CityBoundaries
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.DataVersion
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.databinding.FragmentMap2Binding
import top.qxwkstudio.travel.logic.CityShapes
import top.qxwkstudio.travel.logic.Dates
import top.qxwkstudio.travel.logic.MapStyle

/**
 * 地图页（2.0）：MapLibre 画离线底图，叠加「去过的城市」面；点城市弹只读面板。
 *
 * 三层东西：
 *  1. **底图**：`assets/basemap/cn-z0-6.pmtiles`（Protomaps，中国区 z0–6）。
 *     MapLibre 的 PMTiles 读取在原生层，只认 `pmtiles://file://…`，所以首次进来要先把资产拷到
 *     filesDir（见 [BasemapStore]）。底图只到 z6，再放大是空的 —— 叠加层不受影响。
 *  2. **去过的城市**：从判定用的那份边界（[CityBoundaries]）里挑出名字命中的面，拼成 GeoJSON
 *     交给 MapLibre。不重新解析一遍 4.6 MB 的资产文件。
 *  3. **面板**：点城市 → 该城市的打卡列表（只读），数据来自本地库。
 *
 * 与 1.x 的差别：没有「所有用户的图例筛选」（单机只有一个用户），没有在线瓦片。
 */
class MapFragment : Fragment() {

    private var _binding: FragmentMap2Binding? = null
    private val binding get() = _binding!!

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private var visited: Set<String> = emptySet()
    private var seenVersion = -1
    private var styleLoaded = false

    /** 定位权限：用户在图上主动点准心时才请求（与打卡页同一套口径）。 */
    private val locatePermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) locate() else requireContext().notify(R.string.locate_no_permission, long = true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMap2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mapView = binding.mapView
        mapView.onCreate(savedInstanceState)
        binding.btnClose.setOnClickListener { binding.cityCard.visibility = View.GONE }
        binding.btnLocate.setOnClickListener { locate() }
        load()
    }

    // MapView 的生命周期必须与页面同步；隐藏的 tab 也要停一下，否则后台白拉底图数据
    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) mapView.onResume()
        if (seenVersion != DataVersion.current) load()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroyView() {
        mapView.onDestroy()
        _binding = null
        super.onDestroyView()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) mapView.onPause() else {
            mapView.onResume()
            load()
        }
    }

    // ────────────────────────── 数据 ──────────────────────────

    /**
     * 读「去过的城市」与底图文件，都在 IO 上做（拷 5.8 MB 资产 + 查库）。
     * 拿到之后才去碰地图：MapLibre 的 style 必须在主线程设。
     */
    private fun load() {
        viewLifecycleOwner.lifecycleScope.runIo({
            val db = AppDatabase.get(requireContext())
            val cities = db.checkins().distinctCities().toSet()
            val path = BasemapStore.ensure(requireContext())
            // 顺手把边界解析**放在 IO 线程**预热：4.6 MB 的 GeoJSON 解析要几百毫秒到几秒，
            // 如果在主线程（refreshVisited 里第一次取 locator）才做，首帧地图会卡住甚至 ANR。
            CityBoundaries.locator(requireContext())
            cities to path
        }) { result ->
            val (cities, path) = result.getOrNull() ?: run {
                binding.textStatus.setText(R.string.map_failed)
                return@runIo
            }
            visited = cities
            seenVersion = DataVersion.current
            if (map == null) setupMap(path) else refreshVisited()
        }
    }

    private fun setupMap(path: String) {
        mapView.getMapAsync { mapLibre ->
            map = mapLibre
            mapLibre.cameraPosition = CameraPosition.Builder()
                .target(LatLng(35.0, 105.0))
                .zoom(3.2)
                .build()
            mapLibre.setStyle(Style.Builder().fromJson(styleJson(BasemapStore.url(path)))) { loaded ->
                styleLoaded = true
                style = loaded
                refreshVisited()
                mapLibre.addOnMapClickListener { latLng -> onClickMap(latLng) }
            }
            // 8 秒还没进 style 回调：把失败摆在界面上（没有 logcat 时这是唯一的线索）。
            // 注意 style 载入失败时 MapLibre 不会回调，所以只能靠超时判。
            _binding?.textStatus?.postDelayed({
                if (!styleLoaded) {
                    _binding?.textStatus?.setText(R.string.map_failed)
                    _binding?.textStatus?.visibility = View.VISIBLE
                }
            }, STYLE_TIMEOUT_MS)
        }
    }

    /** style 由 logic/MapStyle 生成（纯函数、有单测），这里只负责把主题色喂进去。 */
    private fun styleJson(pmtilesUrl: String): String = MapStyle.json(
        pmtilesUrl,
        MapStyle.Colors(
            background = colorHex(R.color.bg),
            land = colorHex(R.color.muted),
            water = colorHex(R.color.accent_light),
            road = colorHex(R.color.border),
        ),
    )

    /** 把「去过的城市」灌进地图（首次建源+图层，之后只换数据）。 */
    private fun refreshVisited() {
        val loaded = style ?: return
        val json = visitedGeoJson(visited)
        val existing = loaded.getSourceAs<GeoJsonSource>(VISITED_SOURCE)
        if (existing != null) {
            existing.setGeoJson(json)
            return
        }
        loaded.addSource(GeoJsonSource(VISITED_SOURCE, json))
        val accent = Palette.accent(requireContext())
        loaded.addLayer(
            FillLayer(VISITED_FILL, VISITED_SOURCE).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.fillColor(accent),
                org.maplibre.android.style.layers.PropertyFactory.fillOpacity(0.35f),
            )
        )
        loaded.addLayer(
            LineLayer(VISITED_LINE, VISITED_SOURCE).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.lineColor(accent),
                org.maplibre.android.style.layers.PropertyFactory.lineWidth(1.5f),
            )
        )
        // 圆点层：**不依赖边界解析**。边界文件 4.6 MB，解析失败在手机上可能是静默的（OOM 被
        // runCatching 吞掉），那时多边形一条都画不出来、看起来就像「什么都没渲染」。
        // 圆点只需要城市表里的经纬度，永远画得出来 —— 与 2.0 设计文档「无边界城市画圆点」也一致。
        loaded.addSource(GeoJsonSource(POINTS_SOURCE, visitedPointsJson(visited)))
        loaded.addLayer(
            CircleLayer(POINTS_SOURCE_LAYER, POINTS_SOURCE).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.circleColor(accent),
                org.maplibre.android.style.layers.PropertyFactory.circleRadius(4f),
                org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(1.5f),
                org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
            )
        )

        // 状态里带诊断数字：面数（边界解析结果）与城市数（本地库）。真机上如果没有 logcat，
        // 这一行就是唯一线索 —— 面 0 说明边界没解析出来，城市 0 说明数据没读到。
        val shapeCount = CityBoundaries.locator(requireContext()).shapeCount
        _binding?.textStatus?.text = getString(
            R.string.map_loaded_count,
            shapeCount,
            visited.size,
        )
        // 面为 0 时把失败原因弹出来：解析失败是静默降级，没有 logcat 时这是唯一线索
        if (shapeCount == 0) {
            // 资产读取失败时优先报那条：解析错误只说得出「资产为空」，看不出根本原因
            val reason = CityBoundaries.lastAssetError
                ?: CityShapes.lastError
                ?: ("边界资产 " + CityBoundaries.lastAssetChars + " 字")
            requireContext().notify("边界: " + reason, long = true)
        }
        _binding?.textStatus?.visibility = View.VISIBLE
        _binding?.textStatus?.postDelayed({ _binding?.textStatus?.visibility = View.GONE }, 8000)
    }

    /** 去过的城市的经纬度点（来自城市表，不需要解析边界）。 */
    private fun visitedPointsJson(cities: Set<String>): String {
        if (cities.isEmpty()) return EMPTY_COLLECTION
        val table = CityStore.all(requireContext()).associateBy { it.name }
        val sb = StringBuilder(1 shl 12)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (name in cities) {
            val city = table[name] ?: continue
            if (!first) sb.append(',')
            first = false
            sb.append("{\"type\":\"Feature\",\"properties\":{\"name\":\"")
                .append(name)
                .append("\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            sb.append(city.lng).append(',').append(city.lat)
            sb.append("]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * 只把去过的城市拼成 FeatureCollection。
     *
     * 手工拼字符串而不是走 geopandas/GeoJSON 库：这里就两件事 —— 一串坐标与一层外壳，
     * 坐标为 5 位小数（约 1 米），几十万点也编得快。MultiPolygon 拆成多个同名 Feature，
     * 渲染效果一样（同一 city 的多块面都画）。
     */
    private fun visitedGeoJson(cities: Set<String>): String {
        if (cities.isEmpty()) return EMPTY_COLLECTION
        val shapes = CityBoundaries.locator(requireContext()).shapesFor(cities)
        val sb = StringBuilder(1 shl 18)
        sb.append("""{"type":"FeatureCollection","features":[""")
        shapes.forEachIndexed { index, shape ->
            if (index > 0) sb.append(',')
            sb.append("""{"type":"Feature","properties":{"name":"""").append(shape.name).append("\"},")
            sb.append("\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[")
            appendRing(sb, shape.outer)
            shape.holes.forEach { hole -> sb.append(','); appendRing(sb, hole) }
            sb.append("]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun appendRing(sb: StringBuilder, ring: DoubleArray) {
        sb.append('[')
        var i = 0
        while (i < ring.size) {
            if (i > 0) sb.append(',')
            sb.append('[')
            appendCoord(sb, ring[i])       // 经度
            sb.append(',')
            appendCoord(sb, ring[i + 1])   // 纬度
            sb.append(']')
            i += 2
        }
        sb.append(']')
    }

    /** 5 位小数（约 1 米）：再多对底图精度没有意义，只会让 JSON 更大。 */
    private fun appendCoord(sb: StringBuilder, value: Double) {
        val scaled = Math.round(value * 100_000.0)
        if (scaled < 0) sb.append('-')
        val abs = Math.abs(scaled)
        sb.append(abs / 100_000)
        val fraction = abs % 100_000
        if (fraction != 0L) {
            sb.append('.')
            sb.append(fraction.toString().padStart(5, '0').trimEnd('0'))
        }
    }

    // ────────────────────────── 交互 ──────────────────────────

    /** 点地图：命中城市面就弹面板；点空白收起。 */
    private fun onClickMap(latLng: LatLng): Boolean {
        val mapLibre = map ?: return false
        val point = mapLibre.projection.toScreenLocation(latLng)
        val features = mapLibre.queryRenderedFeatures(point, VISITED_FILL)
        val name = features?.firstOrNull()?.getStringProperty("name")
        if (name.isNullOrBlank()) {
            binding.cityCard.visibility = View.GONE
            return false
        }
        showCity(name)
        return true
    }

    private fun showCity(city: String) {
        val b = binding
        b.cityCard.visibility = View.VISIBLE
        b.textCity.text = city
        b.textCount.setText(R.string.map_loading)
        b.cityList.removeAllViews()

        viewLifecycleOwner.lifecycleScope.runIo({
            CheckinRepo.listForCity(AppDatabase.get(requireContext()), city)
        }) { result ->
            val checkins = result.getOrNull() ?: return@runIo
            // 面板可能已经被关掉、或点去了别的城市：对不上就不填
            if (b.cityCard.visibility != View.VISIBLE || b.textCity.text.toString() != city) return@runIo
            b.textCount.text = getString(R.string.map_city_count, checkins.size)
            b.cityList.removeAllViews()
            if (checkins.isEmpty()) {
                b.cityList.addView(TextView(requireContext()).apply {
                    setText(R.string.map_empty_city)
                    setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                    textSize = 12f
                })
                return@runIo
            }
            checkins.forEach { checkin ->
                b.cityList.addView(TextView(requireContext()).apply {
                    text = Dates.ymd(checkin.checkinAt) + if (checkin.note.isNullOrBlank()) "" else "  ·  ${checkin.note}"
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 12f
                    setPadding(0, dp(3), 0, dp(3))
                })
            }
        }
    }

    /** 定位：判城成功就把镜头挪过去（判不到就让用户手选，与打卡页同一套）。 */
    private fun locate() {
        if (!Locate.granted(requireContext())) {
            locatePermission.launch(Locate.PERMISSION)
            return
        }
        Locate.current(requireContext()) { location ->
            if (location == null) {
                requireContext().notify(R.string.locate_failed, long = true)
                return@current
            }
            val found = CityBoundaries.locator(requireContext())
                .locate(location.latitude, location.longitude)
            val target = found?.city
            val lat = target?.lat ?: location.latitude
            val lng = target?.lng ?: location.longitude
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 8.0))
            if (target == null) requireContext().notify(R.string.locate_no_city, long = true)
        }
    }

    private fun colorHex(resId: Int): String {
        val color = ContextCompat.getColor(requireContext(), resId)
        return "#%02X%02X%02X".format(
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val STYLE_TIMEOUT_MS = 8_000L
        const val EMPTY_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        const val VISITED_SOURCE = "visited"
        const val POINTS_SOURCE = "visited-points"
        const val POINTS_SOURCE_LAYER = "visited-points-layer"
        const val VISITED_FILL = "visited-fill"
        const val VISITED_LINE = "visited-line"
    }
}
