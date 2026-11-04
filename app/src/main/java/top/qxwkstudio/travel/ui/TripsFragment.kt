package top.qxwkstudio.travel.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
 * 行程页（2.0）：分段控件「打卡 (N) / 足迹 (N)」+ 卡片列表。
 *
 * 卡片结构（两种卡共用一套读法：左边一条类别色竖条 → 第一行是「编号徽章 + 标题 + 右侧菜单」，
 * 第二行是「备注 / 时间」这类次要信息）：
 *  - 打卡：编号徽章 + 城市名；第二行左备注、右时间
 *  - 足迹：编号徽章 + 标题 + 花费徽章；第二行左备注、右「时间线数 · 时间」
 *
 * 竖条用 `clipToOutline` 裁到卡片圆角里 —— 之前它按方角画，会从圆角处露出一点，看着像「超出卡片」。
 * 菜单用 [showIconMenu]（自绘、带图标、宽度 wrap），与主页「+」是同一套浮层。
 */
class TripsFragment : Fragment() {

    private var _binding: FragmentTripsBinding? = null
    private val binding get() = _binding!!

    private enum class Segment { CHECKINS, TRIPS }

    private var segment = Segment.CHECKINS
    private var trips: List<TripEntity> = emptyList()
    private var checkins: List<CheckinEntity> = emptyList()

    /** 行程 uuid → `#编号`（派生打卡的徽章要显示它所属的行程编号）。 */
    private var seqByUuid: Map<String, Int> = emptyMap()

    /** 行程 uuid → (到访城市数, 活动数)，卡片第二行用来显示「N 城 · M 项」。 */
    private var timelineCountByUuid: Map<String, Pair<Int, Int>> = emptyMap()

    private var seenVersion = -1

