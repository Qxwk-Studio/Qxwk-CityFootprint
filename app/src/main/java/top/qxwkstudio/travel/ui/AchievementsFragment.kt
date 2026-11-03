package top.qxwkstudio.travel.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CheckinRepo
import top.qxwkstudio.travel.data.DataVersion
import top.qxwkstudio.travel.data.db.AchievementStateEntity
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.databinding.FragmentAchievements2Binding
import top.qxwkstudio.travel.databinding.ItemAchievementBinding
import top.qxwkstudio.travel.logic.Achievements
import top.qxwkstudio.travel.logic.Dates

/**
 * 成就页（2.0）：54 条成就按分类展示，已解锁的显示解锁日期与勾徽章。
 *
 * 判定全在本地（[Achievements.evaluate]）：输入是全部打卡（函数内部按时间排序），输出是每条成就的
 * 达成状态与**回溯出来的解锁时间**。算完把结果落进 `achievement_states` —— 落表不是为了记住
 * 「有没有达成」（那个能重算），而是记住解锁时间（重算不出来，见 Entities.kt 的说明）。
 *
 * 列表用 RecyclerView：之前 54 张卡一次性 addView 进 LinearLayout，进入这一页要在主线程建几百个
 * View，肉眼可见地卡一下；现在只建可见的十来个。
 */
class AchievementsFragment : Fragment() {

    private var _binding: FragmentAchievements2Binding? = null
    private val binding get() = _binding!!

    /** null = 「全部」；否则是 [Achievements.categories] 的下标。 */
    private var selectedCategory: Int? = null

