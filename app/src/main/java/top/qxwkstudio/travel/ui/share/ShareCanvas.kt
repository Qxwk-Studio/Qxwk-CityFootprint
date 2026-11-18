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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
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

    private val qrCache = HashMap<String, Bitmap>()

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

    /** 导出原尺寸位图（票根 1600×900 / 海报 1080×设计高）。 */
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
        var shown = value
        if (maxWidth > 0f) {
            while (shown.length > 1 && text.measureText(shown) > maxWidth) {
                shown = shown.dropLast(2) + "…"
            }
        }
        canvas.drawText(shown, x, baseline, text)
    }

    /** 竖排标题：逐字往下落，返回占用高度。 */
    protected fun drawTextVertical(
        canvas: Canvas,
        value: String,
        x: Float,
        top: Float,
        size: Float,
        color: Int,
        typeface: Typeface,
        gap: Float,
    ): Float {
        text.typeface = typeface
        text.textSize = size
        text.color = color
        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0f
        var y = top + size
        value.forEach { ch ->
            canvas.drawText(ch.toString(), x, y, text)
            y += size + gap
        }
        return y - top
    }

    /** 按宽度折行（最多 [maxLines] 行，多余的用省略号收尾）。 */
    protected fun wrap(value: String, size: Float, color: Int, maxWidth: Float, maxLines: Int): List<String> {
        text.typeface = Typeface.DEFAULT
        text.textSize = size
        text.color = color
        text.textAlign = Paint.Align.LEFT
        text.letterSpacing = 0f
        val lines = ArrayList<String>()
        val paragraph = value.replace('\n', ' ').trim()
        if (paragraph.isEmpty()) return lines
        var line = StringBuilder()
        for (ch in paragraph) {
            val next = line.toString() + ch
            if (text.measureText(next) > maxWidth && line.isNotEmpty()) {
                lines.add(line.toString())
                if (lines.size == maxLines) break
                line = StringBuilder().append(ch)
            } else {
                line.append(ch)
            }
        }
        if (lines.size < maxLines && line.isNotEmpty()) lines.add(line.toString())
        if (lines.isEmpty()) lines.add("")
        if (lines.size == maxLines) {
            val lastIndex = maxLines - 1
            var last = lines[lastIndex]
            if (text.measureText(paragraph) > text.measureText(lines.joinToString("")) && last.length > 1) {
                while (last.length > 1 && text.measureText("$last…") > maxWidth) last = last.dropLast(1)
                lines[lastIndex] = "$last…"
            }
        }
        return lines
    }

    /** 深链二维码；内容为空（网页落地页还没定）时画虚线占位方块。 */
    protected fun drawQr(canvas: Canvas, dst: RectF, content: String, ink: Int, label: String) {
        if (content.isNotBlank()) {
            val size = dst.width().roundToInt().coerceAtLeast(64)
            val key = "$content|$size|$ink"
            val cached = qrCache[key]
            val bitmap = cached ?: ShareQr.bitmap(content, size, ink)?.also { qrCache[key] = it }
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, dst, photoPaint)
                return
            }
        }
        stroke.color = ink
        stroke.strokeWidth = 3f
        stroke.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        canvas.drawRect(dst, stroke)
        stroke.pathEffect = null
        drawText(
            canvas = canvas,
            value = label,
            x = dst.centerX(),
            baseline = dst.centerY() + 9f,
            size = 28f,
            color = ink,
            align = Paint.Align.CENTER,
        )
    }

    /** 四分之一圆邮戳：圆心压在卡片右下角，只露卡片内的那一块。 */
    protected fun drawStamp(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        style: StampStyle,
        ink: Int,
        topLine: String,
        bottomLine: String,
    ) {
        stroke.color = ink
        stroke.strokeWidth = 6f
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
        }
        // 文字只在露出的四分之一里，所以往左上压一点
        drawText(canvas, topLine, centerX - radius * 0.62f, centerY - radius * 0.5f, 26f, ink, align = Paint.Align.CENTER, letterSpacing = 0.08f)
        drawText(canvas, bottomLine, centerX - radius * 0.62f, centerY - radius * 0.5f + 34f, 24f, ink, align = Paint.Align.CENTER)
    }
}

/** ZXing 编码的一次性封装（只依赖 core，不引 android-embedded）。 */
internal object ShareQr {

    fun bitmap(content: String, sizePx: Int, color: Int): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                if (matrix[x, y]) bitmap.setPixel(x, y, color)
            }
        }
        bitmap
    }.getOrNull()

    /** 白底二维码：分享出去后放在深色背景上也能扫。 */
    fun onWhite(content: String, sizePx: Int, color: Int): Bitmap? {
        val dark = bitmap(content, sizePx, color) ?: return null
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(dark, 0f, 0f, null)
        dark.recycle()
        return out
    }
}
