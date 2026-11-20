package top.qxwkstudio.travel.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 分享图的画布：固定「设计尺寸」，预览按屏幕宽度缩放、导出按原始像素出图。
 *
 * 预览与导出走同一个 [drawDesign]，所以预览里看到的排版就是保存/分享出去的那张图，
 * 不需要维护两套布局。图片区用 cover + 位移 + 缩放，滑条直接改这三个量。
 */
abstract class ShareCanvas(context: Context) : View(context) {

    abstract val designWidth: Int

    /** 海报关掉时间线时会变矮，所以是可变的 —— 变化后调 [refreshSize]。 */
    abstract val designHeight: Int

    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    protected val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }


    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        var height = (maxWidth * designHeight.toFloat() / designWidth).roundToInt()
        // 海报很高：父级给了可用高度上限时就按高度反推宽度，别把下面的调整卡片挤没
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
            height = minOf(height, MeasureSpec.getSize(heightMeasureSpec))
        }
        val width = (height * designWidth.toFloat() / designHeight).roundToInt().coerceAtMost(maxWidth)
        setMeasuredDimension(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        val scale = width / designWidth.toFloat()
        canvas.save()
        canvas.scale(scale, scale)
        drawDesign(canvas)
        canvas.restore()
    }

    /** 导出原尺寸位图（票根 2000×900 / 海报 1080×设计高）。 */
    fun exportBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(designWidth, designHeight, Bitmap.Config.ARGB_8888)
        drawDesign(Canvas(bitmap))
        return bitmap
    }

    /** 尺寸相关（海报开关时间线）变了：重新测量并重绘。 */
    fun refreshSize() {
        requestLayout()
        invalidate()
    }

    protected abstract fun drawDesign(canvas: Canvas)

    // ────────────────────────── 绘制小工具 ──────────────────────────

    /** 圆角裁切后画图片：cover 铺满，[zoom] ≥ 1，[dx]/[dy] 是相对图片区宽高的位移比例。 */
    protected fun drawPhoto(
        canvas: Canvas,
        bitmap: Bitmap?,
        dst: RectF,
        radius: Float,
        zoom: Float,
        dx: Float,
        dy: Float,
        placeholder: Int,
    ) {
        val path = Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        fill.color = placeholder
        canvas.drawRect(dst, fill)
        if (bitmap != null) {
            val scale = max(dst.width() / bitmap.width, dst.height() / bitmap.height) * zoom
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val left = dst.left + (dst.width() - w) / 2f + dx * dst.width()
            val top = dst.top + (dst.height() - h) / 2f + dy * dst.height()
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), photoPaint)
        }
        canvas.restore()
    }

    /** 量一段文字在当前字号下的宽度（给标题排版定位用）。 */
    protected fun measure(value: String, size: Float, typeface: Typeface, letterSpacing: Float = 0f): Float {
        text.typeface = typeface
        text.textSize = size
        text.textAlign = Paint.Align.LEFT
        text.letterSpacing = letterSpacing
        text.textScaleX = 1f
        return text.measureText(value)
    }

    /** 单行文字；[maxWidth] > 0 时超宽按省略号截断。 */
    protected fun drawText(
        canvas: Canvas,
        value: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        typeface: Typeface = Typeface.DEFAULT,
        align: Paint.Align = Paint.Align.LEFT,
        maxWidth: Float = 0f,
        letterSpacing: Float = 0f,
    ) {
        if (value.isEmpty()) return
        text.typeface = typeface
        text.textSize = size
        text.color = color
        text.textAlign = align
        text.letterSpacing = letterSpacing
        text.textScaleX = 1f
        var shown = value
        if (maxWidth > 0f) {
            while (shown.length > 1 && text.measureText(shown) > maxWidth) {
                shown = shown.dropLast(2) + "…"
            }
        }
        canvas.drawText(shown, x, baseline, text)
    }

    // ── 标题专用：带字体样式（字重/字距/横向压缩/手写子集）──

    /** Typeface.create 不便宜：3 种字族 × 4 种样式的组合缓存下来，重绘时不再新建。 */
    private val typefaceCache = HashMap<CardFont, Typeface>()

    private fun typefaceOf(font: CardFont): Typeface =
        typefaceCache.getOrPut(font) { font.typeface(context) }

    private fun applyFont(font: CardFont, size: Float, color: Int, letterSpacing: Float) {
        text.typeface = typefaceOf(font)
        text.textSize = size
        text.color = color
        text.letterSpacing = letterSpacing
        text.textScaleX = 1f
    }

    protected fun measureStyled(value: String, size: Float, font: CardFont): Float {
        applyFont(font, size, Color.BLACK, 0f)
        text.textAlign = Paint.Align.LEFT
        return text.measureText(value)
    }

    protected fun drawStyled(
        canvas: Canvas,
        value: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        font: CardFont,
        letterSpacing: Float = 0f,
        maxWidth: Float = 0f,
    ) {
        if (value.isEmpty()) return
        applyFont(font, size, color, font.letterSpacing + letterSpacing)
        text.textAlign = Paint.Align.LEFT
        var shown = value
        if (maxWidth > 0f) {
            while (shown.length > 1 && text.measureText(shown) > maxWidth) {
                shown = shown.dropLast(2) + "…"
            }
        }
        canvas.drawText(shown, x, baseline, text)
    }

    /** 竖排标题：逐字往下落，返回占用高度。 */
    protected fun drawStyledVertical(
        canvas: Canvas,
        value: String,
        x: Float,
        top: Float,
        size: Float,
        color: Int,
        font: CardFont,
        gap: Float,
    ): Float {
        applyFont(font, size, color, 0f)
        text.textAlign = Paint.Align.CENTER
        var y = top + size
        value.forEach { ch ->
            canvas.drawText(ch.toString(), x, y, text)
            y += size + gap
        }
        return y - top
    }

    /** 竖排英文（旋转 90°，从上往下读）：返回占用高度。 */
    protected fun drawRotatedText(
        canvas: Canvas,
        value: String,
        x: Float,
        top: Float,
        size: Float,
        color: Int,
        maxHeight: Float,
        letterSpacing: Float = 0.16f,
    ) {
        if (value.isEmpty()) return
        canvas.save()
        canvas.translate(x, top)
        canvas.rotate(90f)
        drawText(
            canvas = canvas,
            value = value,
            x = 0f,
            baseline = 0f,
            size = size,
            color = color,
            typeface = Typeface.DEFAULT,
            letterSpacing = letterSpacing,
            maxWidth = maxHeight,
        )
        canvas.restore()
    }

    /**
     * 按宽度折行；**保留手输的换行**（段落之间强制断行），最多 [maxLines] 行。
     */
    protected fun wrap(value: String, size: Float, color: Int, maxWidth: Float, maxLines: Int): List<String> {
        text.typeface = Typeface.DEFAULT
        text.textSize = size
        text.color = color
        text.textAlign = Paint.Align.LEFT
        text.letterSpacing = 0f
        text.textScaleX = 1f

        val lines = ArrayList<String>()
        val paragraphs = value.replace("\r\n", "\n").split('\n')
        for (paragraph in paragraphs) {
            if (lines.size >= maxLines) break
            if (paragraph.isBlank()) {
                lines.add("")
                continue
            }
            var line = StringBuilder()
            for (ch in paragraph.trim()) {
                val next = line.toString() + ch
                if (text.measureText(next) > maxWidth && line.isNotEmpty()) {
                    lines.add(line.toString())
                    if (lines.size >= maxLines) break
                    line = StringBuilder().append(ch)
                } else {
                    line.append(ch)
                }
            }
            if (lines.size < maxLines && line.isNotEmpty()) lines.add(line.toString())
        }
        return lines
    }

    /** 深链二维码占位已移除：票根左下角改成品牌文字，见 TicketShareView。 */

    /**
     * 四分之一圆印章：圆心压在卡片右下角，只露卡片内的那一块。
     * [paper] 是卡片底色 —— 齿孔要拿它「打孔」，不然孔是透明的、会透出背板色。
     * 章面不写字（品牌字排在信息区左下角，见 TicketShareView）。
     */
    protected fun drawStamp(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        style: StampStyle,
        ink: Int,
        paper: Int,
    ) {
        stroke.color = ink
        stroke.strokeWidth = 6f
        fill.color = ink
        // 章面：50% 透明度的印色铺底，形状必须与外圈一致（方章不能铺成圆的）。
        // 外圈与文字随后用实色盖上
        fill.color = Color.argb(128, Color.red(ink), Color.green(ink), Color.blue(ink))
        when (style) {
            StampStyle.SQUARE -> {
                canvas.save()
                canvas.rotate(8f, centerX, centerY)
                val side = radius * 1.5f
                canvas.drawRect(centerX - side / 2f, centerY - side / 2f, centerX + side / 2f, centerY + side / 2f, fill)
                canvas.restore()
            }

            StampStyle.OVAL -> {
                canvas.save()
                canvas.rotate(-12f, centerX, centerY)
                canvas.drawOval(
                    RectF(centerX - radius, centerY - radius * 0.7f, centerX + radius, centerY + radius * 0.7f),
                    fill,
                )
                canvas.restore()
            }

            else -> canvas.drawCircle(centerX, centerY, radius - 3f, fill)
        }
        fill.color = ink
        when (style) {
            StampStyle.ROUND -> {
                canvas.drawCircle(centerX, centerY, radius, stroke)
                stroke.strokeWidth = 3f
                canvas.drawCircle(centerX, centerY, radius - 22f, stroke)
            }

            StampStyle.DOUBLE -> {
                canvas.drawCircle(centerX, centerY, radius, stroke)
                canvas.drawCircle(centerX, centerY, radius - 26f, stroke)
            }

            StampStyle.SQUARE -> {
                canvas.save()
                canvas.rotate(8f, centerX, centerY)
                val side = radius * 1.5f
                canvas.drawRect(centerX - side / 2f, centerY - side / 2f, centerX + side / 2f, centerY + side / 2f, stroke)
                canvas.restore()
            }

            StampStyle.OVAL -> {
                canvas.save()
                canvas.rotate(-12f, centerX, centerY)
                val outer = RectF(centerX - radius, centerY - radius * 0.7f, centerX + radius, centerY + radius * 0.7f)
                canvas.drawOval(outer, stroke)
                stroke.strokeWidth = 3f
                canvas.drawOval(
                    RectF(outer.left + 20f, outer.top + 20f, outer.right - 20f, outer.bottom - 20f),
                    stroke,
                )
                canvas.restore()
            }

            StampStyle.PERFORATED -> {
                // 齿孔：外圈实线，再沿线用纸色打一圈小圆孔（不是虚线）
                canvas.drawCircle(centerX, centerY, radius, stroke)
                stroke.strokeWidth = 3f
                canvas.drawCircle(centerX, centerY, radius - 26f, stroke)

                fill.color = paper
                val holes = 30
                for (index in 0 until holes) {
                    val angle = Math.toRadians(index * 360.0 / holes)
                    val hx = centerX + (radius * Math.cos(angle)).toFloat()
                    val hy = centerY + (radius * Math.sin(angle)).toFloat()
                    canvas.drawCircle(hx, hy, 8.5f, fill)
                }
            }
        }
        stroke.strokeWidth = 6f
    }
}
