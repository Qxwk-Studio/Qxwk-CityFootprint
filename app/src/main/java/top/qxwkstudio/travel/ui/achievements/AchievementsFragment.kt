package top.qxwkstudio.travel.ui.achievements

import top.qxwkstudio.travel.ui.common.*

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CheckinRepo
import top.qxwkstudio.travel.data.TripRepo
import top.qxwkstudio.travel.data.DataVersion
import top.qxwkstudio.travel.data.db.AchievementStateEntity
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.databinding.FragmentAchievementsBinding
import top.qxwkstudio.travel.databinding.ItemAchievementBinding
import top.qxwkstudio.travel.logic.Achievements
import top.qxwkstudio.travel.logic.Dates

/**
 * 成就页（2.0）：四个分类的滑动胶囊（占满整宽）+ 成就卡列表。
 *
 * 分类只有四个、没有「全部」：一屏只展示一个分类，胶囊平均分宽度，选中块用和底部导航
 * 同一条非线性曲线滑过去。
 *
 * 解锁进度作为**列表第一项**，跟着列表一起滚 —— 之前钉在顶部，小屏上一滚就只剩它和两三张卡。
 */
class AchievementsFragment : Fragment() {

    private var _binding: FragmentAchievementsBinding? = null
    private val binding get() = requireNotNull(_binding) { "binding 在视图生命周期外被访问" }

    private var selectedCategory: Int = 0

