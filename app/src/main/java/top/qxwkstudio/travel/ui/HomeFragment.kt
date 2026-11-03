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
 * 主页（2.0）：五张统计卡 + 城市排名。
 *
 * 数据全部来自本地库（[CheckinRepo] + [TripRepo]），一次读两份快照，然后在内存里算 ——
 * [Stats] 是纯函数，算这几百条数据在主线程也只是一次聚合，不值得再开线程。
 *
 * 排名有两个维度（次数 / 时间），切换只重排、不重查：两份快照已经拿在手上。
 * 刻意**没有**「花费」维度，理由见 [Stats] 的类注释。
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var entries: List<Stats.Entry> = emptyList()
    private var trips: List<Stats.Trip> = emptyList()
    private var rankBy = RankBy.COUNT

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

    /**
     * 从打卡/编辑页回来时也要重读：那一刻本页一直是 RESUMED（切页是 hide/show），
     * 只靠 onHiddenChanged 收不到通知。用 [DataVersion] 判断有没有写过数据，
     * 没写过就不白刷一次。
     */
    override fun onResume() {
        super.onResume()
        if (seenVersion != DataVersion.current) load()
    }

    private var seenVersion = -1

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
                    // 库里的精度常量与 logic/DatePrecision 是同一组值（data 依赖 logic）
                    datePrecision = it.datePrecision,
                )
            }
            render()        }
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

    private fun renderRank() {
        val b = binding
        // 选中的维度用主色，另一个用次要色 —— 只有两种状态，不值得为它写 selector
        val accent = Palette.accent(requireContext())
        val secondary = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        b.btnRankCount.setTextColor(if (rankBy == RankBy.COUNT) accent else secondary)
        b.btnRankTime.setTextColor(if (rankBy == RankBy.TIME) accent else secondary)

        b.rankContainer.removeAllViews()
        val rows = Stats.rank(entries, rankBy)
        rows.forEachIndexed { index, row ->
            b.rankContainer.addView(rankRow(index + 1, row))
        }
    }

    /**
     * 名次行：名次圆 + 城市（+省份）+ 维度值。
     *
     * 行内容用代码拼而不是布局 + Adapter：排名最多几十行、结构就三个控件，
     * 为它配一套 ViewHolder 反而多一层间接（行程页那种会随筛选变化的列表才值得上 RecyclerView）。
     */
    private fun rankRow(rank: Int, row: Stats.CityRow): View {
        val ctx = requireContext()
        val line = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
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
            // 前三名用主色实心圆：改 tint 而不是换 drawable —— 直接 setTint 会改到共享的那份
            // Drawable 缓存（所有名次圆会一起变色），backgroundTintList 只作用于这个 View
            if (rank <= 3) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Palette.accent(ctx),
                )
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(22), dp(22))
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
            textSize = 12f
        })
        return line
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
