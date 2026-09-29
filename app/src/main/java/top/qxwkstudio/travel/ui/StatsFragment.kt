package top.qxwkstudio.travel.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.ApiException
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.VisitRepo
import top.qxwkstudio.travel.databinding.FragmentStatsBinding
import top.qxwkstudio.travel.databinding.ItemAchievementCountBinding
import top.qxwkstudio.travel.databinding.ItemAchievementCountCategoryBinding
import top.qxwkstudio.travel.databinding.ItemRankBinding
import top.qxwkstudio.travel.logic.AchievementCountGroup
import top.qxwkstudio.travel.logic.City
import top.qxwkstudio.travel.logic.SiteStats

/**
 * 「全站统计」：版面与口径都对齐网页端 docs/stats.html ——
 * 四张数字卡（总行程 / 总城市 / 覆盖省份 / 总用户）、城市次数排名（默认 10 名、可展开到 50）、
 * 成就达成人数。只发一个公开请求 GET /api/stats。
 *
 * 覆盖省份在**客户端从 /stats 已有数据推出来**（网页也是这么算的，不额外开接口）：
 * 把 cityRank 的城市名拿去 assets/cities.json 查省份，再算种类数。
 * 成就达成人数则**由后端算好**（backend/src/achievements.js 的 achievementCounts），
 * App 不再自带一份判定表 —— 定义只有一处，两端不会算出不同结果。
 */
