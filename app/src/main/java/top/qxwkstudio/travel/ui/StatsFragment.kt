package top.qxwkstudio.travel.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.ApiException
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.VisitRepo
import top.qxwkstudio.travel.databinding.FragmentStatsBinding
import top.qxwkstudio.travel.databinding.ItemRankBinding
import top.qxwkstudio.travel.logic.SiteStats
import top.qxwkstudio.travel.logic.Visit

/**
 * 「全站统计」：全站汇总（GET /api/stats，公开）+ 城市排行。
 *
 * 一处细节：「我打卡的城市」用的是**自己的足迹**（GET /api/my-visits），
 * 不是全站统计 —— 所以这一页要发两个请求，合成一次后台任务
 * （同一个线程里先后发，省一次线程创建）。个人成就已拆到独立的「我的成就」tab
 * （AchievementsFragment），这里不再渲染。
 *
 * 排行只画前 10 名：与网页版默认视图一致（docs/stats.html 的 RANK_DEFAULT=10），
 * 网页那个「展开到 50」的开关这里没做（要额外一个按钮与文案），名次靠后不影响视图。
 */
class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = Store(requireContext())
        load()
    }

    /**
     * 切回本 tab 时重新拉一次：数字会变（别人也在打卡）。
     * 为什么不用 onResume：tab 是 add/hide/show 切换的，隐藏的 Fragment 仍是 RESUMED（见 MainActivity）。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) load()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun load() {
        val token = store.token
        if (token == null) {
            Session.expired(requireActivity())
            return
        }
        binding.progress.visibility = View.VISIBLE
        binding.textError.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.runIo({
            // 「我打卡的城市」与成就都基于自己的足迹；统计那份是公开接口
            val visits = VisitRepo.myVisits(token)
            val stats = VisitRepo.stats()
            visits to stats
        }) { result ->
            binding.progress.visibility = View.GONE

            val pair = result.getOrNull()
            if (pair == null) {
                val e = result.exceptionOrNull() ?: RuntimeException()
                if (e is ApiException && e.code == 401) {
                    Session.expired(requireActivity())
                    return@runIo
                }
                // 统计页的错误就地显示（这一页本来就有位置），不弹 Toast
                binding.textError.text = e.message ?: getString(R.string.common_error)
                binding.textError.visibility = View.VISIBLE
                return@runIo
            }
            render(pair.first, pair.second)
        }
    }

    private fun render(visits: List<Visit>, stats: SiteStats) {
        binding.valueVisits.text = stats.totalVisits.toString()
        binding.valueCities.text = stats.totalCities.toString()
        // 与网页版同口径：users 是「users LEFT JOIN visits」的完整表，直接取条数（stats.html:421）
        binding.valueUsers.text = stats.users.size.toString()
        // 同一座城市打卡多次只算一座
        binding.valueMyCities.text = visits.map { it.city }.distinct().size.toString()
        // 管理员看到的数字含私密足迹，必须说明白，否则会以为统计把别人的私密记录抖出来了
        binding.textAdminNote.visibility = if (stats.isAdmin) View.VISIBLE else View.GONE

        binding.textRankTop.text = getString(R.string.stats_rank_top, RANK_DEFAULT)

        binding.rankContainer.removeAllViews()
        stats.cityRank.take(RANK_DEFAULT).forEachIndexed { index, row ->
            val item = ItemRankBinding.inflate(layoutInflater, binding.rankContainer, false)
            item.textRank.text = (index + 1).toString()
            item.textCity.text = row.city
            // 次数与人数各占一列，和表头那两列左右对齐（设计稿的 rank-row）
            item.textCount.text = row.count.toString()
            item.textPeople.text = row.people.toString()
            binding.rankContainer.addView(item.root)
        }
    }

    private companion object {
        /** 与网页版 RANK_DEFAULT 对齐（frontend/stats.html）。 */
        const val RANK_DEFAULT = 10
    }
}