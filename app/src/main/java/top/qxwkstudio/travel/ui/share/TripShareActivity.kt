package top.qxwkstudio.travel.ui.share

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
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
    private var layout = TextLayout.HORIZONTAL
    private var rangeUnit = RangeUnit.DAY
    private var selectedCities = linkedSetOf<String>()
    private var timelineOn = true

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

            view.title = entity.title
            view.layout = layout
            view.dateText = ShareDates.range(entity.startTime, entity.endTime, rangeUnit)
            applyCities()
            applyTimeline()
            applyPhoto()
            view.refresh()
            buildCards()
        }
    }

    private fun collectPhotos(timeline: Timeline?): List<String> {
        val out = LinkedHashSet<String>()
        timeline?.items.orEmpty().forEach { item ->
            item.photoPath?.split(",")?.filter { it.isNotBlank() }?.forEach(out::add)
        }
        return out.toList()
    }

    // ────────────────────────── 卡片 ──────────────────────────

    private fun buildCards() {
        photoCard()
        titleCard()
        dateCard()
        cityCard()
        timelineCard()
    }

    private fun photoCard() {
        val body = addCard(R.drawable.ic_camera, R.string.share_card_photo)
        fun fill() {
            body.removeAllViews()
            body.addView(
                photoStrip(
                    paths = photos,
                    selected = photoPath,
                    onPick = { photoPath = it; applyPhoto(); fill() },
                    onPicked = { photoPath = it; applyPhoto(); fill() },
                ),
            )
            body.addView(slider(R.string.share_offset_x, -100, 100, dx) { dx = it; applyTransform() })
            body.addView(slider(R.string.share_offset_y, -100, 100, dy) { dy = it; applyTransform() })
            body.addView(slider(R.string.share_zoom, 100, 300, zoom) { zoom = it; applyTransform() })
        }
        fill()
    }

    private fun titleCard() {
        val body = addCard(R.drawable.ic_note, R.string.share_card_title)
        fun fill() {
            body.removeAllViews()
            body.addView(
                chips(
                    labelRes = R.string.share_label_layout,
                    options = listOf(getString(R.string.share_layout_h), getString(R.string.share_layout_v)),
                    selected = if (layout == TextLayout.HORIZONTAL) 0 else 1,
                ) { index ->
                    layout = if (index == 0) TextLayout.HORIZONTAL else TextLayout.VERTICAL
                    view.layout = layout
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
        cities.forEach { city ->
            body.addView(CheckBox(this).apply {
                text = CityStore.displayName(this@TripShareActivity, city)
                textSize = 13f
                setTextColor(getColor(R.color.fg))
                buttonTintList = ColorStateList.valueOf(Palette.accent(this@TripShareActivity))
                isChecked = city in selectedCities
                setPadding(0, dp(4), 0, 0)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedCities.add(city) else selectedCities.remove(city)
                    applyCities()
                    view.refresh()
                }
            })
        }
    }

    private fun timelineCard() {
        val body = addCard(R.drawable.ic_timeline, R.string.share_card_timeline)
        body.addView(
            switchRow(R.string.share_timeline_switch, timelineOn) { checked ->
                timelineOn = checked
                view.timelineOn = checked
                // 关掉时间线后海报变矮：必须重新测量，否则预览下沿会留着一条空白
                view.refreshSize()
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

    /** 时间线按行程顺序排：城市 + 该城市段第一件事的日期（没有就退回行程开始日）。 */
    private fun applyTimeline() {
        val start = trip?.startTime ?: 0L
        view.timeline = segments.map { segment ->
            val time = segment.events.firstOrNull()?.time?.takeIf { it > 0L } ?: start
            CityStore.displayName(this, segment.city) to ShareDates.single(time, DateStyle.DOT)
        }.filter { it.first.isNotBlank() }
    }

    companion object {
        private const val EXTRA_ID = "trip_id"

        fun intent(context: Context, id: Long): Intent =
            Intent(context, TripShareActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
