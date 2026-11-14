package top.qxwkstudio.travel.ui.trips

import top.qxwkstudio.travel.ui.common.*
import android.view.animation.PathInterpolator
import top.qxwkstudio.travel.ui.common.Palette
import top.qxwkstudio.travel.logic.Provinces
import android.widget.FrameLayout

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.SyncEvents
import top.qxwkstudio.travel.data.CheckinRepo
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.DataVersion
import top.qxwkstudio.travel.data.PhotoJanitor
import top.qxwkstudio.travel.data.TripRepo
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.CheckinEntity
import top.qxwkstudio.travel.data.db.CheckinSource
import top.qxwkstudio.travel.data.db.TripEntity
import top.qxwkstudio.travel.databinding.FragmentTripsBinding
import top.qxwkstudio.travel.logic.DatePrecision
import top.qxwkstudio.travel.logic.Dates
import top.qxwkstudio.travel.logic.Money
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
    private val binding get() = requireNotNull(_binding) { "binding 在视图生命周期外被访问" }

    private enum class Segment { CHECKINS, TRIPS }

    private var segment = Segment.CHECKINS
    private var query = ""
    private var visibleCount = PAGE_SIZE

    /** 两个分段各自缓存已铺好的卡片，来回切换时不重复 inflate 前 30 条。 */
    private var checkinRowsKey = ""
    private var checkinRowsCache: List<View> = emptyList()
    private var tripRowsKey = ""
    private var tripRowsCache: List<View> = emptyList()

    /** 胶囊容器上次的宽度：主题/配色重建后要重新摆滑块。 */
    private var segWidth = 0
    private var trips: List<TripEntity> = emptyList()
    private var checkins: List<CheckinEntity> = emptyList()

    /** 行程 uuid → `#编号`（派生打卡的徽章要显示它所属的行程编号）。 */
    private var seqByUuid: Map<String, Int> = emptyMap()

    /** 行程 uuid → (到访城市数, 活动数)，卡片第二行用来显示「N 城 · M 项」。 */
    private var timelineCountByUuid: Map<String, Pair<Int, Int>> = emptyMap()
    private var citiesByUuid: Map<String, List<String>> = emptyMap()
    private var tripTitleByUuid: Map<String, String> = emptyMap()
    private var checkinSearchById: Map<Long, String> = emptyMap()
    private var tripSearchByUuid: Map<String, String> = emptyMap()

    private var seenVersion = -1

    /** 每座城市「最早那条打卡」的 id：表格里的首访角标与金色边框按它判断。 */
    private var firstVisitIds: Set<Long> = emptySet()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTripsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            SyncEvents.events.collect { load() }
        }
        binding.root.fadeSlideIn()
        binding.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                visibleCount = PAGE_SIZE
                binding.btnClearSearch.visibility = if (query.isBlank()) View.GONE else View.VISIBLE
                render()
            }
        })
        binding.btnClearSearch.setOnClickListener { binding.inputSearch.setText("") }
        binding.btnLoadMore.setOnClickListener {
            visibleCount += PAGE_SIZE
            render()
        }

        binding.btnSegCheckins.setOnClickListener {
            if (segment == Segment.CHECKINS) return@setOnClickListener
            segment = Segment.CHECKINS
            visibleCount = PAGE_SIZE
            render()
            binding.segTrack.post { moveIndicator(animate = true) }
        }
        binding.btnSegTrips.setOnClickListener {
            if (segment == Segment.TRIPS) return@setOnClickListener
            segment = Segment.TRIPS
            visibleCount = PAGE_SIZE
            render()
            binding.segTrack.post { moveIndicator(animate = true) }
        }
        load()
        // 主题/深浅色切换会重建页面：宽度可能还是 0，等真正布局完再摆滑块
        binding.chipContainer.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w > 0 && w != segWidth) {
                segWidth = w
                moveIndicator(animate = false)
            }
        }
        binding.segTrack.post { moveIndicator(animate = false) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) { _binding?.root?.fadeSlideIn(); load() }
    }

    /** 从打卡页回来时本页没被 hide 过，靠版本号判断要不要重读（同 HomeFragment）。 */
    /**
     * 从详情页 / 编辑器回来一律重读：卡片内容（标题、备注、城市数）改完要立刻可见。
     * 本地两次查询很便宜，不值得为省它去赌版本号的时序。
     */
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
            trips = tripList
            checkins = checkinList
            seqByUuid = tripList.associate { it.uuid to it.seqNo }
            seenVersion = DataVersion.current
            firstVisitIds = checkinList
                .groupBy { it.cityName }
                .values
                .mapNotNull { group -> group.minWithOrNull(compareBy<CheckinEntity> { it.checkinAt }.thenBy { it.id })?.id }
                .toSet()
            val decoded = tripList.associate { trip -> trip.uuid to Timeline.decode(trip.timeline) }
            timelineCountByUuid = decoded.mapValues { (_, timeline) ->
                (timeline?.cityCount ?: 0) to (timeline?.eventCount ?: 0)
            }
            citiesByUuid = decoded.mapValues { (_, timeline) ->
                timeline?.items.orEmpty()
                    .filter { it.type == Timeline.Type.VISIT }
                    .map { it.text }
            }
            tripTitleByUuid = tripList.associate { it.uuid to it.title }
            tripSearchByUuid = tripList.associate { trip ->
                trip.uuid to listOf(
                    trip.title,
                    trip.note,
                    trip.seqNo.toString(),
                    citiesByUuid[trip.uuid].orEmpty().joinToString(" "),
                ).joinToString(" ").lowercase()
            }
            checkinSearchById = checkinList.associate { checkin ->
                checkin.id to listOf(
                    checkin.cityName,
                    checkin.province,
                    checkin.note,
                    checkin.companions,
                    checkin.tripUuid?.let { tripTitleByUuid[it] },
                    checkin.seqNo.toString(),
                ).joinToString(" ").lowercase()
            }
            render()
        }
    }

    private fun render() {
        val b = binding
        b.btnSegCheckins.text = getString(R.string.trips_seg_checkins, checkins.size)
        b.btnSegTrips.text = getString(R.string.trips_seg_trips, trips.size)
        updateSegColors()

        val keyword = query.trim().lowercase()
        val allVisible = when (segment) {
            Segment.CHECKINS -> if (keyword.isBlank()) checkins else checkins.filter { matches(it, keyword) }
            Segment.TRIPS -> if (keyword.isBlank()) trips else trips.filter { matches(it, keyword) }
        }
        val shown = allVisible.take(visibleCount)

        b.listContainer.removeAllViews()
        val empty = shown.isEmpty()
        b.textEmpty.setText(
            if (keyword.isNotBlank() && empty) R.string.trips_search_empty else R.string.trips_empty,
        )
        b.textEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        b.btnLoadMore.visibility = if (allVisible.size > shown.size) View.VISIBLE else View.GONE
        if (empty) return

        val rowsKey = "$keyword|$visibleCount|$seenVersion"
        val rows = when (segment) {
            Segment.CHECKINS -> {
                if (rowsKey == checkinRowsKey) checkinRowsCache
                else shown.map { checkinRow(it as CheckinEntity) }.also {
                    checkinRowsKey = rowsKey
                    checkinRowsCache = it
                }
            }
            Segment.TRIPS -> {
                if (rowsKey == tripRowsKey) tripRowsCache
                else shown.map { tripRow(it as TripEntity) }.also {
                    tripRowsKey = rowsKey
                    tripRowsCache = it
                }
            }
        }
        rows.forEach { b.listContainer.addView(it) }
    }

    private fun matches(checkin: CheckinEntity, keyword: String): Boolean =
        checkinSearchById[checkin.id]?.contains(keyword) == true

    private fun matches(trip: TripEntity, keyword: String): Boolean =
        tripSearchByUuid[trip.uuid]?.contains(keyword) == true

    private companion object {
        const val PAGE_SIZE = 30
    }

    private fun updateSegColors() {
        val on = ContextCompat.getColor(requireContext(), R.color.on_accent)
        val off = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        binding.btnSegCheckins.setTextColor(if (segment == Segment.CHECKINS) on else off)
        binding.btnSegTrips.setTextColor(if (segment == Segment.TRIPS) on else off)
    }

    /** 两等分滑块：宽度取容器一半，按当前分段平移。 */
    private fun moveIndicator(animate: Boolean) {
        val indicator = binding.chipIndicator
        val half = binding.chipContainer.width / 2
        // 还没布局完：等 chipContainer 的布局回调来摆，别把宽度写成 0
        if (half <= 0) return
        indicator.layoutParams = (indicator.layoutParams as FrameLayout.LayoutParams).apply { width = half }
        indicator.requestLayout()
        val target = if (segment == Segment.CHECKINS) 0f else half.toFloat()
        if (animate) {
            indicator.animate().translationX(target)
                .setDuration(200)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()
        } else {
            indicator.translationX = target
        }
    }
    // ────────────────────────── 卡片 ──────────────────────────

    private fun checkinRow(checkin: CheckinEntity): View {
        val derived = checkin.source == CheckinSource.DERIVED
        val stripe = if (derived) R.color.stripe_derived else R.color.stripe_manual
        val first = checkin.id in firstVisitIds
        val (root, card) = cardWithStripe(stripe, province = checkin.province)

        // 第一行：左 编号 + 城市 · 省份 + 金额；右 首访 + 菜单
        val header = row()
        val badgeText = if (derived) {
            getString(
                R.string.trips_derived_seq,
                checkin.tripUuid?.let { seqByUuid[it] } ?: 0,
                checkin.segmentIndex + 1,
            )
        } else {
            getString(R.string.trips_trip_seq, checkin.seqNo)
        }
        header.addView(numberBadgeText(badgeText, stripe))
        header.addView(titleViewWrap(CityStore.displayName(requireContext(), checkin.cityName)))
        if (!checkin.province.isNullOrBlank()) {
            header.addView(dotView())
            header.addView(provinceView(CityStore.provinceDisplay(requireContext(), checkin.province)))
        }
        checkin.amountCents?.takeIf { it > 0 }?.let { header.addView(costBadge(it)) }
        header.addView(spacer())
        if (first) header.addView(firstVisitChip())
        header.addView(menuButton { anchor ->
            showIconMenu(
                anchor,
                buildList {
                    add(MenuAction(R.drawable.ic_share, R.string.trips_menu_share) {
                        requireContext().notify(R.string.trips_share_wip)
                    })
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

        // 第二行：左 同行人；右 评分 · 时长 · 到访时间（评分在右组左一）
        val second = row(topMargin = 10)
        val left = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val companionText = checkin.companions?.takeIf { it.isNotBlank() } ?: "-"
        val companionView = iconText(R.drawable.ic_person, companionText, expandText = true)
        companionView.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        )
        left.addView(companionView)
        second.addView(left)
        second.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), 1)
        })

        val rightItems = mutableListOf<View>()
        rightItems.add(iconText(R.drawable.ic_star, checkin.rating?.let { getString(R.string.rating_value, it) } ?: "-"))
        rightItems.add(iconText(R.drawable.ic_calendar, checkin.stayDays?.let { getString(R.string.home_days_value, it.toString()) } ?: "-"))
        rightItems.add(iconText(R.drawable.ic_calendar, dateText(checkin)))
        val right = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rightItems.forEachIndexed { index, item ->
            if (index > 0) right.addView(dotView())
            right.addView(item)
        }
        second.addView(right)
        card.addView(second)

        root.isClickable = true
        root.setOnClickListener {
            startActivity(CheckinDetailActivity.intent(requireContext(), checkin.id))
        }
        return root
    }

    /** 非徽章信息之间的「·」分隔。 */
    private fun dotView(): TextView = TextView(requireContext()).apply {
        text = "·"
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        setPadding(dp(3), 0, dp(3), 0)
        gravity = Gravity.CENTER_VERTICAL
    }
    /** 省份小灰字。 */
    private fun provinceView(province: String): TextView = TextView(requireContext()).apply {
        text = province
        textSize = 11f
        setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(2), 0, 0, 0)
    }

    /** 标题（不占满宽度，留给右边的省/金额/菜单）。 */
    private fun titleViewWrap(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.fg))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), 0, 0, 0)
    }
    private fun tripRow(trip: TripEntity): View {
        val (root, card) = cardWithStripe(R.color.stripe_trip)

        // 第一行：编号徽章 + 标题 + 花费徽章 + 菜单
        val header = row()
        header.addView(numberBadge(trip.seqNo, R.color.stripe_trip))
        header.addView(titleViewWrap(trip.title.ifBlank { getString(R.string.trips_trip_empty_title) }))
        if (trip.totalCostCents > 0) header.addView(costBadge(trip.totalCostCents))
        header.addView(spacer())
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

        // 第二行：左「城市串」、右「时间线数 · 时间」
        val meta = row(topMargin = 8)
        val cities = citiesByUuid[trip.uuid].orEmpty()
        val cityText = cities.map { CityStore.displayName(requireContext(), it) }.joinToString(" · ").ifBlank { "-" }
        val cityView = iconText(R.drawable.ic_city, cityText, expandText = true)
        cityView.layoutParams = weightParams()
        meta.addView(cityView)
        // 左右两组之间留空隙
        meta.addView(View(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })
        val counts = timelineCountByUuid[trip.uuid] ?: (0 to 0)
        meta.addView(iconText(R.drawable.ic_luggage, getString(R.string.trips_meta_counts, counts.first, counts.second)))
        meta.addView(iconText(R.drawable.ic_calendar, Dates.ymd(trip.startTime), marginStart = 10))
        card.addView(meta)

        root.isClickable = true
        root.setOnClickListener {
            startActivity(TripDetailActivity.intent(requireContext(), trip.id))
        }
        return root
    }

    /**
     * 卡片外壳：左侧一条贯穿竖条 + 内容区。
     *
     * 竖条颜色按类别固定（足迹蓝 / 独立打卡绿 / 关联打卡灰，见 colors_2.xml）。
     * `clipToOutline` 把整张卡裁成圆角轮廓，竖条不会再从圆角处露出来。
     */
    private fun cardWithStripe(stripeRes: Int, province: String? = null): Pair<LinearLayout, LinearLayout> {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_card)
            // 水印可以比卡片大，溢出的部分按卡片轮廓裁掉
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

        // 卡片内容区：水印在底层，文字在上面
        val frame = WatermarkLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            watermarkText = Provinces.abbr(province)
            watermarkSizeSp = 90f
            setWatermarkColor(ContextCompat.getColor(ctx, stripeRes))
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        frame.addView(content)
        root.addView(frame)
        return root to content
    }

    // ────────────────────────── 小部件 ──────────────────────────

    private fun row(topMargin: Int = 0): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (topMargin > 0) setPadding(0, dp(topMargin), 0, 0)
    }

    /** 编号徽章：类别色底、白字、圆角 6dp。 */
    private fun numberBadge(seq: Int, colorRes: Int): TextView =
        numberBadgeText(getString(R.string.trips_trip_seq, seq), colorRes)

    private fun numberBadgeText(badge: String, colorRes: Int): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = badge
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
            text = getString(R.string.home_cost_value, Money.yuan(cents))
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
            ).apply { marginStart = dp(12) }
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
            val thumb = ImageView(ctx).apply {
                setBackgroundResource(R.drawable.bg_photo_tile)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(10) }
                setOnClickListener {
                    val photos = checkin.photoPath?.split(",")?.filter { it.isNotBlank() }.orEmpty()
                    showPhotoDialog(ctx, photos)
                }
            }
            row.addView(thumb)
            // 列表可能一次铺 30 张卡片；缩略图不能在主线程同步解码。
            viewLifecycleOwner.lifecycleScope.runIo({
                PhotoStore.load(checkin.photoPath, maxPx = 256)
            }) { result ->
                result.getOrNull()?.let { if (thumb.isAttachedToWindow) thumb.setImageBitmap(it) }
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
        DatePrecision.UNKNOWN -> getString(R.string.checkin_date_unknown)
        else -> Dates.ymd(checkin.checkinAt)
    }

    // ────────────────────────── 删除 ──────────────────────────

    private fun confirmDeleteTrip(trip: TripEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_title)
            .setMessage(
                getString(
                    R.string.delete_message,
                    trip.title.ifBlank { getString(R.string.trips_trip_empty_title) },
                ),
            )
            .setPositiveButton(R.string.common_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.runIo({
                    TripRepo.delete(AppDatabase.get(requireContext()), trip.id)
                }) { result ->
                    if (result.isSuccess) {
                        trips = trips.filterNot { it.uuid == trip.uuid }
                        checkins = checkins.filterNot { it.tripUuid == trip.uuid }
                        render()
                        requireContext().notify(R.string.trips_deleted)
                        PhotoJanitor.sweepAsync(requireContext())
                        load()
                    } else requireContext().notify(R.string.common_error, long = true)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmDeleteCheckin(checkin: CheckinEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, CityStore.displayName(requireContext(), checkin.cityName)))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.runIo({
                    CheckinRepo.delete(AppDatabase.get(requireContext()), checkin.id)
                }) { result ->
                    if (result.isSuccess) {
                        checkins = checkins.filterNot { it.id == checkin.id }
                        render()
                        requireContext().notify(R.string.trips_deleted)
                        load()
                    } else requireContext().notify(R.string.common_error, long = true)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
