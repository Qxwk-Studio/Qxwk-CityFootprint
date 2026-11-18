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
    private var dateStyle = DateStyle.DOT
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
            view.dateText = dateText(entity, dateStyle)
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
                    options = listOf(
                        getString(R.string.share_font_default),
                        getString(R.string.share_font_serif),
                        getString(R.string.share_font_mono),
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
                    options = listOf(
                        getString(R.string.share_calendar_gregorian),
                        getString(R.string.share_calendar_lunar_soon),
                    ),
                    selected = 0,
                    disabled = intArrayOf(1),
                ) { },
            )
            body.addView(
                chips(
                    options = listOf(
                        getString(R.string.share_date_format_dot),
                        getString(R.string.share_date_format_cn),
                        getString(R.string.share_date_format_en),
                    ),
                    selected = dateStyle.ordinal,
                ) { index ->
                    dateStyle = DateStyle.entries[index]
                    checkin?.let { view.dateText = dateText(it, dateStyle) }
                    view.refresh()
                    fill()
                },
            )
        }
        fill()
    }

    private fun noteCard() {
        val body = addCard(R.drawable.ic_note, R.string.share_card_note)
        val edit = EditText(this).apply {
            setText(view.note)
            hint = getString(R.string.share_card_note)
            textSize = 14f
            minLines = 1
            maxLines = 3
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
            colorRowLive({ noteColor }) { color ->
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
                },
            )
            if (qrOn) {
                body.addView(
                    colorRow(qrColor) { color ->
                        qrColor = color
                        view.qrColor = color
                        view.refresh()
                        fill()
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
                    options = listOf(
                        getString(R.string.share_stamp_round),
                        getString(R.string.share_stamp_square),
                        getString(R.string.share_stamp_double),
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
                colorRow(stampColor) { color ->
                    stampColor = color
                    view.stampColor = color
                    view.refresh()
                    fill()
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

    /** 日期按记录精度出：只记得年月就不编一个 1 号出来。 */
    private fun dateText(entity: CheckinEntity, style: DateStyle): String {
        val pattern = when (entity.datePrecision) {
            DatePrecision.YEAR -> "yyyy"
            DatePrecision.MONTH -> "yyyy.MM"
            DatePrecision.UNKNOWN -> return getString(R.string.checkin_date_unknown)
            else -> when (style) {
                DateStyle.DOT -> "yyyy.MM.dd"
                DateStyle.CN -> "yyyy年M月d日"
                DateStyle.EN -> "d MMM yyyy"
            }
        }
        val locale = if (style == DateStyle.CN) Locale.CHINA else Locale.US
        return SimpleDateFormat(pattern, locale).format(Date(entity.checkinAt))
    }

    companion object {
        private const val EXTRA_ID = "checkin_id"

        fun intent(context: Context, id: Long): Intent =
            Intent(context, CheckinShareActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
