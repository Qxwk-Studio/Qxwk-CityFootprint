package top.qxwkstudio.travel.ui.share

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.TripEntity
import top.qxwkstudio.travel.logic.Timeline
import top.qxwkstudio.travel.ui.common.Palette
import top.qxwkstudio.travel.ui.common.runIo

/**
 * 足迹分享：竖版海报。
 *
 * 图片 4:3 在上、信息区在下；到达城市可勾选（默认全选），时间线可关（关掉后海报整体变矮）。
 */
class TripShareActivity : ShareActivityBase() {

    private var trip: TripEntity? = null
    private var segments: List<Timeline.Segment> = emptyList()
    private var photos: List<String> = emptyList()

    private var photoPath: String? = null
    private var zoom = 100
    private var dx = 0
    private var dy = 0
    private var font = CardFont()
    private var rangeUnit = RangeUnit.DAY
    private var selectedCities = linkedSetOf<String>()
    private var timelineOn = true
    private var note = ""
    private var noteColor = InkColor.BLACK
    private var paperColor = PaperColor.CREAM
    private var timelineShowDate = true
    private var timelineShowTime = true
    private var timelineShowCompanions = false
    private var timelineShowNote = false

    override val titleRes: Int = R.string.share_title_trip

    override val filePrefix: String = "trip-share"

    private val view: PosterShareView get() = canvas as PosterShareView

    override fun createCanvas(): ShareCanvas = PosterShareView(this)

    override fun populate() {
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        lifecycleScope.runIo({
            val db = AppDatabase.get(this)
            val entity = db.trips().byId(id) ?: return@runIo null
            entity to Timeline.decode(entity.timeline)
        }) { result ->
            val (entity, timeline) = result.getOrNull() ?: run {
                finish()
                return@runIo
            }
            trip = entity
            segments = timeline?.segments.orEmpty()
            photos = collectPhotos(timeline)
            photoPath = photos.firstOrNull()
            selectedCities = segments.map { it.city }.filter { it.isNotBlank() }.toCollection(linkedSetOf())
            note = entity.note.orEmpty()

            view.title = entity.title
            view.font = font
            view.dateText = ShareDates.range(entity.startTime, entity.endTime, rangeUnit)
            view.note = note
            view.noteColor = noteColor
            view.paperColor = paperColor
            view.timeline = buildTimelineRows(timeline, entity.startTime)
            view.showDate = timelineShowDate
            view.showTime = timelineShowTime
            view.showCompanions = timelineShowCompanions
            view.showNote = timelineShowNote
            applyCities()
            applyPhoto()
            // 时间线行数决定海报高度：数据到位后必须重新测量，否则首次进入会被截断
            view.refreshSize()
            buildCards()
        }
    }

    /**
     * 时间线行：每个城市先一行到达日，再把它下面的活动 / 交通 / 地点各排一行。
     * 颜色只跟城市绑定，所以同一城市的所有活动都是同一种颜色。
     */
    private fun buildTimelineRows(timeline: Timeline?, startTime: Long): List<PosterShareView.Row> {
        val rows = mutableListOf<PosterShareView.Row>()
        timeline?.segments.orEmpty().forEach { segment ->
            if (segment.city.isBlank()) return@forEach
            val city = CityStore.displayName(this, segment.city)
            val segmentStart = segment.events.firstOrNull()?.time?.takeIf { it > 0 } ?: startTime
            rows += PosterShareView.Row(
                city = segment.city,
                dateText = dateOnly(segmentStart),
                title = city,
                companions = segment.companions.orEmpty(),
                note = segment.note.orEmpty(),
            )
            segment.events.forEach eventLoop@{ event ->
                if (event.text.isBlank()) return@eventLoop
                rows += PosterShareView.Row(
                    city = segment.city,
                    dateText = dateOnly(event.time),
                    timeText = timeOnly(event.time),
                    title = event.text,
                    note = event.note.orEmpty(),
                )
            }
        }
        return rows.take(MAX_TIMELINE_ROWS)
    }

    private fun dateOnly(millis: Long): String =
        java.text.SimpleDateFormat("MM.dd", java.util.Locale.US).format(java.util.Date(millis))

    private fun timeOnly(millis: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(millis))

    private fun collectPhotos(timeline: Timeline?): List<String> {
        val out = LinkedHashSet<String>()
        timeline?.items.orEmpty().forEach { item ->
            item.photoPath?.split(",")?.filter { it.isNotBlank() }?.forEach(out::add)
        }
        return out.toList()
    }