class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store

    /** 排行是否已展开到前 50。本页只在首次创建时 load 一次，切 tab 回来不重拉，这个选择自然留得住。 */
    private var rankExpanded = false

    /** 最近一次的数据：展开/收起时本地重排，不必再发一次请求。 */
    private var lastStats: SiteStats? = null
    private var lastCities: List<City> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = Store(requireContext())
        // 与主页/成就页同一套下拉刷新：指示器统一用主色（SwipeRefreshLayout 没有 XML 配色属性）
        binding.swipe.setColorSchemeResources(R.color.accent)
        binding.swipe.setOnRefreshListener { load(force = true) }
        binding.btnRankMore.setOnClickListener {
            rankExpanded = !rankExpanded
            lastStats?.let { renderRank(it, lastCities) }
        }
        // 只在首次创建时拉一次；切回本 tab 不再自动重拉（那会每次切栏都打网络）。
        // 数据本身有 Store 的一天缓存兜底，要立刻看新的就下拉刷新（force = true）。
        load()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** [force] = true 跳过一天缓存强制走网络（下拉刷新用）。 */
    private fun load(force: Boolean = false) {
        if (store.token == null) {
            // 正常进不来（MainActivity 已判定登录态）；真发生就按统一流程回登录页
            Session.expired(requireActivity())
            return
        }
        binding.swipe.isRefreshing = true
        binding.textError.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.runIo({ VisitRepo.stats(requireContext(), force) }) { result ->
            binding.swipe.isRefreshing = false

            val stats = result.getOrNull()
            if (stats == null) {
                val e = result.exceptionOrNull() ?: RuntimeException()
                if (e is ApiException && e.code == 401) {
                    Session.expired(requireActivity())
                    return@runIo
                }
                // 统计页的错误就地显示（这一页本来就有位置），不弹 Toast。
                // 兜底文案照网页 stats.html 那句「统计加载失败，请稍后重试」，不用通用 common_error
                binding.textError.text = e.message ?: getString(R.string.stats_load_failed)
                binding.textError.visibility = View.VISIBLE
                return@runIo
            }
            render(stats)
        }
    }

    private fun render(stats: SiteStats) {
        val allCities = CityStore.all(requireContext())
        lastStats = stats
        lastCities = allCities

        binding.valueVisits.text = stats.totalVisits.toString()
        binding.valueCities.text = stats.totalCities.toString()
        // 覆盖省份：从城市排名去重省份（与网页 stats.html 同口径）
        binding.valueProvinces.text =
            stats.cityRank.mapNotNull { provinceOf(it.city, allCities) }.distinct().size.toString()
        binding.valueUsers.text = stats.totalUsers.toString()
        // 管理员看到的数字含私密足迹，必须说明白，否则会以为统计把别人的私密记录抖出来了
        binding.textAdminNote.visibility = if (stats.isAdmin) View.VISIBLE else View.GONE

        renderRank(stats, allCities)
        renderAchievementCounts(stats.achievements)
    }

    /** 城市排行：行样式照网页 .rank-item（名次圆 + 城市·省份 + 去过 N 人 + N 次），+ 展开/收起。 */
    private fun renderRank(stats: SiteStats, allCities: List<City>) {
        val rows = stats.cityRank.take(if (rankExpanded) RANK_EXPAND else RANK_DEFAULT)
        binding.rankContainer.removeAllViews()
        rows.forEachIndexed { index, row ->
            val item = ItemRankBinding.inflate(layoutInflater, binding.rankContainer, false)
            item.textRank.text = (index + 1).toString()
            // 前三名的名次圆高亮：主色底 + 白字（等价网页 .rank-no.top）
            if (index < 3) {
                item.textRank.backgroundTintList = ColorStateList.valueOf(color(R.color.accent))
                item.textRank.setTextColor(color(R.color.on_accent))
            }
            item.textCity.text = row.city
            item.textProvince.text = provinceOf(row.city, allCities)?.let { "· $it" }.orEmpty()
            item.textPeople.text = getString(R.string.stats_rank_people, row.people)
            item.textCount.text = getString(R.string.stats_rank_count, row.count)
            binding.rankContainer.addView(item.root)
        }

        // 全站还没有任何足迹：照网页把列表位置换成一句话空态，展开/收起按钮也就没意义了
        val rankEmpty = rows.isEmpty()
        binding.textRankEmpty.visibility = if (rankEmpty) View.VISIBLE else View.GONE
        binding.btnRankMore.visibility = if (rankEmpty) View.GONE else View.VISIBLE

        // 展开 / 收起：三种文案与网页 .rank-toggle 一致（不够 10 名时按钮置灰）
        val total = stats.cityRank.size
        binding.btnRankMore.text = when {
            rankExpanded -> getString(R.string.stats_rank_collapse, RANK_DEFAULT)
            total > RANK_DEFAULT -> getString(R.string.stats_rank_expand, minOf(RANK_EXPAND, total), total)
            else -> getString(R.string.stats_rank_total, total)
        }
        binding.btnRankMore.isEnabled = rankExpanded || total > RANK_DEFAULT
    }

    /**
     * 成就达成人数：数字直接用后端算好的 achievements[].items[].count。
     * 后端按成就的**稳定 code** 累计，客户端不再自己跑一遍判定 ——
     * 原先这里是逐人跑 logic/Achievements 再拿中文 name 当键计数，改一次展示文案就会静默错位。
     */
    private fun renderAchievementCounts(groups: List<AchievementCountGroup>) {
        binding.achCountContainer.removeAllViews()
        groups.forEachIndexed { catIndex, cat ->
            val header = ItemAchievementCountCategoryBinding.inflate(layoutInflater, binding.achCountContainer, false)
            header.textCategoryTitle.text = cat.title
            // 第一个分类不画上方分隔线（前面就是卡片标题）
            header.categoryTopDivider.visibility = if (catIndex == 0) View.GONE else View.VISIBLE
            binding.achCountContainer.addView(header.root)

            for (a in cat.items) {
                val item = ItemAchievementCountBinding.inflate(layoutInflater, binding.achCountContainer, false)
                item.textIcon.text = a.icon
                item.textName.text = a.name
                item.textDesc.text = a.desc
                item.textNum.text = getString(R.string.stats_achv_count_num, a.count)
                // 有人达成才用主色（等价网页 .ach-count-num.done）
                item.textNum.setTextColor(color(if (a.count > 0) R.color.accent else R.color.muted_fg))
                binding.achCountContainer.addView(item.root)
            }
        }
    }

    /** 城市名 → 省份；本机城市表里查不到（或省份为空）就返回 null（该城市不计入省份数）。 */
    private fun provinceOf(city: String, allCities: List<City>): String? =
        allCities.firstOrNull { it.name == city }?.province?.takeIf { it.isNotBlank() }

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)

    private companion object {
        /** 与网页版 RANK_DEFAULT / RANK_EXPAND 对齐（docs/stats.html）。 */
        const val RANK_DEFAULT = 10
        const val RANK_EXPAND = 50
    }
}
