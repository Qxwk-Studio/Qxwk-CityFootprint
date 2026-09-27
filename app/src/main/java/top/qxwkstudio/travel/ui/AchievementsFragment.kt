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
import top.qxwkstudio.travel.databinding.FragmentAchievementsBinding
import top.qxwkstudio.travel.databinding.ItemAchievementBinding
import top.qxwkstudio.travel.databinding.ItemAchievementGroupBinding
import top.qxwkstudio.travel.logic.Achievements
import top.qxwkstudio.travel.logic.Visit

/**
 * 「我的成就」：页面内容即网页端足迹管理页的成就区（原先是统计页底部那张卡，按 tab 拆出来）。
 *
 * 成就只能按「我去过哪些城市」判定，所以这一页只发一个请求 GET /api/my-visits，
 * 不碰全站统计 —— 判定逻辑与网页版同一份定义（见 logic/Achievements 的说明）。
 */
class AchievementsFragment : Fragment() {

    private var _binding: FragmentAchievementsBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAchievementsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = Store(requireContext())
        load()
    }

    /**
     * 切回本 tab 时重新拉一次：新加了足迹，成就可能刚好点亮。
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

        Async.run({ VisitRepo.myVisits(token) }) { result ->
            val bd = _binding ?: return@run
            bd.progress.visibility = View.GONE

            val visits = result.getOrNull()
            if (visits == null) {
                val e = result.exceptionOrNull() ?: RuntimeException()
                if (e is ApiException && e.code == 401) {
                    Session.expired(requireActivity())
                    return@run
                }
                // 这一页本来就有位置，错误就地显示，不弹 Toast
                bd.textError.text = e.message ?: getString(R.string.common_error)
                bd.textError.visibility = View.VISIBLE
                return@run
            }
            render(visits)
        }
    }

    private fun render(visits: List<Visit>) {
        val b = _binding ?: return
        val groups = Achievements.all(visits.map { it.city })
        val (done, total) = Achievements.progress(groups)
        b.textProgressRatio.text = getString(R.string.achievement_progress_ratio, done, total)
        // 进度条按 0-100 整数百分比，total 为 0 时不画（NaN/除零都不该发生）
        b.achievementBar.progress = if (total > 0) done * 100 / total else 0

        b.achievementsContainer.removeAllViews()
        groups.forEachIndexed { groupIndex, group ->
            val header = ItemAchievementGroupBinding.inflate(layoutInflater, b.achievementsContainer, false)
            header.textGroupTitle.text = group.title
            header.textGroupCount.text =
                getString(R.string.achievement_progress_ratio, group.items.count { it.done }, group.items.size)
            // 第一组上方不画分隔线（前面就是进度条）
            header.groupTopDivider.visibility = if (groupIndex == 0) View.GONE else View.VISIBLE
            b.achievementsContainer.addView(header.root)

            group.items.forEachIndexed { itemIndex, achievement ->
                val item = ItemAchievementBinding.inflate(layoutInflater, b.achievementsContainer, false)
                item.textIcon.text = achievement.icon
                item.textName.text = achievement.name
                item.textDesc.text = achievement.desc

                // 未达成的只把「名字/说明」压灰。图标是 emoji（彩色字体），
                // 给它 setTextColor 反而会出奇怪的颜色，所以图标不染
                val nameColor = color(if (achievement.done) R.color.text_primary else R.color.achievement_todo)
                val subColor = color(if (achievement.done) R.color.text_secondary else R.color.achievement_todo)
                item.textName.setTextColor(nameColor)
                item.textDesc.setTextColor(subColor)

                // 每组第一条不画顶部分隔线（分组标题已经把它和上一条隔开了）
                if (itemIndex == 0) item.root.setBackgroundResource(0)

                b.achievementsContainer.addView(item.root)
            }
        }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)
}