    private var states: Map<String, Achievements.State> = emptyMap()
    private var seenVersion = -1
    private val adapter = AchievementAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAchievementsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.fadeSlideIn()
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        buildChips()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            _binding?.root?.fadeSlideIn()
            load()
        }
    }

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
            val tripTimes = TripRepo.list(db).map { it.startTime }
            val evaluated = Achievements.evaluate(samples, tripTimes)

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
                refreshEmptyState()
                adapter.submit(buildRows())
            }
        }
    }

    // ────────────────────────── 分类胶囊 ──────────────────────────

    private fun buildChips() {
        val container = binding.chipContainer
        container.removeAllViews()
        Achievements.categories.forEachIndexed { index, category ->
            val chip = TextView(requireContext()).apply {
                text = category.title
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (index == selectedCategory) R.color.on_accent else R.color.text_secondary,
                    ),
                )
                isClickable = true
                setOnClickListener {
                    if (selectedCategory == index) return@setOnClickListener
                    selectedCategory = index
                    refreshChipColors()
                    moveIndicator(animate = true)
                    refreshEmptyState()
                    adapter.submit(buildRows())
                    binding.list.scrollToPosition(0)
                }
            }
            container.addView(chip)
        }
        container.post { moveIndicator(animate = false) }
    }

    private fun refreshChipColors() {
        val container = binding.chipContainer
        for (index in 0 until container.childCount) {
            val chip = container.getChildAt(index) as TextView
            chip.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (index == selectedCategory) R.color.on_accent else R.color.text_secondary,
                ),
            )
        }
    }

    /** 四等分；translationX 只按段宽走（FrameLayout 已按 padding 布局子 View）。 */
    private fun moveIndicator(animate: Boolean) {
        val track = binding.segTrack
        val indicator = binding.chipIndicator
        fun place() {
            val available = track.width - track.paddingLeft - track.paddingRight
            if (available <= 0) return
            val part = available / 4
            val lp = indicator.layoutParams
            if (lp.width != part) {
                lp.width = part
                indicator.layoutParams = lp
            }
            val target = part * selectedCategory
            if (animate) {
                indicator.animate()
                    .translationX(target.toFloat())
                    .setDuration(200L)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                    .start()
            } else {
                indicator.animate().cancel()
                indicator.translationX = target.toFloat()
            }
        }
        track.post { place() }
        track.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) place()
        }
    }

    // ────────────────────────── 列表 ──────────────────────────

    /** 新用户也要看到全部未解锁成就，不因「0 进度」把列表藏起来。 */
    private fun refreshEmptyState() {
        binding.textEmpty.visibility = View.GONE
        binding.list.visibility = View.VISIBLE
    }

    private sealed interface Row {
        data class Header(val title: String, val done: Int, val total: Int) : Row
        data class Card(
            val category: Int,
            val def: Achievements.AchievementDef,
            val state: Achievements.State,
        ) : Row
    }

    private fun buildRows(): List<Row> {
        val categories = Achievements.categories
        val index = selectedCategory
        val codes = categories[index].items.map { it.code }
        val done = codes.count { states[it]?.done == true }
        val rows = ArrayList<Row>(16)
        rows.add(Row.Header(categories[index].title, done, codes.size))
        categories[index].items.forEach { def ->
            val state = states[def.code] ?: return@forEach
            rows.add(Row.Card(index, def, state))
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
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_CARD

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val context = parent.context
            return if (viewType == TYPE_HEADER) {
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(10) }
                }
                val titleRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val title = TextView(context).apply {
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.fg))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val percent = TextView(context).apply {
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                titleRow.addView(title)
                titleRow.addView(percent)
                card.addView(titleRow)

                val barRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                }
                val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(6), 1f)
                    progressDrawable = ContextCompat.getDrawable(context, R.drawable.progress_rounded)
                }
                val barPercent = TextView(context).apply {
                    // 百分比按标题字号 + 主题色，作为这一行的强调数字
                    textSize = 15f
                    setTextColor(Palette.accent(context))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(8) }
                }
                barRow.addView(bar)
                barRow.addView(barPercent)
                card.addView(barRow)
                HeaderHolder(card, title, percent, bar, barPercent)
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
                is Row.Header -> {
                    val h = holder as HeaderHolder
                    h.title.text = row.title
                    h.percent.text = getString(R.string.ach_unlocked_count, row.done, row.total)
                    h.bar.max = row.total.coerceAtLeast(1)
                    h.bar.progress = row.done
                    h.barPercent.text = getString(
                        R.string.ach_category_percent,
                        row.done * 100 / row.total.coerceAtLeast(1),
                    )
                }
                is Row.Card -> bindCard(holder as CardHolder, row)
            }
        }

        private fun bindCard(holder: CardHolder, row: Row.Card) {
            val ctx = requireContext()
            val b = holder.binding
            val state = row.state
            b.root.alpha = if (state.done) 1f else 0.55f
            val milestone = when (val rule = row.def.rule) {
                is Achievements.Rule.CityCount -> rule.min
                is Achievements.Rule.CheckinCount -> rule.min
                is Achievements.Rule.TripCount -> rule.min
                else -> null
            }
            if (row.category == 0 && milestone != null) {
                b.icon.visibility = View.GONE
                b.textMilestone.visibility = View.VISIBLE
                b.textMilestone.text = milestone.toString()
            } else {
                b.icon.visibility = View.VISIBLE
                b.textMilestone.visibility = View.GONE
                b.icon.setImageResource(iconFor(row.def.code, row.category))
            }
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
            b.textProgress.text = getString(R.string.ach_progress, state.progress, state.target)
            b.textProgress.setTextColor(
                if (state.done) Palette.accent(ctx)
                else ContextCompat.getColor(ctx, R.color.muted_fg),
            )
        }
    }

    private class HeaderHolder(
        root: View,
        val title: TextView,
        val percent: TextView,
        val bar: ProgressBar,
        val barPercent: TextView,
    ) : RecyclerView.ViewHolder(root)

    private class CardHolder(val binding: ItemAchievementBinding) : RecyclerView.ViewHolder(binding.root)

    /** 逐条成就图标：按主题分组，允许同类共用，不再整类同一张图。 */
    private val achievementIcons = mapOf(
        // 城市打卡
        "city_beijing" to R.drawable.ic_palace,
        "city_shijiazhuang" to R.drawable.ic_food,
        "city_handan" to R.drawable.ic_flag,
        "city_dalian" to R.drawable.ic_waves,
        "city_dandong" to R.drawable.ic_waves,
        "city_harbin" to R.drawable.ic_snow,
        "city_shanghai" to R.drawable.ic_city,
        "city_suzhou" to R.drawable.ic_palace,
        "city_xuzhou" to R.drawable.ic_flag,
        "city_hangzhou" to R.drawable.ic_waves,
        "city_huangshan" to R.drawable.ic_mountain,
        "city_xiamen" to R.drawable.ic_waves,
        "city_nanchang" to R.drawable.ic_palace,
        "city_jingdezhen" to R.drawable.ic_palette,
        "city_qingdao" to R.drawable.ic_waves,
        "city_weifang" to R.drawable.ic_wind,
        "city_luoyang" to R.drawable.ic_palace,
        "city_kaifeng" to R.drawable.ic_palace,
        "city_xinxiang" to R.drawable.ic_city,
        "city_wuhan" to R.drawable.ic_palace,
        "city_yichang" to R.drawable.ic_waves,
        "city_changsha" to R.drawable.ic_food,
        "city_zhangjiajie" to R.drawable.ic_mountain,
        "city_yueyang" to R.drawable.ic_palace,
        "city_chaozhou" to R.drawable.ic_food,
        "city_foshan" to R.drawable.ic_factory,
        "city_guilin" to R.drawable.ic_mountain,
        "city_liuzhou" to R.drawable.ic_food,
        "city_sanya" to R.drawable.ic_waves,
        "city_chongqing" to R.drawable.ic_mountain,
        "city_chengdu" to R.drawable.ic_food,
        "city_kunming" to R.drawable.ic_sun,
        "city_dali" to R.drawable.ic_mountain,
        "city_lijiang" to R.drawable.ic_palace,
        "city_lhasa" to R.drawable.ic_sun,
        "city_xian" to R.drawable.ic_palace,
        "city_yanan" to R.drawable.ic_redstar,
        "city_urumqi" to R.drawable.ic_food,
        "city_kashi" to R.drawable.ic_temple,
        // 巡游四方
        "coastal_city" to R.drawable.ic_waves,
        "special_zone" to R.drawable.ic_city,
        "grand_canal" to R.drawable.ic_waves,
        "jiangnan_all" to R.drawable.ic_waves,
        "great_wall" to R.drawable.ic_wall,
        "silk_road" to R.drawable.ic_compass,
        "space_launch" to R.drawable.ic_rocket,
        "island_city" to R.drawable.ic_waves,
        "plateau_city" to R.drawable.ic_mountain,
        "metro_all" to R.drawable.ic_city,
        "municipality_all" to R.drawable.ic_city,
        "prov_capitals_all" to R.drawable.ic_palace,
        "hk_macau" to R.drawable.ic_city,
        "ancient_capitals" to R.drawable.ic_palace,
        "northeast_all" to R.drawable.ic_snow,
        "five_mountains" to R.drawable.ic_mountain,
        "four_grottoes" to R.drawable.ic_temple,
        "buddhist_mountains" to R.drawable.ic_temple,
        "red_sites" to R.drawable.ic_redstar,
        "rehe_province" to R.drawable.ic_tree,
        // 极限挑战
        "east_end_jiamusi" to R.drawable.ic_compass,
        "south_end_sansha" to R.drawable.ic_compass,
        "west_end_kezilesu" to R.drawable.ic_compass,
        "north_end_daxinganling" to R.drawable.ic_compass,
        "arid_turpan" to R.drawable.ic_desert,
        "rainiest_yaan" to R.drawable.ic_rain,
        "sunny_baotou" to R.drawable.ic_sun,
        "hottest_turpan" to R.drawable.ic_sun,
        "coldest_hulunbuir" to R.drawable.ic_snow,
        "highest_naqu" to R.drawable.ic_mountain,
        "lowest_turpan" to R.drawable.ic_mountain,
        "windy_alashankou" to R.drawable.ic_wind,
        "dusty_akesu" to R.drawable.ic_desert,
    )

    private fun iconFor(code: String, category: Int): Int =
        achievementIcons[code] ?: iconFor(category)

    private fun iconFor(category: Int): Int = when (category) {
        0 -> R.drawable.ic_achievement
        1 -> R.drawable.ic_stamp
        2 -> R.drawable.ic_compass
        else -> R.drawable.ic_peak
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CARD = 1
    }
}
