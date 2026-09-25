package top.qxwkstudio.travel.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.ApiException
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.VisitRepo
import top.qxwkstudio.travel.databinding.FragmentStatsBinding
import top.qxwkstudio.travel.databinding.ItemAchievementBinding
import top.qxwkstudio.travel.databinding.ItemAchievementGroupBinding
import top.qxwkstudio.travel.databinding.ItemRankBinding
import top.qxwkstudio.travel.logic.Achievements
import top.qxwkstudio.travel.logic.SiteStats
import top.qxwkstudio.travel.logic.Visit

/**
 * 「统计」：全站汇总（GET /api/stats，公开）+ 城市排行 + 我的成就。
 *
 * 一处细节：成就与「我打卡的城市」用的是**自己的足迹**（GET /api/my-visits），
 * 不是全站统计 —— 成就判定只能按「我去过哪些城市」。所以这一页要发两个请求，
 * 合成一次后台任务（同一个线程里先后发，省一次线程创建）。
 *
 * 排行只画前 10 名：与网页版默认视图一致（frontend/stats.html 的 RANK_DEFAULT=10），
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
        val b = _binding ?: return
        b.progress.visibility = View.VISIBLE
        b.textError.visibility = View.GONE

        Async.run({
            // 「我打卡的城市」与成就都基于自己的足迹；统计那份是公开接口
            val visits = VisitRepo.myVisits(token)
            val stats = VisitRepo.stats()
            visits to stats
        }) { result ->
            val bd = _binding ?: return@run
            bd.progress.visibility = View.GONE

            val pair = result.getOrNull()
            if (pair == null) {
                val e = result.exceptionOrNull() ?: RuntimeException()
                if (e is ApiException && e.code == 401) {
                    Session.expired(requireActivity())
                    return@run
                }
                // 统计页的错误就地显示（这一页本来就有位置），不弹 Toast
                bd.textError.text = e.message ?: getString(R.string.common_error)
                bd.textError.visibility = View.VISIBLE
                return@run
            }
            render(pair.first, pair.second)
        }
    }

    private fun render(visits: List<Visit>, stats: SiteStats) {
        val b = _binding ?: return

        b.valueVisits.text = stats.totalVisits.toString()
        b.valueCities.text = stats.totalCities.toString()
        // 与网页版同口径：users 是「users LEFT JOIN visits」的完整表，直接取条数（stats.html:421）
        b.valueUsers.text = stats.users.size.toString()
        // 同一座城市打卡多次只算一座
        b.valueMyCities.text = visits.map { it.city }.distinct().size.toString()
        // 管理员看到的数字含私密足迹，必须说明白，否则会以为统计把别人的私密记录抖出来了
        b.textAdminNote.visibility = if (stats.isAdmin) View.VISIBLE else View.GONE

        b.rankContainer.removeAllViews()
        stats.cityRank.take(RANK_DEFAULT).forEachIndexed { index, row ->
            val item = ItemRankBinding.inflate(layoutInflater, b.rankContainer, false)
            item.textRank.text = (index + 1).toString()
            item.textCity.text = row.city
            item.textCount.text = getString(R.string.stats_rank_item, row.count, row.people)
            b.rankContainer.addView(item.root)
        }

        // 成就：判定逻辑与网页版同一份定义（见 logic/Achievements 的说明）
        val groups = Achievements.all(visits.map { it.city })
        val (done, total) = Achievements.progress(groups)
        b.textAchievementProgress.text = getString(R.string.stats_achievements_progress, done, total)

        b.achievementsContainer.removeAllViews()
        for (group in groups) {
            val title = ItemAchievementGroupBinding.inflate(layoutInflater, b.achievementsContainer, false)
            // item_achievement_group.xml 的根本身就带 id=textGroupTitle，root 就是那个 TextView
            title.root.text = group.title
            b.achievementsContainer.addView(title.root)

            for (achievement in group.items) {
                val item = ItemAchievementBinding.inflate(layoutInflater, b.achievementsContainer, false)
                item.textIcon.text = achievement.icon
                item.textName.text = achievement.name
                item.textDesc.text = achievement.desc
                item.textState.text =
                    getString(if (achievement.done) R.string.stats_achievement_done else R.string.stats_achievement_todo)

                // 未达成的只把「名字/说明/状态」压灰。图标是 emoji（彩色字体），
                // 给它 setTextColor 反而会出奇怪的颜色，所以图标不染
                val nameColor = color(if (achievement.done) R.color.text_primary else R.color.achievement_todo)
                val subColor = color(if (achievement.done) R.color.text_secondary else R.color.achievement_todo)
                item.textName.setTextColor(nameColor)
                item.textDesc.setTextColor(subColor)
                item.textState.setTextColor(subColor)

                b.achievementsContainer.addView(item.root)
            }
        }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)

    private companion object {
        /** 与网页版 RANK_DEFAULT 对齐（frontend/stats.html）。 */
        const val RANK_DEFAULT = 10
    }
}