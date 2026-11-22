package top.qxwkstudio.travel.ui.trips

import top.qxwkstudio.travel.ui.common.*
import top.qxwkstudio.travel.ui.share.CheckinShareActivity
import top.qxwkstudio.travel.ui.share.TripShareActivity
import top.qxwkstudio.travel.logic.Provinces

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.DataExport
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
 * 行程页（2.0）：分段控件「打卡 (N) / 足迹 (N)」+ ViewPager2 分页列表。
 *
 * 左右滑动是**跟手翻页**：两页常驻（offscreenPageLimit = 1），拖动时相邻页一起位移，
 * 顶部滑块按 `position + positionOffset` 同步，松手按速度吸附；点标签则是平滑滚过去。
 * 列表自己不再识别横滑，也就不会出现「松手后卡片又触发点击进详情」。
 *
 * 卡片结构（两种卡共用一套读法：左边一条类别色竖条 → 第一行是「编号徽章 + 标题 + 右侧菜单」，
 * 第二行是「备注 / 时间」这类次要信息）：
 *  - 打卡：编号徽章 + 城市名；第二行左备注、右时间
 *  - 足迹：编号徽章 + 标题 + 花费徽章；第二行左备注、右「时间线数 · 时间」
 *
 * 竖条用 `clipToOutline` 裁到卡片圆角里 —— 之前它按方角画，会从圆角处露出一点，看着像「超出卡片」。
 * 菜单用 [showIconMenu]（自绘、带图标、宽度 wrap），与主页「+」是同一套浮层。
 *
 * 多选模式（外壳切换见 [TripsSelectionHost] / MainActivity）：卡片左侧多出一颗指示器，
 * 点整张卡就是选中/取消；派生打卡在这期间整段隐藏，也不进全选、删除、导出与统计。
 */
class TripsFragment : Fragment() {

    private var _binding: FragmentTripsBinding? = null
    private val binding get() = requireNotNull(_binding) { "binding 在视图生命周期外被访问" }

    private enum class Segment { CHECKINS, TRIPS }

    /** 页序即标签顺序：0 = 打卡，1 = 足迹。ViewPager2 的页码直接当这里的下标用。 */
    private val segments = listOf(Segment.CHECKINS, Segment.TRIPS)

    private var query = ""

    /** 每个分段各自记住「加载更多」展开到第几条：翻页来回不该把另一段的展开量改掉。 */
    private val visibleCount = mutableMapOf<Segment, Int>()

    /** 已经创建出来的页（页面本身由 ViewPager2 的 RecyclerView 持有，这里只为数据变化时重铺）。 */
    private val pages = mutableMapOf<Segment, Page>()

    /** 滑块与标签颜色的连续联动，见 [PagerSegments]。 */
    private lateinit var tracker: PagerSegments

    /** 一页的滚动容器 + 卡片列；两页各自独立滚动，翻页后回到原位置。 */
    private class Page(val scroll: ScrollView, val column: LinearLayout) {
        /** 每页顶部那条搜索卡（第一个卡片位置）。 */
        var search: View? = null
    }

    /** 两个分段各自缓存已铺好的卡片，来回切换时不重复 inflate 前 30 条。 */
    private var checkinRowsKey = ""
    private var checkinRowsCache: List<View> = emptyList()
    private var tripRowsKey = ""
    private var tripRowsCache: List<View> = emptyList()
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

    /** 本次加载在跑：onViewCreated 与 onResume 相邻触发时只读一次库。 */
    private var loading = false

    /** 加载期间又来了 load()：不能直接丢，加载完补跑一次。 */
    private var pendingLoad = false

    /** 每座城市「最早那条打卡」的 id：表格里的首访角标与金色边框按它判断。 */
    private var firstVisitIds: Set<Long> = emptySet()

    /** 多选模式：卡片左侧出指示器、派生打卡隐藏、点按变成选中（外层顶栏/底栏由 MainActivity 切换）。 */
    private var selecting = false
    private val selectedCheckins = mutableSetOf<Long>()
    private val selectedTrips = mutableSetOf<String>()

    /** 行 key → 「只刷新选中外观」：切换选中时不重建整行，只换指示器与卡片边框。 */
    private val rowUpdaters = mutableMapOf<String, (Boolean) -> Unit>()

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

