package top.qxwkstudio.travel.ui.share

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.CheckinEntity
import top.qxwkstudio.travel.logic.DatePrecision
import top.qxwkstudio.travel.ui.common.runIo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 打卡分享：票根样式。
 *
 * 预览与导出共用 [TicketShareView] 的同一段绘制；这里只维护「可调整的状态」，
 * 每次改动都写回画布并 refresh —— 所以拖动滑条时预览是跟手的。
 */
class CheckinShareActivity : ShareActivityBase() {

    private var checkin: CheckinEntity? = null
    private var photos: List<String> = emptyList()

    private var photoPath: String? = null
    private var zoom = 100
    private var dx = 0
    private var dy = 0
    private var font = CardFont.DEFAULT
    private var layout = TextLayout.HORIZONTAL
    private var calendar = CalendarKind.GREGORIAN
    private var dateStyle = DateStyle.DOT
    private var lunarStyle = 0
    private var noteColor = InkColor.BLACK
    private var qrOn = true
    private var qrColor = InkColor.BLACK
    private var stampStyle = StampStyle.ROUND
    private var stampColor = InkColor.ACCENT

    override val titleRes: Int = R.string.share_title_checkin

    override val filePrefix: String = "checkin-share"

    private val view: TicketShareView get() = canvas as TicketShareView

    override fun createCanvas(): ShareCanvas = TicketShareView(this)

    override fun populate() {
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        lifecycleScope.runIo({ AppDatabase.get(this).checkins().byId(id) }) { result ->
            val entity = result.getOrNull() ?: run {
                finish()
                return@runIo
            }
            checkin = entity
            photos = entity.photoPath?.split(",")?.filter { it.isNotBlank() }.orEmpty()
            photoPath = photos.firstOrNull()
            applyPhoto()
            view.titleZh = entity.cityName
            view.titleEn = englishName(entity.cityName)
            view.note = entity.note.orEmpty()
            view.dateText = dateText(entity)
            view.qrContent = ShareLink.checkin(entity.uuid)
            view.refresh()
            buildCards()
        }
    }

    // ────────────────────────── 卡片 ──────────────────────────

    private fun buildCards() {
        photoCard()
        titleCard()
        dateCard()
        noteCard()
        qrCard()
        stampCard()
    }

