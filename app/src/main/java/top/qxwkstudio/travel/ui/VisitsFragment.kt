package top.qxwkstudio.travel.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.VisitRepo
import top.qxwkstudio.travel.databinding.DialogVisitDetailBinding
import top.qxwkstudio.travel.databinding.FragmentVisitsBinding
import top.qxwkstudio.travel.logic.Visit
import top.qxwkstudio.travel.logic.VisitDate
import top.qxwkstudio.travel.logic.topTransportLabel
import top.qxwkstudio.travel.logic.transportLabels

/**
 * 「主页」：网页端「足迹管理页」的内容 —— 顶部足迹统计概览 + 行程列表（下拉刷新）。
 * 接口：GET /api/my-visits（后端按 created_at DESC 排好序）。
 * 本页只「读」和给编辑入口：点条目看只读详情弹窗，铅笔进 VisitEditActivity，
 * 增删改三个请求都在那一页里发（POST / PUT / DELETE）。
 */
class VisitsFragment : Fragment() {

    private var _binding: FragmentVisitsBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store
    private lateinit var adapter: VisitAdapter
    private lateinit var headerAdapter: VisitsHeaderAdapter

    /**
     * 新增/编辑页回来就重新拉一次列表。
     * 为什么需要它、而不是靠 onResume：四个 tab 用 add/hide/show 切换，
     * 本页在别的 tab 显示时**仍是 RESUMED**，从编辑页回来时也不会重新 resume ——
     * 不注册这个回调就会看到「明明保存了，列表还是旧的」。
     */
    private val editResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) load()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVisitsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = Store(requireContext())

        adapter = VisitAdapter(
            onView = { visit -> showDetail(visit) },
            onEdit = { visit -> editResult.launch(VisitEditActivity.intent(requireContext(), visit)) })
        // 页头也是列表的一项（第 0 项），这样整页共用一个滚动容器（见 fragment_visits.xml / VisitsHeaderAdapter）
        headerAdapter = VisitsHeaderAdapter { editResult.launch(VisitEditActivity.intent(requireContext(), null)) }

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = ConcatAdapter(headerAdapter, adapter)
        binding.swipe.setColorSchemeResources(R.color.accent)
        binding.swipe.setOnRefreshListener { load(force = true) }
        // 列表外垫了层 FrameLayout（放空态提示），SwipeRefreshLayout 默认会去问它「滚过没有」，
        // 它永远答没有 —— 于是滑到中间也能下拉。这里把判断交回真正的列表
        binding.swipe.setOnChildScrollUpCallback { _, _ -> binding.list.canScrollVertically(-1) }

        load()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** [force] = true 跳过一天缓存强制走网络（下拉刷新用）。 */
    private fun load(force: Boolean = false) {
        val token = store.token
        if (token == null) {
            // 正常进不来（MainActivity 已判定登录态）；真发生就按统一流程回登录页，而不是空列表
            Session.expired(requireActivity())
            return
        }
        binding.swipe.isRefreshing = true
        binding.textEmpty.visibility = View.GONE

        // 挂在 viewLifecycleOwner 上：页面销毁时协程自动取消，
        // 所以回调里不用再判 binding 是否还在（见 ui/Coroutines 的说明）
        viewLifecycleOwner.lifecycleScope.runIo({ VisitRepo.myVisits(requireContext(), token, force) }) { result ->
            // 下拉刷新转圈必须停：失败时不停，用户会以为还在加载
            binding.swipe.isRefreshing = false

            val data = result.getOrNull()
            if (data == null) {
                // 401 在这条链路里统一处理（清 token 回登录页 + 只弹一次），其余弹后端文案
                activity?.handleApiFailure(result.exceptionOrNull() ?: RuntimeException())
                return@runIo
            }
            val visits = data.visits
            adapter.submit(visits)
            binding.textEmpty.visibility = if (visits.isEmpty()) View.VISIBLE else View.GONE
            renderOverview(visits)
        }
    }

    /**
     * 概览：时间跨度 / 去过城市 / 足迹总数 / 覆盖省份 / 最常用出行。
     * 与网页端 docs/visits.html 的 updateVisitStats 同口径：
     *  - 城市去重（同一座城打卡多次只算一座）；
     *  - 省份按城市名在本机 assets/cities.json 里查，查不到记「未知」（仍占一个名额，跟网页端一致）；
     *  - 日期直接按字符串排序取首尾（数据形如 2024 或 2024-08，字典序即时间序）；
     *  - 最常用出行按「有多少条足迹用过它」投票，并列时取枚举里靠前的那个（见 logic/Transport）。
     */
    private fun renderOverview(visits: List<Visit>) {
        val cityNames = visits.map { it.city }.distinct()

        val allCities = CityStore.all(requireContext())
        val unknown = getString(R.string.visits_overview_unknown_province)
        val provinces = cityNames.map { name ->
            allCities.firstOrNull { it.name == name }?.province?.takeIf { it.isNotBlank() } ?: unknown
        }.toSet()

        val dates = visits.mapNotNull { it.visitDate }.filter { it.isNotBlank() }.sorted()

        headerAdapter.submit(
            VisitsOverview(
                range = if (dates.isEmpty()) "—" else "${dates.first()} → ${dates.last()}",
                cities = cityNames.size.toString(),
                visits = visits.size.toString(),
                provinces = provinces.size.toString(),
                transport = topTransportLabel(visits) ?: "—",
            )
        )
    }

    /**
     * 「查看」弹窗：列表里备注只占一行、多的用省略号收住，完整内容在这里看。
     * 刻意做成**只读**：要改走右侧那颗铅笔进编辑页（删除也在编辑页里），
     * 免得一个弹窗既当详情又当表单，改起来还得判断「填了没」。
     */
    private fun showDetail(visit: Visit) {
        val d = DialogVisitDetailBinding.inflate(layoutInflater)
        d.textCity.text = visit.city
        d.textPrivate.visibility = if (visit.isPrivate) View.VISIBLE else View.GONE
        d.textDate.text = VisitDate.display(visit.visitDate)

        // 空的行整行隐藏（含它的小标签）：出行方式没填、备注没写都是常态，
        // 留一个空标题比少一行更难看
        val transports = transportLabels(visit.transport)
        d.rowTransport.visibility = if (transports.isEmpty()) View.GONE else View.VISIBLE
        // 多个方式拼成「甲 / 乙」，与列表里那排徽章的表达一致（弹窗里不再摆可点的 chip）
        d.textTransport.text = transports.joinToString(" / ")

        d.rowNote.visibility = if (visit.note.isBlank()) View.GONE else View.VISIBLE
        d.textNote.text = visit.note

        AlertDialog.Builder(requireContext())
            .setView(d.root)
            .setPositiveButton(R.string.visits_detail_close, null)
            .show()
    }
}