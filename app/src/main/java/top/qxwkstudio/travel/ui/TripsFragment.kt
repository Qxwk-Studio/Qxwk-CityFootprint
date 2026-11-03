package top.qxwkstudio.travel.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CheckinRepo
import top.qxwkstudio.travel.data.DataVersion
import top.qxwkstudio.travel.data.TripRepo
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.CheckinEntity
import top.qxwkstudio.travel.data.db.CheckinSource
import top.qxwkstudio.travel.data.db.TripEntity
import top.qxwkstudio.travel.databinding.FragmentTripsBinding
import top.qxwkstudio.travel.logic.DatePrecision
import top.qxwkstudio.travel.logic.Dates
import top.qxwkstudio.travel.logic.Timeline

/**
 * 行程页（2.0）：分段控件「打卡 (N) / 足迹 (N)」，两段各自按时间倒序。
 *
 * 打卡那一段里**关联打卡与独立打卡是混排的**（2.0 设计文档 §2）：关联打卡底部标「来自足迹 #17」、
 * 没有编辑入口；独立打卡标「手动打卡」。两种来源共用 `city_checkins` 一张表，
 * 这里只是按 [CheckinEntity.source] 决定底部那行文案。
 *
 * 行内容用代码铺（与主页排名同一套理由）：一屏几十行、结构固定，
 * 上 RecyclerView 要配两套 Adapter 换不到可感知的收益。等有「长按拖动排序」之类需求再换。
 */
class TripsFragment : Fragment() {

    private var _binding: FragmentTripsBinding? = null
    private val binding get() = _binding!!

    private enum class Segment { CHECKINS, TRIPS }

    private var segment = Segment.CHECKINS
    private var trips: List<TripEntity> = emptyList()
    private var checkins: List<CheckinEntity> = emptyList()

    /** 行程 uuid → `#编号`：关联打卡底部要写「来自足迹 #17」。 */
    private var seqByUuid: Map<String, Int> = emptyMap()