    private fun photoCard() {
        // 「选择照片」挪到卡片标题行右侧；本打卡已有的照片放在下面，没有就整块不显示
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
                    options = listOf(
                        getString(R.string.share_font_default),
                        getString(R.string.share_font_serif),
                        getString(R.string.share_font_mono),
                        getString(R.string.share_font_light),
                        getString(R.string.share_font_condensed),
                        getString(R.string.share_font_cursive),
                    ),
                    selected = CardFont.ALL.indexOf(font),
                ) { index ->
                    font = CardFont.ALL[index]
                    view.font = font
                    view.refresh()
                    fill()
                },
            )
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
                    labelRes = R.string.share_label_calendar,
                    options = listOf(
                        getString(R.string.share_calendar_gregorian),
                        getString(R.string.share_calendar_lunar),
                    ),
                    selected = calendar.ordinal,
                ) { index ->
                    calendar = CalendarKind.entries[index]
                    checkin?.let { view.dateText = dateText(it) }
                    view.refresh()
                    // 两种历法的格式选项不同，整块重排
                    fill()
                },
            )
            if (calendar == CalendarKind.GREGORIAN) {
                body.addView(
                    chips(
                        labelRes = R.string.share_label_format,
                        options = listOf(
                            getString(R.string.share_date_format_dot),
                            getString(R.string.share_date_format_cn),
                            getString(R.string.share_date_format_en),
                            getString(R.string.share_date_format_slash),
                            getString(R.string.share_date_format_iso),
                            getString(R.string.share_date_format_space),
                        ),
                        selected = dateStyle.ordinal,
                    ) { index ->
                        dateStyle = DateStyle.entries[index]
                        checkin?.let { view.dateText = dateText(it) }
                        view.refresh()
                        fill()
                    },
                )
            } else {
                body.addView(
                    chips(
                        labelRes = R.string.share_label_format,
                        options = listOf(
                            getString(R.string.share_date_lunar_full),
                            getString(R.string.share_date_lunar_short),
                            getString(R.string.share_date_lunar_month),
                            getString(R.string.share_date_lunar_year),
                        ),
                        selected = lunarStyle,
                    ) { index ->
                        lunarStyle = index
                        checkin?.let { view.dateText = dateText(it) }
                        view.refresh()
                        fill()
                    },
                )
            }
        }
        fill()
    }

    private fun noteCard() {
        val body = addCard(R.drawable.ic_note, R.string.share_card_note)
        val edit = EditText(this).apply {
            setText(view.note)
            hint = getString(R.string.share_card_note)
            textSize = 14f
            // 备注允许换行：多行输入 + 顶对齐，票根上会按换行分行画
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = android.view.Gravity.TOP
            minLines = 2
            maxLines = 6
            setTextColor(getColor(R.color.fg))
            setHintTextColor(ContextCompat.getColor(this@CheckinShareActivity, R.color.text_tertiary))
            background = ContextCompat.getDrawable(this@CheckinShareActivity, R.drawable.bg_input_box)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            doAfterTextChanged { editable ->
                view.note = editable?.toString().orEmpty()
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

    private fun qrCard() {
        val body = addCard(R.drawable.ic_link, R.string.share_card_qr)
        fun fill() {
            body.removeAllViews()
            body.addView(
                switchRow(R.string.share_qr_switch, qrOn) { checked ->
                    qrOn = checked
                    view.qrOn = checked
                    view.refresh()
                    fill()
                },
            )
            if (qrOn) {
                body.addView(
                    colorRowLive(R.string.share_label_color, { qrColor }) { color ->
                        qrColor = color
                        view.qrColor = color
                        view.refresh()
                    },
                )
            }
        }
        fill()
    }

    private fun stampCard() {
        val body = addCard(R.drawable.ic_stamp, R.string.share_card_stamp)
        fun fill() {
            body.removeAllViews()
            body.addView(
                chips(
                    labelRes = R.string.share_label_style,
                    options = listOf(
                        getString(R.string.share_stamp_round),
                        getString(R.string.share_stamp_double),
                        getString(R.string.share_stamp_square),
                        getString(R.string.share_stamp_oval),
                        getString(R.string.share_stamp_perforated),
                    ),
                    selected = stampStyle.ordinal,
                ) { index ->
                    stampStyle = StampStyle.entries[index]
                    view.stampStyle = stampStyle
                    view.refresh()
                    fill()
                },
            )
            body.addView(
                colorRowLive(R.string.share_label_color, { stampColor }) { color ->
                    stampColor = color
                    view.stampColor = color
                    view.refresh()
                },
            )
        }
        fill()
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

    private fun englishName(cityName: String): String {
        val en = CityStore.all(this).firstOrNull { it.name == cityName }?.nameEn ?: return ""
        return en.uppercase(Locale.US) + "."
    }

    /**
     * 日期文案：先按历法取，再按记录精度收。
     * 只记得年 / 年月时不编造具体日；农历解析失败（极端日期）回退公历。
     */
    private fun dateText(entity: CheckinEntity): String {
        if (calendar == CalendarKind.LUNAR) {
            val lunar = LunarCalendar.of(entity.checkinAt)
            if (lunar != null) {
                return when (entity.datePrecision) {
                    DatePrecision.YEAR -> LunarCalendar.formatCoarse(lunar, 0)
                    DatePrecision.MONTH -> LunarCalendar.formatCoarse(lunar, 1)
                    DatePrecision.UNKNOWN -> getString(R.string.checkin_date_unknown)
                    else -> LunarCalendar.format(lunar, lunarStyle)
                }
            }
        }
        val pattern = when (entity.datePrecision) {
            DatePrecision.YEAR -> "yyyy"
            DatePrecision.MONTH -> "yyyy.MM"
            DatePrecision.UNKNOWN -> return getString(R.string.checkin_date_unknown)
            else -> when (dateStyle) {
                DateStyle.DOT -> "yyyy.MM.dd"
                DateStyle.CN -> "yyyy年M月d日"
                DateStyle.EN -> "d MMM yyyy"
                DateStyle.SLASH -> "yyyy/MM/dd"
                DateStyle.ISO -> "yyyy-MM-dd"
                DateStyle.SPACED -> "yyyy  MM  dd"
            }
        }
        val locale = if (dateStyle == DateStyle.CN) Locale.CHINA else Locale.US
        return SimpleDateFormat(pattern, locale).format(Date(entity.checkinAt))
    }

    companion object {
        private const val EXTRA_ID = "checkin_id"

        fun intent(context: Context, id: Long): Intent =
            Intent(context, CheckinShareActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
