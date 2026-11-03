package top.qxwkstudio.travel.ui

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
import top.qxwkstudio.travel.data.TripRepo
import top.qxwkstudio.travel.data.db.AppDatabase
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
    private var trips: List<Stats.Trip> = emptyList()
    private var rankBy = RankBy.COUNT
    private var seenVersion = -1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnRankCount.setOnClickListener {
            if (rankBy == RankBy.COUNT) return@setOnClickListener
            rankBy = RankBy.COUNT
            renderRank()
        }
        binding.btnRankTime.setOnClickListener {
            if (rankBy == RankBy.TIME) return@setOnClickListener
            rankBy = RankBy.TIME
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
        if (!hidden) load()
    }

    /** 从打卡/编辑页回来时本页没被 hide 过，靠版本号判断要不要重读（同阶段一的刷新链）。 */
    override fun onResume() {
        super.onResume()
        if (seenVersion != DataVersion.current) load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.runIo({
            val db = AppDatabase.get(requireContext())
            TripRepo.list(db) to CheckinRepo.list(db)
        }) { result ->
            val pair = result.getOrNull() ?: return@runIo
            val (tripEntities, checkins) = pair
            seenVersion = DataVersion.current
            trips = tripEntities.map { Stats.Trip(it.totalCostCents) }
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
        val overview = Stats.overview(entries, trips)
        val b = binding

        b.valueRange.text = Dates.range(overview.rangeStart, overview.rangeEnd) ?: getString(R.string.home_dash)
        b.valueCities.text = overview.cities.toString()
        b.valueProvinces.text = overview.provinces.toString()
        b.valueMost.text = overview.mostVisitedCity ?: getString(R.string.home_dash)
        b.valueCost.text = getString(R.string.home_cost_value, (overview.totalCostCents / 100).toString())

        b.textEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        renderRank()
    }

    // ────────────────────────── 排名 ──────────────────────────

    private fun renderRank() {
        val b = binding
        val onAccent = ContextCompat.getColor(requireContext(), R.color.on_accent)
        val secondary = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        b.btnRankCount.setTextColor(if (rankBy == RankBy.COUNT) onAccent else secondary)
        b.btnRankTime.setTextColor(if (rankBy == RankBy.TIME) onAccent else secondary)
        placeSegPill(animate = true)

        b.rankContainer.removeAllViews()
        val rows = Stats.rank(entries, rankBy)
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
        track.post {
            val available = track.width - track.paddingLeft - track.paddingRight
            if (available <= 0) return@post
            val half = available / 2
            val lp = pill.layoutParams
            if (lp.width != half) {
                lp.width = half
                pill.layoutParams = lp
            }
            val target = track.paddingLeft + if (rankBy == RankBy.COUNT) 0 else half
            if (animate) {
                pill.animate().translationX(target.toFloat()).setDuration(180L).start()
            } else {
                pill.animate().cancel()
                pill.translationX = target.toFloat()
            }
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
            setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (rank <= 3) R.color.on_accent else R.color.text_secondary,
                ),
            )
            textSize = 12f
            setBackgroundResource(R.drawable.bg_rank_circle)
            if (rank <= 3) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(Palette.accent(ctx))
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(24), dp(24))
        })

        line.addView(TextView(ctx).apply {
            text = row.city
            setTextColor(ContextCompat.getColor(ctx, R.color.fg))
            textSize = 14f
            setPadding(dp(10), 0, 0, 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        line.addView(TextView(ctx).apply {
            text = when (rankBy) {
                RankBy.COUNT -> getString(R.string.home_count_value, row.count.toString())
                RankBy.TIME -> Dates.ymd(row.lastVisitAt)
            }
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            textSize = 13f
        })
        return line
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