    /** 行程 uuid → 时间线节点数：卡片上「N 个时间线」。 */
    private var timelineCountByUuid: Map<String, Int> = emptyMap()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTripsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnSegCheckins.setOnClickListener {
            if (segment == Segment.CHECKINS) return@setOnClickListener
            segment = Segment.CHECKINS
            render()
        }
        binding.btnSegTrips.setOnClickListener {
            if (segment == Segment.TRIPS) return@setOnClickListener
            segment = Segment.TRIPS
            render()
        }
        load()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) load()
    }

    /** 从打卡页回来时本页没被 hide 过，靠版本号判断要不要重读（同 HomeFragment）。 */
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
            val (tripList, checkinList) = result.getOrNull() ?: return@runIo
            seenVersion = DataVersion.current
            trips = tripList
            checkins = checkinList
            seqByUuid = tripList.associate { it.uuid to it.seqNo }
            timelineCountByUuid = tripList.associate { trip ->
                // 解析失败的时间线按 0 个节点显示，不让一行坏数据把整页拖崩
                trip.uuid to (Timeline.decode(trip.timeline)?.events?.size ?: 0)
            }
            render()
        }
    }

    private fun render() {
        val b = binding
        val accent = Palette.accent(requireContext())
        val secondary = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        b.btnSegCheckins.text = getString(R.string.trips_seg_checkins, checkins.size)
        b.btnSegTrips.text = getString(R.string.trips_seg_trips, trips.size)
        b.btnSegCheckins.setTextColor(if (segment == Segment.CHECKINS) accent else secondary)
        b.btnSegTrips.setTextColor(if (segment == Segment.TRIPS) accent else secondary)

        b.listContainer.removeAllViews()
        val empty = when (segment) {
            Segment.CHECKINS -> checkins.isEmpty()
            Segment.TRIPS -> trips.isEmpty()
        }
        b.textEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) return

        when (segment) {
            Segment.CHECKINS -> checkins.forEach { b.listContainer.addView(checkinRow(it)) }
            Segment.TRIPS -> trips.forEach { b.listContainer.addView(tripRow(it)) }
        }
    }

    /** 打卡卡片：城市名 + 日期（按精度显示）+ 来源那行。 */
    private fun checkinRow(checkin: CheckinEntity): View {
        val ctx = requireContext()
        val derived = checkin.source == CheckinSource.DERIVED
        val (root, card) = cardWithStripe(if (derived) R.color.stripe_derived else R.color.stripe_manual)

        // 第一行：城市 + 「更多」。关联打卡是派生物，菜单里只给分享（2.0 设计文档 的权限差异）
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = checkin.cityName
            setTextColor(ContextCompat.getColor(ctx, R.color.fg))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(moreButton { anchor ->
            showMenu(
                anchor,
                onEdit = if (derived) null else { { startActivity(CheckinEditActivity.intent(ctx, checkin.id)) } },
                onDelete = if (derived) null else { { confirmDeleteCheckin(checkin) } },
            )
        })
        card.addView(header)

        card.addView(TextView(ctx).apply {
            text = dateText(checkin)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        })

        // 来源行：派生打卡写它属于哪条行程，手动打卡写「手动打卡」
        card.addView(TextView(ctx).apply {
            text = if (derived) {
                val seq = checkin.tripUuid?.let { seqByUuid[it] }
                // 注意：`trips_checkin_derived` 是 %1$d，参数必须传 Int。
                // 传 toString() 会抛 IllegalFormatConversionException —— 一点开这个 tab 就闪退。
                if (seq != null) getString(R.string.trips_checkin_derived, seq)
                else getString(R.string.trips_checkin_manual)
            } else {
                getString(R.string.trips_checkin_manual)
            }
            setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        })
        return root
    }

    /** 行程卡片：`#编号` + 标题 / 起始日期 + 时间线节点数。 */
    private fun tripRow(trip: TripEntity): View {
        val ctx = requireContext()
        val (root, card) = cardWithStripe(R.color.stripe_trip)

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = getString(R.string.trips_trip_seq, trip.seqNo)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
            textSize = 12f
        })
        header.addView(TextView(ctx).apply {
            text = trip.title.ifBlank { getString(R.string.trips_trip_empty_title) }
            setTextColor(ContextCompat.getColor(ctx, R.color.fg))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(moreButton { anchor ->
            showMenu(
                anchor,
                onEdit = { startActivity(TripEditActivity.intent(ctx, trip.id)) },
                onDelete = { confirmDeleteTrip(trip) },
            )
        })
        card.addView(header)

        card.addView(TextView(ctx).apply {
            // 同样是 %1$d：传 Int，不是 toString()
            text = getString(
                R.string.trips_trip_timeline,
                timelineCountByUuid[trip.uuid] ?: 0,
            ) + " · " + Dates.ymd(trip.startTime)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        })

        // 整卡可点：进编辑器（与 `...` 菜单里的「编辑」等价，卡片比三点好点）
        root.isClickable = true
        root.setOnClickListener { startActivity(TripEditActivity.intent(ctx, trip.id)) }
        return root
    }

    /**
     * 卡片外壳：左侧一条贯穿竖条 + 内容区。
     *
     * 竖条颜色按类别固定（足迹蓝 / 独立打卡绿 / 关联打卡灰，见 colors_2.xml），
     * 左侧圆角靠 bg_card_stripe 的形状，与卡片的圆角对齐。
     */
    private fun cardWithStripe(stripeRes: Int): Pair<LinearLayout, LinearLayout> {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        root.addView(View(ctx).apply {
            setBackgroundResource(R.drawable.bg_card_stripe)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, stripeRes),
            )
            layoutParams = LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT)
        })
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        root.addView(content)
        return root to content
    }

    private fun moreButton(onClick: (View) -> Unit): View =
        android.widget.ImageButton(requireContext()).apply {
            setImageResource(R.drawable.ic_more)
            background = null
            setColorFilter(ContextCompat.getColor(requireContext(), R.color.text_tertiary))
            contentDescription = getString(R.string.common_menu)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener(onClick)
        }

    /**
     * 卡片的 `...` 菜单：分享（占位）/ 编辑 / 删除。
     * [onEdit] 与 [onDelete] 传 null 表示这一类不给那个入口（关联打卡就只能分享）。
     */
    private fun showMenu(
        anchor: View,
        onEdit: (() -> Unit)? = null,
        onDelete: (() -> Unit)? = null,
    ) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(android.view.Menu.NONE, 1, 0, R.string.trips_menu_share)
        if (onEdit != null) popup.menu.add(android.view.Menu.NONE, 2, 1, R.string.trips_menu_edit)
        if (onDelete != null) popup.menu.add(android.view.Menu.NONE, 3, 2, R.string.trips_menu_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> requireContext().notify(R.string.trips_share_wip)
                2 -> onEdit?.invoke()
                3 -> onDelete?.invoke()
            }
            true
        }
        popup.show()
    }

    private fun confirmDeleteTrip(trip: TripEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trips_delete_title)
            .setMessage(getString(R.string.trips_delete_message, trip.title.ifBlank { getString(R.string.trips_trip_empty_title) }))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.runIo({
                    TripRepo.delete(AppDatabase.get(requireContext()), trip.id)
                }) { result ->
                    if (result.isSuccess) requireContext().notify(R.string.trips_deleted)
                    else requireContext().notify(R.string.common_error, long = true)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmDeleteCheckin(checkin: CheckinEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.checkin_delete_title)
            .setMessage(getString(R.string.trips_delete_message, checkin.cityName))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.runIo({
                    CheckinRepo.delete(AppDatabase.get(requireContext()), checkin.id)
                }) { result ->
                    if (result.isSuccess) requireContext().notify(R.string.trips_deleted)
                    else requireContext().notify(R.string.common_error, long = true)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 日期按精度显示：只记得年就只给年，不要补一个假的 1 月 1 日出去。 */
    private fun dateText(checkin: CheckinEntity): String = when (checkin.datePrecision) {
        DatePrecision.YEAR -> Dates.year(checkin.checkinAt)
        DatePrecision.MONTH -> Dates.dotMonth(checkin.checkinAt)
        DatePrecision.UNKNOWN -> Dates.year(checkin.checkinAt)
        else -> Dates.ymd(checkin.checkinAt)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
