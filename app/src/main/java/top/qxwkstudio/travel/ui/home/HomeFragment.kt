package top.qxwkstudio.travel.ui.home

import top.qxwkstudio.travel.ui.common.*

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CheckinRepo
import top.qxwkstudio.travel.data.DataVersion
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.CheckinSource
import top.qxwkstudio.travel.databinding.FragmentHomeBinding
import top.qxwkstudio.travel.logic.Dates
import top.qxwkstudio.travel.logic.Stats
import top.qxwkstudio.travel.logic.Stats.RankBy

/**
 * 主页（2.0）：足迹统计（五张卡）+ 城市排名（一张卡的行列表 + 滑动胶囊筛选）。
 *
 * 数据全部来自本地库（[CheckinRepo] + [TripRepo]），一次读两份快照、在内存里算 ——
 * [Stats] 是纯函数，几百条数据的聚合不值得再开线程。
 *
 * 排名切换只重排、不重查；筛选控件的选中块会**滑动**过去（见 [placeSegPill]），
 * 比两个文字变色更能表达「这是一组二选一的开关」。
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var entries: List<Stats.Entry> = emptyList()
    private var rankBy = RankBy.COUNT
    /** 城市 → 花销（分）/ 停留天数，来自所有行程的城市段。 */
    private var cityAmounts: Map<String, Int> = emptyMap()

    /** 主页「总花销」：所有打卡的金额合计（派生打卡已镜像行程金额）。 */
    private var checkinCostCents: Int = 0
    private var cityDays: Map<String, Int> = emptyMap()
    private var cityRatings: Map<String, Float> = emptyMap()
    private var totalDays: Int = 0
    private var seenVersion = -1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.fadeSlideIn()
        binding.btnRankCount.setOnClickListener {
            if (rankBy == RankBy.COUNT) return@setOnClickListener
            rankBy = RankBy.COUNT
            renderRank()
        }
        binding.btnRankAmount.setOnClickListener {
            if (rankBy == RankBy.AMOUNT) return@setOnClickListener
            rankBy = RankBy.AMOUNT
            renderRank()
        }
        binding.btnRankTime.setOnClickListener {
            if (rankBy == RankBy.TIME) return@setOnClickListener
            rankBy = RankBy.TIME
            renderRank()
        }
        binding.btnRankRating.setOnClickListener {
            if (rankBy == RankBy.RATING) return@setOnClickListener
            rankBy = RankBy.RATING
            renderRank()
        }
        load()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** 切回本 tab 时重读：本页在别的 tab 显示时仍是 RESUMED（见 MainActivity 的 add/hide/show）。 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) { _binding?.root?.fadeSlideIn(); load() }
    }

    /** 从打卡/编辑页回来时本页没被 hide 过，靠版本号判断要不要重读（同阶段一的刷新链）。 */
    override fun onResume() {
        super.onResume()
        if (seenVersion != DataVersion.current) load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.runIo({
            val db = AppDatabase.get(requireContext())
            CheckinRepo.list(db)
        }) { result ->
            val checkins = result.getOrNull() ?: return@runIo
        // 每个城市的花销与停留天数：全部来自打卡（手动 + 派生）。
        // 派生打卡已经镜像了行程段，所以不需要再读行程时间线，口径也就只有一套。
        val amounts = mutableMapOf<String, Int>()
        val days = mutableMapOf<String, Int>()
        checkins.forEach { checkin ->
            checkin.amountCents?.let { amounts[checkin.cityName] = (amounts[checkin.cityName] ?: 0) + it }
            checkin.stayDays?.let { days[checkin.cityName] = (days[checkin.cityName] ?: 0) + it }
        }
        cityAmounts = amounts
        cityDays = days
        totalDays = days.values.sum()
        cityRatings = checkins
            .filter { it.rating != null }
            .groupBy { it.cityName }
            .mapValues { (_, list) -> list.sumOf { it.rating ?: 0 }.toFloat() / list.size }
            seenVersion = DataVersion.current
            entries = checkins.map {
                Stats.Entry(
                    cityName = it.cityName,
                    province = it.province,
                    checkinAt = it.checkinAt,
                    datePrecision = it.datePrecision,
                )
            }
            render()
        }
    }

    private fun render() {
        val overview = Stats.overview(entries)
        val b = binding

        b.valueRange.text = Dates.range(overview.rangeStart, overview.rangeEnd) ?: getString(R.string.home_dash)
        b.valueCities.text = getString(R.string.home_cities_provinces, overview.cities, overview.provinces)
        b.valueMost.text = overview.mostVisitedCity ?: getString(R.string.home_dash)
        b.valueDays.text = getString(R.string.home_days_value, totalDays.toString())
        b.valueCost.text = getString(R.string.home_cost_value, (checkinCostCents / 100).toString())

        b.textEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        renderRank()
    }

    // ────────────────────────── 排名 ──────────────────────────

    private fun renderRank() {
        val b = binding
        val onAccent = ContextCompat.getColor(requireContext(), R.color.on_accent)
        val secondary = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        b.btnRankCount.setTextColor(if (rankBy == RankBy.COUNT) onAccent else secondary)
        b.btnRankAmount.setTextColor(if (rankBy == RankBy.AMOUNT) onAccent else secondary)
        b.btnRankTime.setTextColor(if (rankBy == RankBy.TIME) onAccent else secondary)
        b.btnRankRating.setTextColor(if (rankBy == RankBy.RATING) onAccent else secondary)
        placeSegPill(animate = true)

        b.rankContainer.removeAllViews()
        val rows = Stats.rank(entries, rankBy, cityAmounts, cityDays, cityRatings)
        b.rankCard.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        rows.forEachIndexed { index, row ->
            b.rankContainer.addView(rankRow(index + 1, row))
            // 行间 1px 横线（与 1.x 的排行卡一致）；最后一行的下面不加
            if (index != rows.lastIndex) b.rankContainer.addView(divider())
        }
    }

    /**
     * 把选中块滑到当前选项下面。
     *
     * 轨道有 3dp padding：可分配宽度是「轨道宽 - 左右 padding」，一半给「次数」、一半给「时间」。
     * 划动用 180ms，与屏幕其它过渡同档；首次进来直接落位（不播动画），免得看见它从左边滑过去。
     */
    private fun placeSegPill(animate: Boolean) {
        val b = binding
        val track = b.segRank
        val pill = b.segPill
        fun place() {
            val available = track.width - track.paddingLeft - track.paddingRight
            if (available <= 0) return
            val part = available / 4
            val lp = pill.layoutParams
            if (lp.width != part) {
                lp.width = part
                pill.layoutParams = lp
            }
            // FrameLayout 的子 View 已经在 padding 内布局，translationX 只按段宽走；
            // 再加 paddingLeft 会让左侧偏、右侧被裁掉。
            val target = part * rankBy.ordinal
            if (animate) {
                pill.animate().translationX(target.toFloat()).setDuration(200L)
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                    .start()
            } else {
                pill.animate().cancel()
                pill.translationX = target.toFloat()
            }
        }
        // 首次进入时 width 可能还是 0（add + 布局同一帧），post 里取不到就靠这次布局回调兜住；
        // 之后旋转/分屏改变宽度同样会重新落位。
        track.post { place() }
        track.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) place()
        }
    }

    private fun divider(): View = View(requireContext()).apply {
        setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.border))
        layoutParams = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        }
    }

    /**
     * 名次行：名次圆 + 城市 + 维度值。行内容用代码拼（同前：几十行、结构固定）。
     */
    private fun rankRow(rank: Int, row: Stats.CityRow): View {
        val ctx = requireContext()
        val line = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        line.addView(TextView(ctx).apply {
            text = rank.toString()
            gravity = android.view.Gravity.CENTER
            textSize = 12f
            setBackgroundResource(R.drawable.bg_rank_circle)
            val medal = when (rank) {
                1 -> R.color.rank_gold
                2 -> R.color.rank_silver
                3 -> R.color.rank_bronze
                else -> 0
            }
            if (medal != 0) {
                setTextColor(ContextCompat.getColor(ctx, R.color.on_accent))
                backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, medal))
            } else {
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(24), dp(24))
        })

        val cityText = android.text.SpannableStringBuilder(row.city)
        if (row.province.isNotBlank()) {
            cityText.append(" · ").append(row.province)
            cityText.setSpan(
                android.text.style.ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.text_tertiary)),
                row.city.length, cityText.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            // 省份不只是换色，字号也小一号（0.82 倍）
            cityText.setSpan(
                android.text.style.RelativeSizeSpan(0.82f),
                row.city.length, cityText.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        line.addView(TextView(ctx).apply {
            text = cityText
            setTextColor(ContextCompat.getColor(ctx, R.color.fg))
            textSize = 14f
            setPadding(dp(10), 0, 0, 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        line.addView(TextView(ctx).apply {
            text = when (rankBy) {
                RankBy.COUNT -> getString(R.string.home_count_value, row.count.toString())
                RankBy.AMOUNT -> getString(R.string.home_money_value, (row.amountCents / 100).toString())
                RankBy.TIME -> getString(R.string.home_days_value, row.stayDays.toString())
                RankBy.RATING -> getString(R.string.home_rating_value, String.format(java.util.Locale.US, "%.1f", row.rating))
            }
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            textSize = 13f
        })
        return line
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
