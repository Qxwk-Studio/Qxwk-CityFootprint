package top.qxwkstudio.travel.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * 足迹分享：竖版海报。
 *
 * 固定 3:4（1080×1440）：上侧正方形图片区，下侧信息区（标题 / 时间段 / 到达城市，全部右对齐）。
 * 打开时间线时海报不改变上半部分，只在 3:4 的下沿画一条虚线，虚线下面再接一段竖排时间线，
 * 所以关掉时间线就是标准 3:4 海报，打开则整体变长。
 */
internal class PosterShareView(context: Context) : ShareCanvas(context) {

    override val designWidth = 1080

    var timelineOn = true

    override val designHeight: Int
        get() = if (timelineOn) BASE_HEIGHT + TIMELINE_HEIGHT else BASE_HEIGHT

    var photo: Bitmap? = null
    var zoom = 1f
    var dx = 0f
    var dy = 0f

    var title = ""
    var font = CardFont.DEFAULT
    var dateText = ""
    var cities: List<String> = emptyList()

    /** 时间线节点：城市名 → 日期。同城市共用一种颜色。 */
    var timeline: List<Pair<String, String>> = emptyList()

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

        // 标题：与票根同字号，只做横排；与图片留出一段明显空隙
        val titleBaseline = image.bottom + GAP_IMAGE_TITLE
        drawStyled(canvas, title, contentLeft, titleBaseline, 72f, tint, font, maxWidth = contentWidth)

        // 时间段与城市右对齐
        val dateBaseline = titleBaseline + 70f
        drawText(
            canvas = canvas,
            value = dateText,
            x = contentRight,
            baseline = dateBaseline,
            size = 36f,
            color = tint,
            typeface = Typeface.DEFAULT,
            align = Paint.Align.RIGHT,
            maxWidth = contentWidth,
        )

        val cityBaseline = dateBaseline + 62f
        val cityLines = wrap(cities.joinToString(" · "), 36f, tint, contentWidth, 2)
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
            drawTimeline(canvas, tint, dividerY + 54f)
        }
        canvas.restore()
    }

    /** 竖排时间线：每个节点一行，圆点颜色按城市分配（同城市同色），行内用「·」分隔。 */
    private fun drawTimeline(canvas: Canvas, tint: Int, top: Float) {
        val nodes = timeline.filter { it.first.isNotBlank() }
        if (nodes.isEmpty()) return

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
        nodes.forEach { (city, _) ->
            if (!colorOf.containsKey(city)) colorOf[city] = palette[colorOf.size % palette.size]
        }

        nodes.take(MAX_NODES).forEachIndexed { index, (city, date) ->
            val baseline = top + index * 46f
            fill.color = colorOf.getValue(city)
            canvas.drawCircle(74f, baseline - 10f, 8f, fill)
            drawText(
                canvas = canvas,
                value = "$city · $date",
                x = 96f,
                baseline = baseline,
                size = 28f,
                color = tint,
            )
        }
    }

    private companion object {
        const val PAPER = 0xFFFBF6EA.toInt()
        const val BACKDROP = 0xFFEAE2D1.toInt()
        const val PHOTO_EMPTY = 0xFFE2D9C6.toInt()

        /** 3:4 基准高度（1080×1440）。 */
        const val BASE_HEIGHT = 1440

        /** 时间线区间额外增加的高度。 */
        const val TIMELINE_HEIGHT = 300

        /** 图片与标题之间的空隙（设计坐标）。 */
        const val GAP_IMAGE_TITLE = 150f

        const val MAX_NODES = 6
    }
}
