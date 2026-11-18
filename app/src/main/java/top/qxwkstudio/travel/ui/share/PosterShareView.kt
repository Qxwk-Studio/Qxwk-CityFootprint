package top.qxwkstudio.travel.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * 足迹分享：竖版海报。
 *
 * 上侧 4:3 图片区，下侧信息区（标题 / 时间跨度 / 到达城市 + 二维码 / 时间线）。
 * 时间线可以关掉：关掉后设计高度从 1440 收到 1240，预览与导出图一起变矮 ——
 * 所以 [designHeight] 是动态的，改完必须调 [refreshSize] 重新测量。
 */
internal class PosterShareView(context: Context) : ShareCanvas(context) {

    override val designWidth = 1080

    var timelineOn = true

    override val designHeight: Int
        get() = if (timelineOn) 1440 else 1240

    var photo: Bitmap? = null
    var zoom = 1f
    var dx = 0f
    var dy = 0f

    var title = ""
    var layout = TextLayout.HORIZONTAL
    var dateText = ""
    var cities: List<String> = emptyList()

    /** 时间线上的节点：城市名 → 起始日期（按行程时间线顺序）。 */
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

        // 上侧 4:3 图片区
        val image = RectF(40f, 40f, designWidth - 40f, 40f + (designWidth - 80f) * 0.75f)
        drawPhoto(canvas, photo, image, 18f, zoom, dx, dy, PHOTO_EMPTY)

        val left = 72f
        val right = designWidth - 72f
        val width = right - left
        var cursor = image.bottom + 78f

        if (layout == TextLayout.VERTICAL) {
            cursor += drawStyledVertical(canvas, title, left + 34f, cursor, 56f, tint, CardFont.DEFAULT, 10f)
        } else {
            drawText(canvas, title, left, cursor, 62f, tint, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), maxWidth = width)
            cursor += 22f
        }

        cursor += 52f
        drawText(canvas, dateText, left, cursor, 30f, tint, Typeface.DEFAULT, maxWidth = width)

        cursor += 58f
        // 二维码已移除：城市行直接用满整宽
        val cityLines = wrap(cities.joinToString(" · "), 30f, tint, width, 2)
        cityLines.forEachIndexed { index, line ->
            drawText(canvas, line, left, cursor + index * 40f, 30f, tint)
        }

        if (timelineOn) drawTimeline(canvas, tint, cursor + 120f)
        canvas.restore()
    }

    /** 横向时间线：一条细线 + 每个城市一个点，城市名在点上方、日期在下方。 */
    private fun drawTimeline(canvas: Canvas, tint: Int, top: Float) {
        val nodes = timeline.filter { it.first.isNotBlank() }
        if (nodes.isEmpty()) return

        val left = 84f
        val right = designWidth - 84f
        val shown = nodes.take(MAX_NODES)
        val step = if (shown.size > 1) (right - left) / (shown.size - 1) else 0f
        val lineY = top + 46f

        stroke.color = 0x66221D16
        stroke.strokeWidth = 3f
        stroke.pathEffect = null
        canvas.drawLine(left, lineY, if (shown.size > 1) right else left, lineY, stroke)

        shown.forEachIndexed { index, (city, date) ->
            val x = if (shown.size > 1) left + step * index else left
            fill.color = tint
            canvas.drawCircle(x, lineY, 9f, fill)

            val label = shortCity(city)
            drawText(canvas, label, x, lineY - 22f, 26f, tint, align = Paint.Align.CENTER, maxWidth = if (shown.size > 1) step + 60f else 400f)
            drawText(canvas, date, x, lineY + 38f, 22f, tint, align = Paint.Align.CENTER)
        }
    }

    /** 时间线上城市名最多 5 字，超出就截断 —— 节点间距只够这么宽。 */
    private fun shortCity(name: String): String = if (name.length <= 5) name else name.take(4) + "…"

    private companion object {
        const val PAPER = 0xFFFBF6EA.toInt()
        const val BACKDROP = 0xFFEAE2D1.toInt()
        const val PHOTO_EMPTY = 0xFFE2D9C6.toInt()
        const val MAX_NODES = 6
    }
}
