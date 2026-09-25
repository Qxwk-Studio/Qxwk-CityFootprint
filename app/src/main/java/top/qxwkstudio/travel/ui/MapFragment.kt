package top.qxwkstudio.travel.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import top.qxwkstudio.travel.BuildConfig
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.VisitRepo
import top.qxwkstudio.travel.databinding.FragmentMapBinding
import top.qxwkstudio.travel.logic.CitySearch

/**
 * 「地图」：把自己去过的城市打在 OSM 地图上（osmdroid），点标记看那座城市的边界。
 *
 * 选型理由（与 README 同一份）：osmdroid 是纯 Android View 的 OSM 地图，**不需要 API key**，
 * 与「界面和地图都原生、不引 WebView」的要求一致。被否掉的方案：
 *  - Google Maps SDK：要申请 API key 并绑包名/签名，个人项目多一处密钥管理；
 *  - WebView 套网页版地图：用户明确不要 WebView。
 *
 * 边界是「附加信息」，所以整条链路都是**可降级**的：拿不到 adcode、或者 GeoJSON 解析失败，
 * 都只提示一句，标记照旧留着（不把已经画好的东西清掉）。
 */
class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store

    private val markerOverlays = mutableListOf<Marker>()
    private val boundaryOverlays = mutableListOf<Overlay>()

    /** 只装一次；切 tab 回来不重装（重装会把用户缩放/平移过的视野也重置掉）。 */
    private var loaded = false

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
        // OSM 的瓦片服务要求 UA 能识别调用方；必须在请求瓦片之前设好，否则会因默认 UA 被拒
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID

        val b = binding
        b.map.setTileSource(TileSourceFactory.MAPNIK)
        b.map.setMultiTouchControls(true)
        b.map.controller.setZoom(4.0)
        // 先给一个能看见全国的视野（中国大致中心），用户再自己缩放
        b.map.controller.setCenter(GeoPoint(35.0, 105.0))

        load()
    }

    override fun onResume() {
        super.onResume()
        _binding?.map?.onResume()
    }

    override fun onPause() {
        _binding?.map?.onPause()
        super.onPause()
    }

    /**
     * 切走时暂停地图、切回来恢复。
     * 光靠 onPause/onResume 不够：tab 是 add/hide/show 切换的，隐藏的 Fragment 仍是 RESUMED，
     * 不在这里停一下，地图会在后台白拉瓦片流量。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) _binding?.map?.onPause() else _binding?.map?.onResume()
    }

    override fun onDestroyView() {
        // 必须 detach：MapView 内部有线程与瓦片缓存，不 detach 会泄漏（osmdroid 的硬性要求）
        _binding?.map?.onDetach()
        _binding = null
        super.onDestroyView()
    }

    private fun load() {
        if (loaded) return
        val token = store.token
        if (token == null) {
            Session.expired(requireActivity())
            return
        }
        val b = _binding ?: return
        b.progress.visibility = View.VISIBLE

        Async.run({ VisitRepo.myVisits(token) }) { result ->
            val bd = _binding ?: return@run
            bd.progress.visibility = View.GONE

            val visits = result.getOrNull()
            if (visits == null) {
                activity?.handleApiFailure(result.exceptionOrNull() ?: RuntimeException())
                return@run
            }
            loaded = true
            bd.textEmpty.visibility = if (visits.isEmpty()) View.VISIBLE else View.GONE

            bd.map.overlays.removeAll(markerOverlays)
            markerOverlays.clear()
            for (visit in visits) {
                val marker = Marker(bd.map).apply {
                    position = GeoPoint(visit.lat, visit.lng)
                    title = visit.city
                    // 默认那个图钉在 OSM 底图上几乎看不见，换成我们自己的脚印图标
                    icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ ->
                        showBoundary(visit.city)
                        true // 消费掉：不再弹 osmdroid 默认那个信息气泡（边界已经说明了一切）
                    }
                }
                markerOverlays.add(marker)
                bd.map.overlays.add(marker)
            }
            bd.map.invalidate()
        }
    }

    /** 点标记 → 取该城市的边界。每次点新的先清掉上一次的：同一屏只画一座城市（需求就是「只画被点中的」）。 */
    private fun showBoundary(cityName: String) {
        val b = _binding ?: return
        clearBoundary()

        // adcode 来自本机城市表（与网页版同源）。县级市在源数据里没有 adcode → 拿不到边界，降级
        val adcode = CitySearch.findByName(CityStore.all(requireContext()), cityName)?.adcode
        if (adcode == null) {
            showBoundaryText(getString(R.string.map_no_boundary, cityName))
            return
        }

        showBoundaryText(getString(R.string.map_loading))
        Async.run({ VisitRepo.geoJson(adcode) }) { result ->
            val bd = _binding ?: return@run
            val json = result.getOrNull()
            if (json == null) {
                // 边界拉不到时**不清标记**：标记是主信息，边界只是补充
                bd.textBoundary.text = getString(R.string.map_boundary_failed)
                return@run
            }
            val polygons = GeoJson.polygons(json)
            if (polygons.isEmpty()) {
                bd.textBoundary.text = getString(R.string.map_no_boundary, cityName)
                return@run
            }
            for (ring in polygons) {
                val polygon = Polygon().apply {
                    setPoints(ring)
                    // 用 Paint 而不是 setFillColor/setStrokeColor 那几个：它们在 6.x 里已标记 @Deprecated，
                    // 只是转发到 Paint（osmdroid 6.0.2 起 Fill/Outline 各是一支 Paint）
                    fillPaint.color = Color.parseColor("#334F46E5")
                    outlinePaint.color = Color.parseColor("#4F46E5")
                    outlinePaint.strokeWidth = 4f
                    title = cityName
                }
                boundaryOverlays.add(polygon)
                bd.map.overlays.add(polygon)
            }
            bd.map.invalidate()
            bd.textBoundary.visibility = View.GONE
        }
    }

    private fun showBoundaryText(text: String) {
        val b = _binding ?: return
        b.textBoundary.text = text
        b.textBoundary.visibility = View.VISIBLE
    }

    private fun clearBoundary() {
        val b = _binding ?: return
        b.map.overlays.removeAll(boundaryOverlays)
        boundaryOverlays.clear()
        b.map.invalidate()
    }

    private companion object {
        const val OSM_PREFS = "osmdroid"
    }
}