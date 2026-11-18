package top.qxwkstudio.travel.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

/**
 * 足迹分享：竖版海报。
 *
 * 基准 3:4（1080×1440）：上侧正方形图片区，下侧信息区（标题 / 备注 + 时间段与到达城市）。
 * 打开时间线时上半部分一字不动，只在 3:4 的下沿画一条虚线，虚线下面接竖排时间线，
 * 海报按时间线条数**动态变长**。
 */
internal class PosterShareView(context: Context) : ShareCanvas(context) {

    override val designWidth = 1080

    var timelineOn = true

    override val designHeight: Int
        get() = if (timelineOn) {
            BASE_HEIGHT + TIMELINE_HEADER.toInt() + max(1, timeline.size) * ROW_HEIGHT.toInt()
        } else {
            BASE_HEIGHT
        }

    /** 时间线一行：城市（决定颜色）、日期时间、标题。 */
    data class Row(val city: String, val whenText: String, val title: String)

    var photo: Bitmap? = null
    var zoom = 1f
    var dx = 0f
    var dy = 0f

    var title = ""
    var font = CardFont.DEFAULT
    var dateText = ""

    var note = ""
    var noteColor = InkColor.BLACK

    var cities: List<String> = emptyList()

    var timeline: List<Row> = emptyList()

    fun refresh() = invalidate()

    override fun drawDesign(canvas: Canvas) {
        val tint = InkColor.BLACK.resolve(context)
        val height = designHeight.toFloat()

        fill.color = BACKDROP
        canvas.drawRect(0f, 0f, designWidth.toFloat(), height, fill)

        val card = RectF(30f, 30f, designWidth - 30f, height - 30f)
        val rounded = Path().apply { addRoundRect(card, 26f, 26f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(rounded)
        fill.color = PAPER
        canvas.drawRect(card, fill)

        // 上侧正方形图片区
        val left = 40f
        val right = designWidth - 40f
        val image = RectF(left, 40f, right, 40f + (right - left))
        drawPhoto(canvas, photo, image, 18f, zoom, dx, dy, PHOTO_EMPTY)

        val contentLeft = 60f
        val contentRight = designWidth - 60f
        val contentWidth = contentRight - contentLeft

        // 标题：与票根同字号，只做横排
        val titleBaseline = image.bottom + GAP_IMAGE_TITLE
        drawStyled(canvas, title, contentLeft, titleBaseline, 72f, tint, font, maxWidth = contentWidth)

        // 标题下面：备注在左，时间段与城市在右（右对齐）
        val blockTop = titleBaseline + 66f
        val noteWidth = contentWidth * 0.5f
        val noteLines = wrap(note, 28f, noteColor.resolve(context), noteWidth, 4)
        noteLines.forEachIndexed { index, line ->
            drawText(
                canvas = canvas,
                value = line,
                x = contentLeft,
                baseline = blockTop + index * 40f,
                size = 28f,
                color = noteColor.resolve(context),
            )
        }

        drawText(
            canvas = canvas,
            value = dateText,
            x = contentRight,
            baseline = blockTop,
            size = 36f,
            color = tint,
            typeface = Typeface.DEFAULT,
            align = Paint.Align.RIGHT,
            maxWidth = contentWidth * 0.46f,
        )

        val cityBaseline = blockTop + 56f
        val cityWidth = contentWidth * 0.46f
        val cityLines = wrap(cities.joinToString(" · "), 36f, tint, cityWidth, 2)
        cityLines.forEachIndexed { index, line ->
            drawText(
                canvas = canvas,
                value = line,
                x = contentRight,
                baseline = cityBaseline + index * 48f,
                size = 36f,
                color = tint,
                align = Paint.Align.RIGHT,
            )
        }

        if (timelineOn) {
            // 3:4 的下沿画虚线，虚线以下是延长出来的时间线
            val dividerY = BASE_HEIGHT - 30f
            stroke.color = 0x55221D16
            stroke.strokeWidth = 3f
            stroke.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
            canvas.drawLine(40f, dividerY, designWidth - 40f, dividerY, stroke)
            stroke.pathEffect = null
            drawTimeline(canvas, tint, dividerY + TIMELINE_HEADER)
        }
        canvas.restore()
    }

    /** 竖排时间线：一行一个节点，颜色点按城市固定配色（同城市同色），行内「日期时间 标题」。 */
    private fun drawTimeline(canvas: Canvas, tint: Int, top: Float) {
        val rows = timeline
        if (rows.isEmpty()) return

        val palette = listOf(
            InkColor.ACCENT.resolve(context),
            InkColor.GOLD.resolve(context),
            InkColor.GREEN.resolve(context),
            InkColor.BLUE.resolve(context),
            InkColor.RED.resolve(context),
            InkColor.PLUM.resolve(context),
            InkColor.TEAL.resolve(context),
            InkColor.BROWN.resolve(context),
        )
        val colorOf = LinkedHashMap<String, Int>()
        rows.forEach { row ->
            if (!colorOf.containsKey(row.city)) colorOf[row.city] = palette[colorOf.size % palette.size]
        }

        rows.forEachIndexed { index, row ->
            val baseline = top + index * ROW_HEIGHT
            fill.color = colorOf.getValue(row.city)
            canvas.drawCircle(64f, baseline - 9f, 8f, fill)

            val whenWidth = measure(row.whenText, 24f, Typeface.DEFAULT)
            drawText(
                canvas = canvas,
                value = row.whenText,
                x = 88f,
                baseline = baseline,
                size = 24f,
                color = tint,
                typeface = Typeface.DEFAULT,
            )
            drawText(
                canvas = canvas,
                value = row.title,
                x = 88f + whenWidth + 16f,
                baseline = baseline,
                size = 28f,
                color = tint,
                maxWidth = designWidth - 110f - whenWidth - 16f,
            )
        }
    }

    private companion object {
        const val PAPER = 0xFFFBF6EA.toInt()
        const val BACKDROP = 0xFFEAE2D1.toInt()
        const val PHOTO_EMPTY = 0xFFE2D9C6.toInt()

        /** 3:4 基准高度（1080×1440）。 */
        const val BASE_HEIGHT = 1440

        /** 图片与标题之间的空隙（设计坐标）。 */
        const val GAP_IMAGE_TITLE = 120f

        /** 虚线到第一条时间线的留白。 */
        const val TIMELINE_HEADER = 56f

        /** 时间线单行高度。 */
        const val ROW_HEIGHT = 46f
    }
}
