package top.qxwkstudio.travel.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.ApiException
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.VisitRepo
import top.qxwkstudio.travel.databinding.FragmentAchievementsBinding
import top.qxwkstudio.travel.databinding.ItemAchievementBinding
import top.qxwkstudio.travel.databinding.ItemAchievementGroupBinding
import top.qxwkstudio.travel.logic.Achievement
import top.qxwkstudio.travel.logic.AchievementGroup

/**
 * 「我的成就」：页面内容即网页端足迹管理页的**右侧栏**（标题 / 摘要行 / 分类 / 成就卡）。
 *
 * 名称、图标、说明、判定全部来自后端：GET /api/my-visits 就着那批足迹行就地判定后
 * 把 achievements 一起回给我们（backend/src/achievements.js 是唯一一份定义）。
 * 所以这一页只发这一个请求，客户端也不再自带一份成就判定表。
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
        // 与主页/统计页同一套下拉刷新：指示器统一用主色（SwipeRefreshLayout 没有 XML 配色属性）
        binding.swipe.setColorSchemeResources(R.color.accent)
        binding.swipe.setOnRefreshListener { load(force = true) }
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
        val token = store.token
        if (token == null) {
            Session.expired(requireActivity())
            return
        }
        binding.swipe.isRefreshing = true
        binding.textError.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.runIo({ VisitRepo.myVisits(requireContext(), token, force) }) { result ->
            binding.swipe.isRefreshing = false

            val data = result.getOrNull()
            if (data == null) {
                val e = result.exceptionOrNull() ?: RuntimeException()
                if (e is ApiException && e.code == 401) {
                    Session.expired(requireActivity())
                    return@runIo
                }
                // 这一页本来就有位置，错误就地显示，不弹 Toast
                binding.textError.text = e.message ?: getString(R.string.common_error)
                binding.textError.visibility = View.VISIBLE
                return@runIo
            }
            render(data.achievements)
        }
    }

    /**
     * 摘要行按「所有分类合计」算：已点亮 X / Y —— 与网页 renderAchievements 里
     * doneAll / totalAll 的算法一致（两边都是把各分类的条目摊平再数）。
     */
    private fun render(groups: List<AchievementGroup>) {
        val done = groups.sumOf { group -> group.items.count { it.done } }
        val total = groups.sumOf { it.items.size }
        binding.textSummaryDone.text = done.toString()
        binding.textSummaryTotal.text = getString(R.string.achievement_summary_total, total)
        // 进度条按 0-100 整数百分比，total 为 0 时不画（NaN/除零都不该发生）
        binding.achievementBar.progress = if (total > 0) done * 100 / total else 0

        binding.achievementsContainer.removeAllViews()
        groups.forEach { group ->
            val header = ItemAchievementGroupBinding.inflate(layoutInflater, binding.achievementsContainer, false)
            header.textGroupTitle.text = group.title
            val groupDone = group.items.count { it.done }
            header.textGroupCount.text =
                getString(R.string.achievement_progress_ratio, groupDone, group.items.size)
            // 该组的小进度条（与网页 .category-bar 同口径）：0-100 整数百分比
            header.groupBar.progress = if (group.items.isEmpty()) 0 else groupDone * 100 / group.items.size
            group.items.forEach { achievement -> header.groupItems.addView(itemView(achievement, header.groupItems)) }
            binding.achievementsContainer.addView(header.root)
        }
        // 铺完再调度一次逐条淡入（动画见 res/anim/anim_layout_items）：layoutAnimation
        // 只在容器「下一次布局」时播，而首帧布局时数据还没回来，那一次容器是空的。下拉刷新会重播
        binding.achievementsContainer.scheduleLayoutAnimation()
    }

    /** 铺一条成就卡：达成与否只体现在这张卡自己身上（整卡压暗 / 换底色描边 / 露出勾）。 */
    private fun itemView(achievement: Achievement, parent: ViewGroup): View {
        val item = ItemAchievementBinding.inflate(layoutInflater, parent, false)
        item.textIcon.text = achievement.icon
        item.textName.text = achievement.name
        item.textDesc.text = achievement.desc
        item.textCheck.visibility = if (achievement.done) View.VISIBLE else View.GONE

        if (achievement.done) {
            item.root.setCardBackgroundColor(color(R.color.accent_light))
            item.root.setStrokeColor(color(R.color.achievement_done_stroke))
        } else {
            // 未达成整卡压暗（网页 .achievement 的 opacity: 0.55）
            item.root.alpha = 0.55f
        }
        return item.root
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)
}