        tracker = PagerSegments(binding.segTrack, binding.chipIndicator, binding.chipContainer)
        binding.pager.adapter = PageAdapter()
        // 相邻页提前铺好：拖动时看到的是真内容在平移，而不是松手后才出现
        binding.pager.offscreenPageLimit = 1
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            // 拖动、惯性、吸附都是同一条逐像素回调：滑块和标签色只在这一处更新
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                tracker.update(position + positionOffset)
            }

            override fun onPageSelected(position: Int) {
                // 换页时再铺这一页（render 只铺当前页）
                bindCurrentPage()
            }
        })
        binding.btnSegCheckins.setOnClickListener { binding.pager.setCurrentItem(0, true) }
        binding.btnSegTrips.setOnClickListener { binding.pager.setCurrentItem(1, true) }
        load()
        binding.pager.post { tracker.update(binding.pager.currentItem.toFloat()) }
    }

    override fun onDestroyView() {
        // 页面和行缓存都绑在旧视图上（换主题/尺寸会整页重建），留着会被下一份视图复用成僵尸 View
        pages.clear()
        searchInputs.clear()
        renderPending?.let { _binding?.pager?.removeCallbacks(it) }
        renderPending = null
        invalidateRowCaches()
        _binding?.pager?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            _binding?.root?.fadeSlideIn()
            // 数据没变就别重读重铺：切 tab 的卡顿主要来自这里的两页整铺
            if (seenVersion != DataVersion.current) load()
        }
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
        if (loading) {
            pendingLoad = true
            return
        }
        loading = true
        // 版本号在读库前取：读完再取会把期间的写入标成「已是最新」
        val version = DataVersion.current
        viewLifecycleOwner.lifecycleScope.runIo({
            val db = AppDatabase.get(requireContext())
            TripRepo.list(db) to CheckinRepo.list(db)
        }) { result ->
            loading = false
            val replay = pendingLoad
            pendingLoad = false
            val data = result.getOrNull()
            if (data == null) {
                if (replay) load()
                return@runIo
            }
            val (tripList, checkinList) = data
            trips = tripList
            checkins = checkinList
            seqByUuid = tripList.associate { it.uuid to it.seqNo }
            seenVersion = version
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
            // 数据变化后剔掉已经不存在的选中项，顶栏统计不会停留在旧数字上
            if (selecting) {
                selectedCheckins.retainAll(
                    checkinList.filter { it.source != CheckinSource.DERIVED }.map { it.id }.toSet(),
                )
                selectedTrips.retainAll(tripList.map { it.uuid }.toSet())
            }
            render()
            if (selecting) notifySelectionCounts()
            // 加载期间被吞掉的刷新在这里补跑（例如同步事件、从编辑页返回）
            if (replay) load()
        }
    }

    /** 两页各自的搜索框共享同一个 query；同步时用 [syncingQuery] 防止互相触发。 */
    private val searchInputs = mutableListOf<EditText>()
    private var syncingQuery = false

    private fun searchCard(ctx: android.content.Context): View {
        val card = android.view.LayoutInflater.from(ctx).inflate(R.layout.view_trips_search, null)
        val input = card.findViewById<EditText>(R.id.inputSearch)
        val clear = card.findViewById<ImageView>(R.id.btnClearSearch)
        input.setText(query)
        input.setSelection(input.text.length)
        clear.visibility = if (query.isBlank()) View.GONE else View.VISIBLE
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (syncingQuery) return
                val text = s?.toString().orEmpty()
                query = text
                visibleCount.clear()
                syncingQuery = true
                searchInputs.forEach { other ->
                    if (other !== input) {
                        other.setText(text)
                        other.setSelection(other.text.length)
                    }
                }
                syncingQuery = false
                searchInputs.forEach { other ->
                    other.findViewById<ImageView>(R.id.btnClearSearch)?.visibility =
                        if (text.isBlank()) View.GONE else View.VISIBLE
                }
                // 250ms 防抖：连打时不再每个字都重铺两页
                scheduleRender()
            }
        })
        clear.setOnClickListener { input.setText("") }
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { bottomMargin = dp(10) }
        searchInputs += input
        return card
    }

    private fun clearSearch() {
        query = ""
        syncingQuery = true
        searchInputs.forEach { it.setText("") }
        syncingQuery = false
        visibleCount.clear()
        // 取消待执行的防抖重排，避免清空后又多铺一次
        renderPending?.let { _binding?.pager?.removeCallbacks(it) }
        renderPending = null
        render()
    }

    private var renderPending: Runnable? = null

    /** 标准防抖：每次输入都取消上一次待执行的重排，只有停手 250ms 才真正渲染。 */
    private fun scheduleRender() {
        val binding = _binding ?: return
        renderPending?.let(binding.pager::removeCallbacks)
        val action = Runnable {
            renderPending = null
            if (_binding != null) render()
        }
        renderPending = action
        binding.pager.postDelayed(action, 250)
    }

    private fun render() {
        val b = binding
        b.btnSegCheckins.text = getString(R.string.trips_seg_checkins, checkins.size)
        b.btnSegTrips.text = getString(R.string.trips_seg_trips, trips.size)
        // 只重铺当前页：另一页保留旧内容，翻过去时由 onPageSelected 现铺，
        // 避免一次搜索就把两页共 60 行全部重建
        bindCurrentPage()
    }

    /**
     * 把某个分段的卡片铺进它自己那一页。
     *
     * 两页各有一份滚动位置，「加载更多」也按分段单独记 —— 翻页来回不会互相挤掉。
     */
    private fun bindCurrentPage() {
        val segment = segments.getOrNull(binding.pager.currentItem) ?: return
        pages[segment]?.let { bindPage(it, segment) }
    }

    private fun bindPage(page: Page, target: Segment) {
        val keyword = query.trim().lowercase()
        val allVisible = when (target) {
            Segment.CHECKINS -> {
                val base = if (keyword.isBlank()) checkins else checkins.filter { matches(it, keyword) }
                // 多选期间派生打卡整段隐藏：它们不允许被选中，也不参与全选/删除/导出与统计
                if (selecting) base.filter { it.source != CheckinSource.DERIVED } else base
            }
            Segment.TRIPS -> if (keyword.isBlank()) trips else trips.filter { matches(it, keyword) }
        }
        val limit = visibleCount[target] ?: PAGE_SIZE
        val shown = allVisible.take(limit)

        // 搜索卡固定在第 0 个位置，只重铺它后面的行
        while (page.column.childCount > 1) page.column.removeViewAt(page.column.childCount - 1)
        page.search?.visibility = if (selecting) View.GONE else View.VISIBLE
        rowsFor(target, keyword, limit, shown).forEach(page.column::addView)
        if (shown.isEmpty()) {
            page.column.addView(emptyView(searching = keyword.isNotBlank()))
        } else if (allVisible.size > shown.size) {
            page.column.addView(loadMoreButton(target, page))
        }
    }

    private fun rowsFor(target: Segment, keyword: String, limit: Int, shown: List<*>): List<View> {
        // selecting 进 key：进出多选时行结构（左侧指示器）不同，不能复用旧行
        val rowsKey = "$keyword|$limit|$seenVersion|$selecting"
        return when (target) {
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
    }

    private fun emptyView(searching: Boolean): View = TextView(requireContext()).apply {
        setText(if (searching) R.string.trips_search_empty else R.string.trips_empty)
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        setPadding(dp(16), dp(32), dp(16), dp(32))
    }

    private fun loadMoreButton(target: Segment, page: Page): View {
        val ctx = requireContext()
        return MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.trips_load_more)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44),
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                visibleCount[target] = (visibleCount[target] ?: PAGE_SIZE) + PAGE_SIZE
                bindPage(page, target)
            }
        }
    }

    /** 每页各自一个 ViewHolder 池：页面内容不能被别的分段复用。 */
    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = segments.size

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val ctx = parent.context
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(16))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val scroll = ScrollView(ctx).apply {
                isFillViewport = true
                addView(column)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            val page = Page(scroll, column)
            page.search = searchCard(ctx).also { column.addView(it) }
            pages[segments[viewType]] = page
            return PageHolder(page)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            bindPage(holder.page, segments[position])
        }
    }

    private class PageHolder(val page: Page) : RecyclerView.ViewHolder(page.scroll)

    private fun matches(checkin: CheckinEntity, keyword: String): Boolean =
        checkinSearchById[checkin.id]?.contains(keyword) == true

    private fun matches(trip: TripEntity, keyword: String): Boolean =
        tripSearchByUuid[trip.uuid]?.contains(keyword) == true

    private companion object {
        const val PAGE_SIZE = 30
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
        // 多选模式下卡片菜单让位：这时候点卡片是选中，菜单点起来容易误删
        if (!selecting) {
            header.addView(menuButton { anchor ->
                showIconMenu(
                    anchor,
                    buildList {
                        add(MenuAction(R.drawable.ic_share, R.string.trips_menu_share) {
                            startActivity(CheckinShareActivity.intent(requireContext(), checkin.id))
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
        }
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
            if (selecting) {
                toggleCheckin(checkin)
            } else {
                startActivity(CheckinDetailActivity.intent(requireContext(), checkin.id))
            }
        }
        return if (selecting) decorateSelectable(root, keyOf(checkin), checkin.id in selectedCheckins) else root
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
        if (!selecting) {
            header.addView(menuButton { anchor ->
                showIconMenu(
                    anchor,
                    listOf(
                        MenuAction(R.drawable.ic_share, R.string.trips_menu_share) {
                            startActivity(TripShareActivity.intent(requireContext(), trip.id))
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
        }
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
            if (selecting) {
                toggleTrip(trip)
            } else {
                startActivity(TripDetailActivity.intent(requireContext(), trip.id))
            }
        }
        return if (selecting) decorateSelectable(root, keyOf(trip), trip.uuid in selectedTrips) else root
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

    // ────────────────────────── 多选 ──────────────────────────

    /** 由 MainActivity 在进入多选时调用（外壳切换在那边，选择状态在这边）。 */
    fun enterSelectMode() {
        if (selecting) return
        selecting = true
        selectedCheckins.clear()
        selectedTrips.clear()
        // 搜索框让位：这时若还按关键字过滤，用户看不到过滤条件，容易把看不见的条目一起选上
        clearSearch()
        invalidateRowCaches()
        render()
        notifySelectionCounts()
    }

    /** 退出多选：清空选择、行结构还原，派生打卡按原规则回到列表。 */
    fun exitSelectMode() {
        if (!selecting) return
        selecting = false
        selectedCheckins.clear()
        selectedTrips.clear()
        invalidateRowCaches()
        render()
    }

    /**
     * 全选**当前这一页**的列表（屏幕上现在这批行），派生打卡不参与。
     * 不做跨页全选：另一页的卡片此刻不可见，批量删除时被连带删掉太反直觉。
     */
    fun selectAllVisible() {
        if (!selecting) return
        val keyword = query.trim().lowercase()
        when (segments.getOrNull(binding.pager.currentItem)) {
            Segment.CHECKINS -> checkins
                .filter { it.source != CheckinSource.DERIVED && (keyword.isBlank() || matches(it, keyword)) }
                .take(visibleCount[Segment.CHECKINS] ?: PAGE_SIZE)
                .forEach { checkin ->
                    selectedCheckins.add(checkin.id)
                    rowUpdaters[keyOf(checkin)]?.invoke(true)
                }

            Segment.TRIPS -> trips
                .filter { keyword.isBlank() || matches(it, keyword) }
                .take(visibleCount[Segment.TRIPS] ?: PAGE_SIZE)
                .forEach { trip ->
                    selectedTrips.add(trip.uuid)
                    rowUpdaters[keyOf(trip)]?.invoke(true)
                }

            null -> Unit
        }
        notifySelectionCounts()
    }

    /** 二次确认：必须逐字输入确认文案，按钮才可用；输入不一致时点了也不删。 */
    fun confirmDeleteSelected() {
        if (selectedCheckins.isEmpty() && selectedTrips.isEmpty()) {
            requireContext().notify(R.string.trips_selection_empty)
            return
        }
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = getString(R.string.trips_selection_delete_hint)
            setSingleLine(true)
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.fg))
        }
        val box = FrameLayout(ctx).apply {
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val phrase = getString(R.string.trips_selection_delete_phrase)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.trips_selection_delete_title)
            .setMessage(
                getString(
                    R.string.trips_selection_delete_message,
                    selectedCheckins.size,
                    selectedTrips.size,
                    phrase,
                ),
            )
            .setView(box)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete, null)
            .create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = false
            input.doAfterTextChanged { text -> positive.isEnabled = text?.toString() == phrase }
            positive.setOnClickListener {
                if (input.text?.toString() != phrase) return@setOnClickListener
                dialog.dismiss()
                performDeleteSelected()
            }
        }
        dialog.show()
    }

    /** 只导出被选中的内容；派生打卡不在选择集合里，因此也不会被带出去。 */
    fun exportSelected() {
        if (selectedCheckins.isEmpty() && selectedTrips.isEmpty()) {
            requireContext().notify(R.string.trips_selection_empty)
            return
        }
        val checkinIds = selectedCheckins.toSet()
        val tripUuids = selectedTrips.toSet()
        requireActivity().pickExportFormat { format -> runExport(tripUuids, checkinIds, format) }
    }

    private fun runExport(
        tripUuids: Set<String>,
        checkinIds: Set<Long>,
        format: DataExport.Format,
    ) {
        viewLifecycleOwner.lifecycleScope.runIo({
            DataExport.export(
                requireContext(),
                AppDatabase.get(requireContext()),
                onlyTripUuids = tripUuids,
                onlyCheckinIds = checkinIds,
                format = format,
            )
        }) { result ->
            val data = result.getOrNull()
            if (data == null) {
                val tooLarge = result.exceptionOrNull()?.message == "TOO_LARGE"
                requireContext().notify(
                    if (tooLarge) R.string.export_too_large else R.string.export_failed,
                    long = true,
                )
            } else {
                requireContext().notify(
                    getString(R.string.export_done, data.displayPath, data.trips, data.checkins, data.photos),
                    long = true,
                )
            }
        }
    }

    private fun performDeleteSelected() {
        val checkinIds = selectedCheckins.toSet()
        val tripUuids = selectedTrips.toSet()
        val tripIds = trips.filter { it.uuid in tripUuids }.map { it.id }
        viewLifecycleOwner.lifecycleScope.runIo({
            val db = AppDatabase.get(requireContext())
            // 整批一个事务：中途失败不会留下删了一半的列表
            db.withTransaction {
                // 先删行程：它的派生打卡由 TripRepo 在同一事务里重算，不会留下孤儿
                tripIds.forEach { TripRepo.delete(db, it) }
                checkinIds.forEach { CheckinRepo.delete(db, it) }
            }
        }) { result ->
            if (!result.isSuccess) {
                requireContext().notify(R.string.common_error, long = true)
                return@runIo
            }
            trips = trips.filterNot { it.uuid in tripUuids }
            checkins = checkins.filterNot { it.id in checkinIds }
            selectedCheckins.clear()
            selectedTrips.clear()
            invalidateRowCaches()
            render()
            notifySelectionCounts()
            requireContext().notify(R.string.trips_selection_deleted)
            PhotoJanitor.sweepAsync(requireContext())
            load()
        }
    }

    private fun toggleCheckin(checkin: CheckinEntity) {
        if (!selecting || checkin.source == CheckinSource.DERIVED) return
        val selected = if (checkin.id in selectedCheckins) {
            selectedCheckins.remove(checkin.id)
            false
        } else {
            selectedCheckins.add(checkin.id)
            true
        }
        rowUpdaters[keyOf(checkin)]?.invoke(selected)
        notifySelectionCounts()
    }

    private fun toggleTrip(trip: TripEntity) {
        if (!selecting) return
        val selected = if (trip.uuid in selectedTrips) {
            selectedTrips.remove(trip.uuid)
            false
        } else {
            selectedTrips.add(trip.uuid)
            true
        }
        rowUpdaters[keyOf(trip)]?.invoke(selected)
        notifySelectionCounts()
    }

    /**
     * 多选行：左侧一颗指示器 + 原卡片。
     *
     * 卡片宽度让出一颗指示器的位置（整行仍铺满），选中只在卡片背景上多一圈主题色细描边 ——
     * 不改尺寸、不改内边距、不改点击区域。切换选中只走 [rowUpdaters]，不重建整页 30 行。
     */
    private fun decorateSelectable(card: View, key: String, selected: Boolean): View {
        val ctx = requireContext()
        val dot = ImageView(ctx).apply {
            contentDescription = getString(R.string.trips_selection_indicator)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginEnd = dp(8)
                topMargin = dp(14)
            }
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        (card.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.width = 0
            lp.weight = 1f
            card.layoutParams = lp
        }
        container.addView(dot)
        container.addView(card)

        val updater: (Boolean) -> Unit = { on ->
            if (on) {
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Palette.accent(ctx))
                }
                dot.setImageResource(R.drawable.ic_check)
                dot.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.on_accent),
                )
                dot.setPadding(dp(3), dp(3), dp(3), dp(3))
                // 描边走 foreground：只画在卡片轮廓上，不占尺寸，也不会被左侧竖条盖住
                card.foreground = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(2), Palette.accent(ctx))
                }
            } else {
                dot.setImageDrawable(null)
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2), ContextCompat.getColor(ctx, R.color.border))
                    setColor(android.graphics.Color.TRANSPARENT)
                }
                card.foreground = null
            }
        }
        rowUpdaters[key] = updater
        updater(selected)
        return container
    }

    private fun keyOf(checkin: CheckinEntity): String = "c${checkin.id}"

    private fun keyOf(trip: TripEntity): String = "t${trip.uuid}"

    private fun notifySelectionCounts() {
        (activity as? TripsSelectionHost)?.onTripsSelectionCounts(selectedCheckins.size, selectedTrips.size)
    }

    private fun invalidateRowCaches() {
        checkinRowsKey = ""
        checkinRowsCache = emptyList()
        tripRowsKey = ""
        tripRowsCache = emptyList()
        rowUpdaters.clear()
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
