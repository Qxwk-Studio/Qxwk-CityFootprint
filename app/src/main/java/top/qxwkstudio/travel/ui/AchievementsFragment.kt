package top.qxwkstudio.travel.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CheckinRepo
import top.qxwkstudio.travel.data.DataVersion
import top.qxwkstudio.travel.data.db.AchievementStateEntity
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.databinding.FragmentAchievements2Binding
import top.qxwkstudio.travel.logic.Achievements
import top.qxwkstudio.travel.logic.Dates

/**
 * 成就页（2.0）：54 条成就按分类展示，已解锁的显示解锁日期。
 *
 * 判定全在本地（[Achievements.evaluate]）：输入是全部打卡（函数内部按时间排序），输出是每条成就的
 * 达成状态与**回溯出来的解锁时间**。算完把结果落进 `achievement_states` —— 落表不是为了记住
 * 「有没有达成」（那个能重算），而是记住解锁时间（重算不出来，见 Entities.kt 的说明）。
 *
 * 写入时**保留已有的 unlockedAt**：这次重算得 null（比如用户删了一条打卡、暂时不再满足条件）时，
 * 不该把历史解锁时间抹掉 —— 已经点亮的成就在卡片上继续亮着。
 */
class AchievementsFragment : Fragment() {

    private var _binding: FragmentAchievements2Binding? = null
    private val binding get() = _binding!!

    /** null = 「全部」；否则是 [Achievements.categories] 的下标。 */
    private var selectedCategory: Int? = null

    private var states: Map<String, Achievements.State> = emptyMap()
    private var seenVersion = -1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAchievements2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildChips()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) load()
    }

    /** 打卡页/行程页写过数据后切回来要重算（版本号变了才重算）。 */
    override fun onResume() {
        super.onResume()
        if (seenVersion != DataVersion.current) load()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.runIo({
            val db = AppDatabase.get(requireContext())
            val samples = CheckinRepo.listChronological(db).map {
                Achievements.Sample(it.cityName, it.checkinAt)
            }
            val evaluated = Achievements.evaluate(samples)

            // 合并已有解锁时间：落表只为记住「什么时候解锁的」，不拿它反推达成与否
            val existing = db.achievements().list().associateBy { it.code }
            db.achievements().upsertAll(
                evaluated.map { (code, state) ->
                    AchievementStateEntity(
                        code = code,
                        unlockedAt = existing[code]?.unlockedAt ?: state.unlockedAt,
                        progress = state.progress,
                        target = state.target,
                    )
                }
            )
            evaluated
        }) { result ->
            result.getOrNull()?.let {
                states = it
                seenVersion = DataVersion.current
                render()
            }
        }
    }

    // ────────────────────────── 分类胶囊 ──────────────────────────

    private fun buildChips() {
        val container = binding.chipContainer
        container.removeAllViews()
        addChip(container, getString(R.string.ach_all), selectedCategory == null) {
            selectedCategory = null
            refreshChips()
            render()
        }
        Achievements.categories.forEachIndexed { index, category ->
            addChip(container, category.title, selectedCategory == index) {
                selectedCategory = index
                refreshChips()
                render()
            }
        }
    }

    private fun refreshChips() = buildChips()

    /** 一个胶囊：选中态主色底白字，未选中浅底次要色。 */
    private fun addChip(parent: LinearLayout, text: String, selected: Boolean, onClick: () -> Unit) {
        val chip = Chips.pill(requireContext(), text, selected, onClick)
        chip.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(8) }
        parent.addView(chip)
    }

    // ────────────────────────── 列表 ──────────────────────────

    private fun render() {
        val b = binding
        b.textSummary.text = getString(
            R.string.ach_summary,
            states.count { it.value.done },
            states.size,
        )

        b.container.removeAllViews()
        val categories = Achievements.categories
        val shown = selectedCategory?.let { listOf(it) } ?: categories.indices.toList()
        for (index in shown) {
            if (selectedCategory == null) b.container.addView(sectionHeader(categories[index].title))
            for (def in categories[index].items) {
                val state = states[def.code] ?: continue
                b.container.addView(achievementCard(index, def, state))
            }
        }
    }

    private fun sectionHeader(title: String): View = TextView(requireContext()).apply {
        text = title
        setTextColor(ContextCompat.getColor(requireContext(), R.color.fg))
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(8))
    }

    /** 一张成就卡：左侧分类图标 / 主副标题 / 右上解锁日期 / 底部进度条。 */
    private fun achievementCard(
        category: Int,
        def: Achievements.AchievementDef,
        state: Achievements.State,
    ): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            // 未解锁整卡压暗（网页 .achievement 的 opacity .55）
            alpha = if (state.done) 1f else 0.55f
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.TOP
        }

        header.addView(ImageView(ctx).apply {
            setImageResource(iconFor(category))
            imageTintList = android.content.res.ColorStateList.valueOf(
                Palette.accent(ctx),
            )
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) }
            contentDescription = null
        })

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(ctx).apply {
            text = def.name
            setTextColor(ContextCompat.getColor(ctx, R.color.fg))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        texts.addView(TextView(ctx).apply {
            text = def.desc
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            textSize = 13f
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(texts)

        if (state.done && state.unlockedAt != null) {
            header.addView(TextView(ctx).apply {
                text = getString(R.string.ach_unlocked_at, Dates.ymd(state.unlockedAt))
                setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
                textSize = 12f
                setPadding(dp(8), 0, 0, 0)
            })
        }
        card.addView(header)

        // 底部：全长进度条 + 数值
        val progressRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        progressRow.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = state.target.coerceAtLeast(1)
            progress = state.progress
            progressTintList = android.content.res.ColorStateList.valueOf(
                Palette.accent(ctx),
            )
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.muted),
            )
            layoutParams = LinearLayout.LayoutParams(0, dp(6), 1f)
        })
        progressRow.addView(TextView(ctx).apply {
            text = getString(R.string.ach_progress, state.progress, state.target)
            setTextColor(
                if (state.done) Palette.accent(ctx) else ContextCompat.getColor(ctx, R.color.muted_fg),
            )
            textSize = 12f
            setPadding(dp(10), 0, 0, 0)
        })
        card.addView(progressRow)
        return card
    }

    /**
     * 分类图标。2.0 还没换统一图标库（P2.4 是全站换 Material Symbols），
     * 这里先复用现有矢量图、按分类各取一张 —— 54 条成就挤同一个图标就太难看了。
     */
    private fun iconFor(category: Int): Int = when (category) {
        0 -> R.drawable.ic_map
        1 -> R.drawable.ic_trips
        2 -> R.drawable.ic_locate
        else -> R.drawable.ic_achievement
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
