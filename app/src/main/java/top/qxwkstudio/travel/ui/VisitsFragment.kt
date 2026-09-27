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
import androidx.recyclerview.widget.LinearLayoutManager
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.VisitRepo
import top.qxwkstudio.travel.databinding.FragmentVisitsBinding
import top.qxwkstudio.travel.logic.Visit

/**
 * 「主页」：网页端「足迹管理页」的内容 —— 顶部足迹统计概览 + 行程列表（下拉刷新 + 新增/编辑/删除）。
 * 接口：GET /api/my-visits（后端按 created_at DESC 排好序），增删改分别 POST / PUT / DELETE。
 */
class VisitsFragment : Fragment() {

    private var _binding: FragmentVisitsBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store
    private lateinit var adapter: VisitAdapter

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

        adapter = VisitAdapter(onEdit = { visit -> editResult.launch(VisitEditActivity.intent(requireContext(), visit)) },
            onLongPress = { visit -> confirmDelete(visit) })

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.swipe.setOnRefreshListener { load() }
        binding.btnAdd.setOnClickListener { editResult.launch(VisitEditActivity.intent(requireContext(), null)) }

        load()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun load() {
        val token = store.token
        if (token == null) {
            // 正常进不来（MainActivity 已判定登录态）；真发生就按统一流程回登录页，而不是空列表
            Session.expired(requireActivity())
            return
        }
        binding.progress.visibility = View.VISIBLE
        binding.textEmpty.visibility = View.GONE

        // 挂在 viewLifecycleOwner 上：页面销毁时协程自动取消，
        // 所以回调里不用再判 binding 是否还在（见 ui/Coroutines 的说明）
        viewLifecycleOwner.lifecycleScope.runIo({ VisitRepo.myVisits(token) }) { result ->
            binding.progress.visibility = View.GONE
            // 下拉刷新转圈必须停：失败时不停，用户会以为还在加载
            binding.swipe.isRefreshing = false

            val visits = result.getOrNull()
            if (visits == null) {
                // 401 在这条链路里统一处理（清 token 回登录页 + 只弹一次），其余弹后端文案
                activity?.handleApiFailure(result.exceptionOrNull() ?: RuntimeException())
                return@runIo
            }
            adapter.submit(visits)
            binding.textEmpty.visibility = if (visits.isEmpty()) View.VISIBLE else View.GONE
            renderOverview(visits)
        }
    }

    /**
     * 概览卡：去过城市 / 足迹总数 / 覆盖省份 / 最早·最近。
     * 与网页端 docs/visits.html 的 updateVisitStats 同口径：
     *  - 城市去重（同一座城打卡多次只算一座）；
     *  - 省份按城市名在本机 assets/cities.json 里查，查不到记「未知」（仍占一个名额，跟网页端一致）；
     *  - 日期直接按字符串排序取首尾（数据形如 2024 或 2024-08，字典序即时间序）。
     */
    private fun renderOverview(visits: List<Visit>) {
        val cityNames = visits.map { it.city }.distinct()
        binding.valueCities.text = cityNames.size.toString()
        binding.valueVisits.text = visits.size.toString()

        val allCities = CityStore.all(requireContext())
        val unknown = getString(R.string.visits_overview_unknown_province)
        val provinces = cityNames.map { name ->
            allCities.firstOrNull { it.name == name }?.province?.takeIf { it.isNotBlank() } ?: unknown
        }.toSet()
        binding.valueProvinces.text = provinces.size.toString()

        val dates = visits.mapNotNull { it.visitDate }.filter { it.isNotBlank() }.sorted()
        binding.valueRange.text = if (dates.isEmpty()) "—" else "${dates.first()} → ${dates.last()}"

        // 标题行右侧那条「共 N 条，最近更新 YYYY-MM」：没有带日期的足迹时就只报条数
        binding.textListSubtitle.text = if (dates.isEmpty()) {
            getString(R.string.visits_list_subtitle_plain, visits.size)
        } else {
            getString(R.string.visits_list_subtitle, visits.size, dates.last())
        }
    }

    /** 删除要二次确认：点错了没有回收站。文案里带上城市名，让人看清删的是哪一条。 */
    private fun confirmDelete(visit: Visit) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.visits_delete_confirm_title)
            .setMessage(getString(R.string.visits_delete_confirm_message, visit.city))
            .setPositiveButton(R.string.common_delete) { _, _ -> delete(visit) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun delete(visit: Visit) {
        val token = store.token ?: return
        binding.progress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.runIo({ VisitRepo.delete(token, visit.id) }) { result ->
            val e = result.exceptionOrNull()
            if (e != null) {
                binding.progress.visibility = View.GONE
                activity?.handleApiFailure(e)
                return@runIo
            }
            // 删成功后重新拉一次，而不是本地移除：以后端为准（也顺带同步别人改过的数据）
            load()
        }
    }
}