    // ────────────────────────── 卡片 ──────────────────────────

    private fun buildCards() {
        backgroundCard()
        photoCard()
        titleCard()
        noteCard()
        dateCard()
        cityCard()
        timelineCard()
        addActions()
    }

    /** 第一张卡：海报纸底色。 */
    private fun backgroundCard() {
        val body = addCard(R.drawable.ic_palette, R.string.share_card_background)
        body.addView(paperRowLive(R.string.share_label_color, { paperColor }) { color ->
            paperColor = color
            view.paperColor = color
            view.refresh()
        })
    }

    private fun photoCard() {
        // 与打卡分享页一致：选择照片在标题右侧，已有照片列在下面（没有就隐藏），再往下才是滑条
        lateinit var body: LinearLayout
        body = addCard(
            iconRes = R.drawable.ic_camera,
            titleRes = R.string.share_card_photo,
            actionRes = R.string.share_pick_photo,
            onAction = {
                pickGallery { path ->
                    photoPath = path
                    applyPhoto()
                    photoCardBody(body)
                }
            },
        )
        photoCardBody(body)
    }

    private fun photoCardBody(body: LinearLayout) {
        body.removeAllViews()
        if (photos.isNotEmpty()) {
            body.addView(
                photoStrip(
                    paths = photos,
                    selected = photoPath,
                    onPick = { photoPath = it; applyPhoto(); photoCardBody(body) },
                    onPicked = { photoPath = it; applyPhoto(); photoCardBody(body) },
                    showPickButton = false,
                ),
            )
        }
        body.addView(slider(R.string.share_offset_x, -100, 100, dx) { dx = it; applyTransform() })
        body.addView(slider(R.string.share_offset_y, -100, 100, dy) { dy = it; applyTransform() })
        body.addView(slider(R.string.share_zoom, 100, 300, zoom) { zoom = it; applyTransform() })
    }

    private fun titleCard() {
        val body = addCard(R.drawable.ic_note, R.string.share_card_title)
        fun fill() {
            body.removeAllViews()
            body.addView(
                chips(
                    labelRes = R.string.share_label_font,
                    options = FontFamily.ALL.map { getString(it.labelRes) },
                    selected = FontFamily.ALL.indexOf(font.family),
                ) { index ->
                    font = font.copy(family = FontFamily.ALL[index])
                    view.font = font
                    view.refresh()
                    fill()
                },
            )
            body.addView(
                chips(
                    labelRes = R.string.share_label_font_style,
                    options = FontStyle.ALL.map { getString(it.labelRes) },
                    selected = FontStyle.ALL.indexOf(font.style),
                ) { index ->
                    font = font.copy(style = FontStyle.ALL[index])
                    view.font = font
                    view.refresh()
                    fill()
                },
            )
        }
        fill()
    }

    private fun dateCard() {
        val body = addCard(R.drawable.ic_calendar, R.string.share_card_date)
        fun fill() {
            body.removeAllViews()
            body.addView(
                chips(
                    labelRes = R.string.share_label_format,
                    options = listOf(
                        getString(R.string.share_range_day),
                        getString(R.string.share_range_month),
                        getString(R.string.share_range_year),
                    ),
                    selected = rangeUnit.ordinal,
                ) { index ->
                    rangeUnit = RangeUnit.entries[index]
                    trip?.let { view.dateText = ShareDates.range(it.startTime, it.endTime, rangeUnit) }
                    view.refresh()
                    fill()
                },
            )
        }
        fill()
    }

    private fun cityCard() {
        val body = addCard(R.drawable.ic_city, R.string.share_card_city)
        val cities = segments.map { it.city }.filter { it.isNotBlank() }
        if (cities.isEmpty()) {
            body.addView(android.widget.TextView(this).apply {
                setText(R.string.share_city_empty)
                textSize = 12f
                setTextColor(getColor(R.color.text_tertiary))
                setPadding(0, dp(10), 0, 0)
            })
            return
        }
        cities.forEach { city -> body.addView(cityRow(city)) }
    }

    /** 城市勾选：小圆点 + 城市名，尺寸与行程页多选指示器一致（18dp）。 */
    private fun cityRow(city: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            isClickable = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        val dot = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            imageTintList = ColorStateList.valueOf(getColor(R.color.on_accent))
        }
        row.addView(dot)
        row.addView(android.widget.TextView(this).apply {
            text = CityStore.displayName(this@TripShareActivity, city)
            textSize = 13f
            setTextColor(getColor(R.color.fg))
        })