    private var states: Map<String, Achievements.State> = emptyMap()
    private var seenVersion = -1
    private val adapter = AchievementAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAchievements2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        buildChips()
        renderProgress()
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
        binding.list.adapter = null
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
                renderProgress()
                adapter.submit(buildRows())
            }
        }
    }

    // ────────────────────────── 分类胶囊 ──────────────────────────

    private fun buildChips() {
        val container = binding.chipContainer
        container.removeAllViews()
        addChip(container, getString(R.string.ach_all), selectedCategory == null) {
            selectedCategory = null
            onCategoryChanged()
        }
        Achievements.categories.forEachIndexed { index, category ->
            addChip(container, category.title, selectedCategory == index) {
                selectedCategory = index
                onCategoryChanged()
            }
        }
        // 首帧把选中块直接放到「全部」上（不播动画，免得看见它从左边滑过来）
        container.post { moveIndicator(animate = false) }
    }

    /** 分类胶囊：文字本身不带底，选中块是压在下面的 chipIndicator。 */
    private fun addChip(parent: LinearLayout, text: String, selected: Boolean, onClick: () -> Unit) {
        val context = requireContext()
        val density = resources.displayMetrics.density
        parent.addView(TextView(context).apply {
            this.text = text
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (36 * density).toInt(),
            ).apply { marginEnd = (4 * density).toInt() }
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.on_accent else R.color.text_secondary,
                ),
            )
            isClickable = true
            setOnClickListener { onClick() }
        })
    }

    private fun onCategoryChanged() {
        refreshChipColors()
        moveIndicator(animate = true)
        renderProgress()
        adapter.submit(buildRows())
        binding.list.scrollToPosition(0)
    }

    private fun refreshChipColors() {
        val container = binding.chipContainer
        val selectedChip = selectedCategory?.let { it + 1 } ?: 0
        for (index in 0 until container.childCount) {
            val chip = container.getChildAt(index) as TextView
            chip.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (index == selectedChip) R.color.on_accent else R.color.text_secondary,
                ),
            )
        }
    }

    /** 把选中块平移到当前分类下面；[animate] = false 用于首帧直接落位。 */
    private fun moveIndicator(animate: Boolean) {
        val container = binding.chipContainer
        val indicator = binding.chipIndicator
        val index = selectedCategory?.let { it + 1 } ?: 0
        if (index >= container.childCount) return
        val chip = container.getChildAt(index)
        chip.post {
            val lp = indicator.layoutParams
            if (lp.width != chip.width) {
                lp.width = chip.width
                indicator.layoutParams = lp
            }
            val target = chip.left.toFloat()
            if (animate) {
                indicator.animate().translationX(target).setDuration(180L).start()
                binding.chipScroll.smoothScrollTo((chip.left - dp(16)).coerceAtLeast(0), 0)
            } else {
                indicator.animate().cancel()
                indicator.translationX = target
            }
        }
    }

    // ────────────────────────── 进度卡 / 列表 ──────────────────────────

    private fun renderProgress() {
        val b = binding
        val categories = Achievements.categories
        val codes = if (selectedCategory == null) {
            categories.flatMap { c -> c.items.map { it.code } }
        } else {
            categories[selectedCategory!!].items.map { it.code }
        }
        val done = codes.count { states[it]?.done == true }
        val total = codes.size.coerceAtLeast(1)
        val title = selectedCategory?.let { categories[it].title } ?: getString(R.string.ach_all)
        b.textCategoryProgress.text = "$title · " + getString(R.string.ach_unlocked_count, done, codes.size)
        b.textCategoryPercent.text = getString(R.string.ach_category_percent, done * 100 / total)

        val accent = Palette.accent(requireContext())
        b.barCategory.max = total
        b.barCategory.progress = done
        b.barCategory.progressTintList = ColorStateList.valueOf(accent)
        b.barCategory.progressBackgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.muted),
        )
    }

    private sealed interface Row {
        data class Section(val title: String) : Row
        data class Card(
            val category: Int,
            val def: Achievements.AchievementDef,
            val state: Achievements.State,
        ) : Row
    }

    private fun buildRows(): List<Row> {
        val categories = Achievements.categories
        val shown = selectedCategory?.let { listOf(it) } ?: categories.indices.toList()
        val rows = ArrayList<Row>(64)
        for (index in shown) {
            if (selectedCategory == null) rows.add(Row.Section(categories[index].title))
            for (def in categories[index].items) {
                val state = states[def.code] ?: continue
                rows.add(Row.Card(index, def, state))
            }
        }
        return rows
    }

    private inner class AchievementAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Section) TYPE_SECTION else TYPE_CARD

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val context = parent.context
            return if (viewType == TYPE_SECTION) {
                val tv = TextView(context).apply {
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(ContextCompat.getColor(context, R.color.fg))
                    setPadding(0, dp(8), 0, dp(8))
                }
                SectionHolder(tv)
            } else {
                val b = ItemAchievementBinding.inflate(LayoutInflater.from(context), parent, false)
                b.root.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }
                CardHolder(b)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Section -> (holder as SectionHolder).text.text = row.title
                is Row.Card -> bindCard(holder as CardHolder, row)
            }
        }

        private fun bindCard(holder: CardHolder, row: Row.Card) {
            val ctx = requireContext()
            val b = holder.binding
            val state = row.state
            b.root.alpha = if (state.done) 1f else 0.55f
            b.icon.setImageResource(iconFor(row.category))
            b.textName.text = row.def.name
            b.textDesc.text = row.def.desc

            if (state.done && state.unlockedAt != null) {
                b.textDate.visibility = View.VISIBLE
                b.textDate.text = getString(R.string.ach_unlocked_at, Dates.ymd(state.unlockedAt))
                b.checkBadge.visibility = View.VISIBLE
            } else {
                b.textDate.visibility = View.GONE
                b.checkBadge.visibility = View.GONE
            }

            b.bar.max = state.target.coerceAtLeast(1)
            b.bar.progress = state.progress
            b.bar.progressTintList = ColorStateList.valueOf(Palette.accent(ctx))
            b.bar.progressBackgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.muted),
            )
            b.textProgress.text = getString(R.string.ach_progress, state.progress, state.target)
            b.textProgress.setTextColor(
                if (state.done) Palette.accent(ctx)
                else ContextCompat.getColor(ctx, R.color.muted_fg),
            )
        }
    }

    private class SectionHolder(val text: TextView) : RecyclerView.ViewHolder(text)
    private class CardHolder(val binding: ItemAchievementBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * 分类图标。2.0 还没换统一图标库，这里先按分类各取一张 —— 54 条成就挤同一个图标太单调。
     * （「每条成就一个不同图标」是后续任务。）
     */
    private fun iconFor(category: Int): Int = when (category) {
        0 -> R.drawable.ic_map
        1 -> R.drawable.ic_trips
        2 -> R.drawable.ic_locate
        else -> R.drawable.ic_achievement
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TYPE_SECTION = 0
        const val TYPE_CARD = 1
    }
}
