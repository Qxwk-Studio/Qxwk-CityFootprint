package top.qxwkstudio.travel.ui.map

import top.qxwkstudio.travel.ui.common.*

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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
import top.qxwkstudio.travel.data.SettingsRepo
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.CheckinEntity
import top.qxwkstudio.travel.data.db.CheckinSource
import top.qxwkstudio.travel.databinding.FragmentMapBinding
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

    private var _binding: FragmentMapBinding? = null
    private val binding get() = requireNotNull(_binding) { "binding 在视图生命周期外被访问" }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private var visited: Set<String> = emptySet()
    private var seenVersion = -1

    /** 用户徽章色的 ARGB；0 表示读不到，退回主题色。 */
    private var badgeColor: Int = 0

    /** 省界 GeoJSON（assets/data/provinces.geojson），空串表示读失败。 */
    private var provincesJson: String = ""

    /** 定位请求在跑时忽略重复点击。 */
    private var locating = false
    private var styleLoaded = false

    /** 定位权限：用户在图上主动点准心时才请求（与打卡页同一套口径）。 */
    private val locatePermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) locate() else requireContext().notify(R.string.locate_no_permission, long = true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
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
            // 点亮色 = 用户在「我的」里选的徽章色（缺失时退主题色，见 refreshVisited）
            val badge = SettingsRepo.badgeColor(db)
            // 省界（0.1MB）一并读出来，建 style 后作为线图层压在城市面下面
            provincesJson = runCatching {
                requireContext().assets.open("data/provinces.geojson").bufferedReader().use { it.readText() }
            }.getOrDefault("")
            // 顺手把边界解析**放在 IO 线程**预热：4.6 MB 的 GeoJSON 解析要几百毫秒到几秒，
            // 如果在主线程（refreshVisited 里第一次取 locator）才做，首帧地图会卡住甚至 ANR。
            CityBoundaries.locator(requireContext())
            Triple(cities, path, badge)
        }) { result ->
            val (cities, path, badge) = result.getOrNull() ?: run {
                binding.textStatus.setText(R.string.map_failed)
                return@runIo
            }
            visited = cities
            badgeColor = runCatching { android.graphics.Color.parseColor(badge) }.getOrDefault(0)
            seenVersion = DataVersion.current
            if (map == null) setupMap(path) else refreshVisited()
        }
    }

    private fun setupMap(path: String) {
        mapView.getMapAsync { mapLibre ->
            map = mapLibre
            // 瓦片只到 z6，放大封顶 9；范围锁中国境内，别让用户滑到一片空白
            // 只做平移与缩放：旋转/倾斜手势关掉，避免用户误转成斜的地图
            mapLibre.uiSettings.isRotateGesturesEnabled = false
            mapLibre.uiSettings.isTiltGesturesEnabled = false
            mapLibre.setMinZoomPreference(2.4)
            mapLibre.setMaxZoomPreference(9.0)
            mapLibre.setLatLngBoundsForCameraTarget(LatLngBounds.from(53.6, 135.1, 3.8, 73.5))
            mapLibre.cameraPosition = CameraPosition.Builder()
                .target(LatLng(35.0, 105.0))
                .zoom(3.2)
                .build()
            // 关掉 MapLibre 自带的 (i) 署名按钮：版权信息统一放在「我的 → 开源项目与许可」，
            // 地图页本身不再压两块小字（左下角的 © OpenStreetMap 也一起移走了）。
            mapLibre.uiSettings.isAttributionEnabled = false
            // 左下角那个 MapLibre logo 也关掉：版权信息统一放在「我的 → 开源项目与许可」，
            // 地图页本身不再压任何署名控件。
            mapLibre.uiSettings.isLogoEnabled = false
            mapLibre.setStyle(Style.Builder().fromJson(styleJson(BasemapStore.url(path)))) { loaded ->
                styleLoaded = true
                style = loaded
                if (provincesJson.isNotEmpty()) {
                    loaded.addSource(GeoJsonSource(PROVINCE_SOURCE, provincesJson))
                    loaded.addLayer(
                        LineLayer(PROVINCE_LAYER, PROVINCE_SOURCE).withProperties(
                            org.maplibre.android.style.layers.PropertyFactory.lineColor(colorHex(R.color.map_province)),
                            org.maplibre.android.style.layers.PropertyFactory.lineWidth(0.8f),
                        )
                    )
                }
                // 定位点：用户色小圆点（默认空集合，点定位后更新坐标）
                val locationColor = if (badgeColor != 0) badgeColor else Palette.accent(requireContext())
                loaded.addSource(GeoJsonSource(MY_LOCATION_SOURCE, EMPTY_COLLECTION))
                loaded.addLayer(
                    CircleLayer(MY_LOCATION_LAYER, MY_LOCATION_SOURCE).withProperties(
                        org.maplibre.android.style.layers.PropertyFactory.circleColor(locationColor),
                        org.maplibre.android.style.layers.PropertyFactory.circleRadius(5f),
                        org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(1.5f),
                        org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
                    )
                )
                binding.cityLabels.attach(mapLibre)
                mapLibre.addOnCameraMoveListener { binding.cityLabels.invalidate() }
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
            background = colorHex(R.color.map_bg),
            land = colorHex(R.color.map_land),
            water = colorHex(R.color.map_water),
            road = colorHex(R.color.map_road),
        ),
    )

    /**
     * 把「去过的城市」灌进地图。
     *
     * **面与圆点二选一**：边界解析成功就只画面（面已经能表达「去过」，再撒一层圆点反而挡住底图）；
     * 只有面渲染不出来（资产缺失 / 解析失败）时才退回圆点 —— 圆点只需要城市表里的经纬度，
     * 永远画得出来，是「什么都看不见」的兜底，不是常规形态。
     *
     * 颜色取**用户自己设的徽章色**（我的页可选），不是软件主题色：地图上的「我」应该和头像圆底同色。
     */
    private fun refreshVisited() {
        val loaded = style ?: return
        val color = if (badgeColor != 0) badgeColor else Palette.accent(requireContext())
        val shapes = CityBoundaries.locator(requireContext()).shapesFor(visited)
        if (shapes.isNotEmpty()) {
            val json = shapesJson(shapes)
            val existing = loaded.getSourceAs<GeoJsonSource>(VISITED_SOURCE)
            if (existing != null) {
                existing.setGeoJson(json)
                // 用户可能在设置里换了徽章色：数据没变也要把图层颜色刷一遍
                applyVisitedColors(loaded, color)
                return
            }
            loaded.addSource(GeoJsonSource(VISITED_SOURCE, json))
            loaded.addLayer(
                FillLayer(VISITED_FILL, VISITED_SOURCE).withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.fillColor(color),
                    org.maplibre.android.style.layers.PropertyFactory.fillOpacity(0.35f),
                )
            )
            loaded.addLayer(
                LineLayer(VISITED_LINE, VISITED_SOURCE).withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.lineColor(color),
                    org.maplibre.android.style.layers.PropertyFactory.lineWidth(1.5f),
                )
            )
        }
        // 面渲染不出来时不画圆点：城市名（ui/CityLabelsView）就是唯一标识，
        // 文字与边界解析无关，永远画得出来。

        // 诊断数字只在失败时用：面为 0 说明边界没解析出来
        val shapeCount = CityBoundaries.locator(requireContext()).shapeCount
        // 面为 0 时把失败原因弹出来：解析失败是静默降级，没有 logcat 时这是唯一线索
        if (shapeCount == 0) {
            // 资产读取失败时优先报那条：解析错误只说得出「资产为空」，看不出根本原因
            val reason = CityBoundaries.lastAssetError
                ?: CityShapes.lastError
                ?: ("边界资产 " + CityBoundaries.lastAssetChars + " 字")
            requireContext().notify("边界: " + reason, long = true)
        }
        // 去过的城市名（只画本地点亮的那些，不是底图全量地名）
        _binding?.cityLabels?.submit(
            CityStore.all(requireContext())
                .filter { it.name in visited }
                .map { it.name to LatLng(it.lat, it.lng) },
        )
        // 加载完成：收掉布局里默认那行「正在载入地图」
        _binding?.textStatus?.visibility = View.GONE
    }
    /**
     * 把已解析好的城市面拼成 FeatureCollection。
     *
     * 手工拼字符串而不是走 GeoJSON 库：这里就两件事 —— 一串坐标与一层外壳，
     * 坐标为 5 位小数（约 1 米），几十万点也编得快。MultiPolygon 拆成多个同名 Feature，
     * 渲染效果一样（同一 city 的多块面都画）。
     */
    /** 把「去过的城市」相关图层的颜色同步成当前用户色（徽章色）。 */
    private fun applyVisitedColors(loaded: Style, color: Int) {
        loaded.getLayer(VISITED_FILL)?.let {
            (it as? FillLayer)?.setProperties(
                org.maplibre.android.style.layers.PropertyFactory.fillColor(color),
            )
        }
        loaded.getLayer(VISITED_LINE)?.let {
            (it as? LineLayer)?.setProperties(
                org.maplibre.android.style.layers.PropertyFactory.lineColor(color),
            )
        }
        loaded.getLayer(MY_LOCATION_LAYER)?.let {
            (it as? CircleLayer)?.setProperties(
                org.maplibre.android.style.layers.PropertyFactory.circleColor(color),
            )
        }
    }

    private fun shapesJson(shapes: List<CityShapes.Shape>): String {
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
        val name = features.firstOrNull()?.getStringProperty("name")
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
            // 首访：这座城市里时间最早的那条就是（首访只做角标，不做整卡描边）
            val firstId = checkins.minByOrNull { it.checkinAt }?.id
            if (checkins.isEmpty()) {
                b.cityList.addView(TextView(requireContext()).apply {
                    setText(R.string.map_empty_city)
                    setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                    textSize = 12f
                })
                return@runIo
            }
            checkins.forEach { checkin ->
                b.cityList.addView(checkinRow(checkin, checkin.id == firstId))
            }
        }
    }

    /**
     * 面板里的一行：编号徽章 + 时间 + 备注，下面再跟一行「首访 / 照片 / 同行人 / 星级」。
     */
    private fun checkinRow(checkin: CheckinEntity, firstVisit: Boolean): View {
        val ctx = requireContext()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val derived = checkin.source == CheckinSource.DERIVED
        val badgeColorRes = if (derived) R.color.stripe_derived else R.color.stripe_manual
        row.addView(TextView(ctx).apply {
            text = getString(R.string.trips_trip_seq, checkin.seqNo)
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(ctx, R.color.on_accent))
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(ContextCompat.getColor(ctx, badgeColorRes))
            }
        })
        row.addView(TextView(ctx).apply {
            text = Dates.ymd(checkin.checkinAt)
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(dp(8), 0, 0, 0)
        })
        if (firstVisit) {
            row.addView(TextView(ctx).apply {
                text = getString(R.string.first_visit)
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.first_visit))
                setBackgroundResource(R.drawable.bg_first_visit)
                setPadding(dp(7), dp(2), dp(7), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(6) }
            })
        }
        if (!checkin.note.isNullOrBlank()) {
            row.addView(TextView(ctx).apply {
                text = checkin.note
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
                setPadding(dp(8), 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        column.addView(row)

        val hasExtras = checkin.photoPath != null || checkin.companions != null || checkin.rating != null
        if (hasExtras) {
            val extras = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            if (checkin.photoPath != null) {
                val bitmap = PhotoStore.load(checkin.photoPath, maxPx = 256)
                if (bitmap != null) {
                    extras.addView(ImageView(ctx).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) }
                        setOnClickListener { showPhotoDialog(ctx, checkin.photoPath) }
                    })
                }
            }
            val info = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            if (checkin.companions != null) {
                info.addView(TextView(ctx).apply {
                    text = checkin.companions
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                })
            }
            if (checkin.rating != null) {
                val stars = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                for (star in 1..5) {
                    stars.addView(ImageView(ctx).apply {
                        setImageResource(if (checkin.rating >= star) R.drawable.ic_star else R.drawable.ic_star_outline)
                        imageTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(ctx, R.color.first_visit),
                        )
                        layoutParams = LinearLayout.LayoutParams(dp(13), dp(13))
                    })
                }
                info.addView(stars)
            }
            extras.addView(info)
            column.addView(extras)
        }
        return column
    }

    /** 定位：判城成功就把镜头挪过去（判不到就让用户手选，与打卡页同一套）。 */
    private fun locate() {
        if (locating) return
        if (!Locate.granted(requireContext())) {
            locatePermission.launch(Locate.PERMISSION)
            return
        }
        locating = true
        _binding?.btnLocate?.isEnabled = false
        Locate.current(requireContext()) { location ->
            locating = false
            _binding?.btnLocate?.isEnabled = true
            if (location == null) {
                requireContext().notify(R.string.locate_failed, long = true)
                return@current
            }
            val found = CityBoundaries.locator(requireContext())
                .locate(location.latitude, location.longitude)
            val target = found?.city
            val lat = target?.lat ?: location.latitude
            val lng = target?.lng ?: location.longitude
            // 用户位置的小圆点（实际坐标，不是判城后的城市中心）
            style?.getSourceAs<GeoJsonSource>(MY_LOCATION_SOURCE)?.setGeoJson(
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[${location.longitude},${location.latitude}]}}""",
            )
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
        const val VISITED_FILL = "visited-fill"
        const val VISITED_LINE = "visited-line"
        const val PROVINCE_SOURCE = "provinces"
        const val PROVINCE_LAYER = "provinces-line"
        const val MY_LOCATION_SOURCE = "my-location"
        const val MY_LOCATION_LAYER = "my-location-dot"
    }
}