        fun paint() {
            val on = city in selectedCities
            dot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                if (on) {
                    setColor(Palette.accent(this@TripShareActivity))
                } else {
                    setStroke(dp(1), getColor(R.color.border))
                    setColor(android.graphics.Color.TRANSPARENT)
                }
            }
            dot.setImageDrawable(
                if (on) androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_check) else null,
            )
        }
        row.setOnClickListener {
            if (!selectedCities.add(city)) selectedCities.remove(city)
            paint()
            applyCities()
            view.refresh()
        }
        paint()
        return row
    }

    private fun timelineCard() {
        val body = addCard(R.drawable.ic_timeline, R.string.share_card_timeline)
        body.addView(
            switchRow(R.string.share_timeline_switch, timelineOn, topPadding = 6) { checked ->
                timelineOn = checked
                view.timelineOn = checked
                // 海报高度跟时间线条数绑定：开关后必须重新测量
                view.refreshSize()
            },
        )
        // 每行显示哪些字段：日期 / 时间 / 同行人 / 备注。四个开关排成 2×2，比竖着四行矮一半
        body.addView(
            switchGrid(
                listOf(
                    R.string.share_timeline_date to timelineShowDate,
                    R.string.share_timeline_time to timelineShowTime,
                    R.string.share_timeline_companions to timelineShowCompanions,
                    R.string.share_timeline_note to timelineShowNote,
                ),
            ) { index, checked ->
                when (index) {
                    0 -> timelineShowDate = checked
                    1 -> timelineShowTime = checked
                    2 -> timelineShowCompanions = checked
                    else -> timelineShowNote = checked
                }
                view.showDate = timelineShowDate
                view.showTime = timelineShowTime
                view.showCompanions = timelineShowCompanions
                view.showNote = timelineShowNote
                view.refresh()
            },
        )
    }

    /** 开关排成两列：每行两颗，[onChange] 带下标（顺序与传入列表一致）。 */
    private fun switchGrid(
        items: List<Pair<Int, Boolean>>,
        onChange: (Int, Boolean) -> Unit,
    ): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.chunked(2).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { column, (labelRes, checked) ->
                val index = rowIndex * 2 + column
                val cell = switchRow(labelRes, checked, topPadding = 2, textSize = 12f) { value ->
                    onChange(index, value)
                }
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (column == 0) marginEnd = dp(8)
                    },
                )
            }
            // 奇数个时补一个空位，保证左右列宽一致
            if (pair.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            grid.addView(row)
        }
        return grid
    }

    /** 备注卡与打卡分享页同一套：多行输入 + 十档墨色。 */
    private fun noteCard() {
        val body = addCard(R.drawable.ic_note, R.string.share_card_note)
        val edit = android.widget.EditText(this).apply {
            setText(note)
            hint = getString(R.string.share_card_note)
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = android.view.Gravity.TOP
            minLines = 2
            maxLines = 5
            setTextColor(getColor(R.color.fg))
            setHintTextColor(getColor(R.color.text_tertiary))
            background = androidx.core.content.ContextCompat.getDrawable(
                this@TripShareActivity,
                R.drawable.bg_input_box,
            )
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            doAfterTextChanged { editable ->
                note = editable?.toString().orEmpty()
                view.note = note
                view.refresh()
            }
        }
        body.addView(edit)
        body.addView(
            colorRowLive(R.string.share_label_color, { noteColor }) { color ->
                noteColor = color
                view.noteColor = color
                view.refresh()
            },
        )
    }

    // ────────────────────────── 状态 → 画布 ──────────────────────────

    private fun applyPhoto() {
        loadPhoto(photoPath) { bitmap ->
            view.photo = bitmap
            view.refresh()
        }
    }

    private fun applyTransform() {
        view.zoom = zoom / 100f
        view.dx = dx / 100f
        view.dy = dy / 100f
        view.refresh()
    }

    private fun applyCities() {
        view.cities = segments.map { it.city }
            .filter { it.isNotBlank() && it in selectedCities }
            .map { CityStore.displayName(this, it) }
    }

    /** 时间线与城市颜色绑定在 view.timeline（Row.city）里，城市勾选不影响时间线内容。 */

    companion object {
        private const val EXTRA_ID = "trip_id"

        /** 时间线最多这么多行：再多海报会长得离谱，也超出常见分享场景。 */
        private const val MAX_TIMELINE_ROWS = 18

        fun intent(context: Context, id: Long): Intent =
            Intent(context, TripShareActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
