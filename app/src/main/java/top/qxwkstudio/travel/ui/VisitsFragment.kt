package top.qxwkstudio.travel.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.VisitRepo
import top.qxwkstudio.travel.databinding.FragmentVisitsBinding
import top.qxwkstudio.travel.logic.Visit

/**
 * 「我的足迹」：列表 + 下拉刷新 + 新增/编辑/删除。
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
        binding.fab.setOnClickListener { editResult.launch(VisitEditActivity.intent(requireContext(), null)) }

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
        val b = _binding ?: return
        b.progress.visibility = View.VISIBLE
        b.textEmpty.visibility = View.GONE

        Async.run({ VisitRepo.myVisits(token) }) { result ->
            val bd = _binding ?: return@run
            bd.progress.visibility = View.GONE
            // 下拉刷新转圈必须停：失败时不停，用户会以为还在加载
            bd.swipe.isRefreshing = false

            val visits = result.getOrNull()
            if (visits == null) {
                // 401 在这条链路里统一处理（清 token 回登录页 + 只弹一次），其余弹后端文案
                activity?.handleApiFailure(result.exceptionOrNull() ?: RuntimeException())
                return@run
            }
            adapter.submit(visits)
            bd.textEmpty.visibility = if (visits.isEmpty()) View.VISIBLE else View.GONE
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
        val b = _binding ?: return
        b.progress.visibility = View.VISIBLE
        Async.run({ VisitRepo.delete(token, visit.id) }) { result ->
            val bd = _binding ?: return@run
            val e = result.exceptionOrNull()
            if (e != null) {
                bd.progress.visibility = View.GONE
                activity?.handleApiFailure(e)
                return@run
            }
            // 删成功后重新拉一次，而不是本地移除：以后端为准（也顺带同步别人改过的数据）
            load()
        }
    }
}