    /** 每座城市「最早那条打卡」的 id：表格里的首访角标与金色边框按它判断。 */
    private var firstVisitIds: Set<Long> = emptySet()

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
            firstVisitIds = checkinList
                .groupBy { it.cityName }
                .values
                .mapNotNull { group -> group.minWithOrNull(compareBy<CheckinEntity> { it.checkinAt }.thenBy { it.id })?.id }
                .toSet()
            timelineCountByUuid = tripList.associate { trip ->
                val timeline = Timeline.decode(trip.timeline)
                trip.uuid to ((timeline?.cityCount ?: 0) to (timeline?.eventCount ?: 0))
            }
            render()
        }
    }

    private fun render() {
        val b = binding
        b.btnSegCheckins.text = getString(R.string.trips_seg_checkins, checkins.size)
        b.btnSegTrips.text = getString(R.string.trips_seg_trips, trips.size)
        Chips.styleAsPill(b.btnSegCheckins, requireContext(), segment == Segment.CHECKINS)
        Chips.styleAsPill(b.btnSegTrips, requireContext(), segment == Segment.TRIPS)

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

    // ────────────────────────── 卡片 ──────────────────────────

    private fun checkinRow(checkin: CheckinEntity): View {
        val derived = checkin.source == CheckinSource.DERIVED
        val stripe = if (derived) R.color.stripe_derived else R.color.stripe_manual
        val first = checkin.id in firstVisitIds
        val (root, card) = cardWithStripe(stripe, firstVisit = first)

        // 第一行：编号徽章 + 城市名 + 菜单
        val badgeSeq = if (derived) {
            checkin.tripUuid?.let { seqByUuid[it] } ?: 0
        } else {
            checkin.seqNo
        }
        val header = row()
        header.addView(numberBadge(badgeSeq, stripe))
        header.addView(titleView(checkin.cityName))
        if (first) header.addView(firstVisitChip())
        header.addView(menuButton { anchor ->
            showIconMenu(
                anchor,
                buildList {
                    add(MenuAction(R.drawable.ic_share, R.string.trips_menu_share) {
                        requireContext().notify(R.string.trips_share_wip)
                    })
                    // 关联打卡是行程派生物：只给分享（改它等于改行程）
                    if (!derived) {
                        add(MenuAction(R.drawable.ic_edit, R.string.trips_menu_edit) {
                            startActivity(CheckinEditActivity.intent(requireContext(), checkin.id))
                        })
                        add(MenuAction(R.drawable.ic_delete, R.string.trips_menu_delete) {
                            confirmDeleteCheckin(checkin)
                        })
                    }
                },
            )
        })
        card.addView(header)

        // 第二行：左备注、右时间
        val meta = row(topMargin = 8)
        val note = noteView(checkin.note)
        if (note != null) meta.addView(note, weightParams()) else meta.addView(spacer())
        meta.addView(iconText(R.drawable.ic_span, dateText(checkin)))
        card.addView(meta)

        // 第三行：照片 / 同行人 / 评分（有哪项就显示哪项）
        val extras = checkinExtras(checkin)
        if (extras != null) card.addView(extras)

        root.isClickable = !derived
        if (!derived) root.setOnClickListener {
            startActivity(CheckinEditActivity.intent(requireContext(), checkin.id))
        }
        return root
    }

    private fun tripRow(trip: TripEntity): View {
        val (root, card) = cardWithStripe(R.color.stripe_trip)

        // 第一行：编号徽章 + 标题 + 花费徽章 + 菜单
        val header = row()
        header.addView(numberBadge(trip.seqNo, R.color.stripe_trip))
        header.addView(titleView(trip.title.ifBlank { getString(R.string.trips_trip_empty_title) }))
        if (trip.totalCostCents > 0) header.addView(costBadge(trip.totalCostCents))
        header.addView(menuButton { anchor ->
            showIconMenu(
                anchor,
                listOf(
                    MenuAction(R.drawable.ic_share, R.string.trips_menu_share) {
                        requireContext().notify(R.string.trips_share_wip)
                    },
                    MenuAction(R.drawable.ic_edit, R.string.trips_menu_edit) {
                        startActivity(TripEditActivity.intent(requireContext(), trip.id))
                    },
                    MenuAction(R.drawable.ic_delete, R.string.trips_menu_delete) {
                        confirmDeleteTrip(trip)
                    },
                ),
            )
        })
        card.addView(header)

        // 第二行：左备注、右「时间线数 · 时间」
        val meta = row(topMargin = 8)
        val note = noteView(trip.note)
        if (note != null) meta.addView(note, weightParams()) else meta.addView(spacer())
        val counts = timelineCountByUuid[trip.uuid] ?: (0 to 0)
        meta.addView(iconText(R.drawable.ic_trips, "${counts.first} 城 · ${counts.second} 项"))
        meta.addView(iconText(R.drawable.ic_span, Dates.ymd(trip.startTime), marginStart = 10))
        card.addView(meta)

        root.isClickable = true
        root.setOnClickListener {
            startActivity(TripEditActivity.intent(requireContext(), trip.id))
        }
        return root
    }

    /**
     * 卡片外壳：左侧一条贯穿竖条 + 内容区。
     *
     * 竖条颜色按类别固定（足迹蓝 / 独立打卡绿 / 关联打卡灰，见 colors_2.xml）。
     * `clipToOutline` 把整张卡裁成圆角轮廓，竖条不会再从圆角处露出来。
     */
    private fun cardWithStripe(stripeRes: Int, firstVisit: Boolean = false): Pair<LinearLayout, LinearLayout> {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // 首访卡换成金色描边（与「首访」角标同一套视觉）
            setBackgroundResource(if (firstVisit) R.drawable.bg_card_first else R.drawable.bg_card)
            clipToOutline = true
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

    // ────────────────────────── 小部件 ──────────────────────────

    private fun row(topMargin: Int = 0): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (topMargin > 0) setPadding(0, dp(topMargin), 0, 0)
    }

    /** 编号徽章：类别色底、白字、圆角 6dp。 */
    private fun numberBadge(seq: Int, colorRes: Int): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = getString(R.string.trips_trip_seq, seq)
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.on_accent))
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(ContextCompat.getColor(ctx, colorRes))
            }
        }
    }

    /** 花费徽章（金色）：只在有花销时出现，￥ 用整数分转元。 */
    private fun costBadge(cents: Int): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = getString(R.string.home_cost_value, (cents / 100).toString())
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.on_accent))
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(ContextCompat.getColor(ctx, R.color.badge_cost))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) }
        }
    }

    private fun titleView(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.fg))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun menuButton(onClick: (View) -> Unit): View =
        ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_more)
            background = null
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.text_tertiary),
            )
            contentDescription = getString(R.string.common_menu)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener(onClick)
        }

    /** 备注：图标 + 单行文字（超长截断）。没有备注返回 null，调用方用 spacer 占位把右侧信息顶到最右。 */
    private fun noteView(note: String?): View? {
        if (note.isNullOrBlank()) return null
        return iconText(R.drawable.ic_note, note, expandText = true)
    }

    /** 首访角标：金色浅底 + 金边。 */
    private fun firstVisitChip(): TextView = TextView(requireContext()).apply {
        setText(R.string.first_visit)
        textSize = 11f
        setTextColor(ContextCompat.getColor(context, R.color.first_visit))
        setBackgroundResource(R.drawable.bg_first_visit)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(4) }
    }

    /** 照片缩略图 + 同行人 + 星级；三者都没有返回 null。 */
    private fun checkinExtras(checkin: CheckinEntity): View? {
        val ctx = requireContext()
        val hasPhoto = checkin.photoPath != null
        val hasCompanions = checkin.companions != null
        val hasRating = checkin.rating != null
        if (!hasPhoto && !hasCompanions && !hasRating) return null

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        if (hasPhoto) {
            val bitmap = PhotoStore.load(checkin.photoPath, maxPx = 256)
            if (bitmap != null) {
                row.addView(ImageView(ctx).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(10) }
                })
            }
        }
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        if (hasCompanions) {
            info.addView(TextView(ctx).apply {
                text = checkin.companions.orEmpty().ifBlank { "—" }
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            })
        }
        if (hasRating) {
            val stars = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (star in 1..5) {
                stars.addView(ImageView(ctx).apply {
                    setImageResource(if ((checkin.rating ?: 0) >= star) R.drawable.ic_star else R.drawable.ic_star_outline)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.first_visit),
                    )
                    layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
                })
            }
            stars.setPadding(0, dp(2), 0, 0)
            info.addView(stars)
        }
        row.addView(info)
        return row
    }

    private fun spacer(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    private fun weightParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /**
     * 图标 + 文字的一组次要信息（14dp 图标、12sp 次要色）。
     *
     * [expandText] = true 时文字拿满剩余宽度并单行截断 —— 备注那种可能很长的一段要这样处理，
     * 否则 TextView 是 wrap_content，ellipsize 永远不触发，长备注会把右侧信息挤没。
     */
    private fun iconText(iconRes: Int, text: String, marginStart: Int = 0, expandText: Boolean = false): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (marginStart > 0) setPadding(dp(marginStart), 0, 0, 0)
            addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.text_tertiary),
                )
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
            })
            addView(TextView(ctx).apply {
                this.text = text
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(dp(4), 0, 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = if (expandText) {
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                } else {
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
            })
        }
    }

    /** 日期按精度显示：只记得年就只给年，不要补一个假的 1 月 1 日出去。 */
    private fun dateText(checkin: CheckinEntity): String = when (checkin.datePrecision) {
        DatePrecision.YEAR -> Dates.year(checkin.checkinAt)
        DatePrecision.MONTH -> Dates.dotMonth(checkin.checkinAt)
        DatePrecision.UNKNOWN -> Dates.year(checkin.checkinAt)
        else -> Dates.ymd(checkin.checkinAt)
    }

    // ────────────────────────── 删除 ──────────────────────────

    private fun confirmDeleteTrip(trip: TripEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trips_delete_title)
            .setMessage(
                getString(
                    R.string.trips_delete_message,
                    trip.title.ifBlank { getString(R.string.trips_trip_empty_title) },
                ),
            )
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
