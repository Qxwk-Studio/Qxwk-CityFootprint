package top.qxwkstudio.travel.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * 打卡分享：长幅票根（20:9）。
 *
 * 左右边缘是圆弧锯齿（两条边各挖一圈半圆），中间一条虚线把「左侧 16:9 图片区」与
 * 「右侧信息区」分开；信息区从上到下是标题（中文横排时英文旋转 90° 贴在其右侧）/ 时间 /
 * 备注，底部左侧二维码、右下角四分之一圆邮戳（圆心压在卡片角上，只露卡片内那块）。
 */
internal class TicketShareView(context: Context) : ShareCanvas(context) {

    override val designWidth = 2000

    override val designHeight = 900

    var photo: Bitmap? = null
    var zoom = 1f
    var dx = 0f
    var dy = 0f

    var titleZh = ""
    var titleEn = ""
    var font = CardFont()
    var layout = TextLayout.HORIZONTAL

    var dateText = ""
    var note = ""
    var noteColor = InkColor.BLACK

    var stampColor = InkColor.ACCENT
    var stampStyle = StampStyle.PERFORATED

    /** 票根纸底色（第一张「背景」卡可调）。 */
    var paperColor = PaperColor.CREAM

    fun refresh() = invalidate()

    override fun drawDesign(canvas: Canvas) {
        val tint = InkColor.BLACK.resolve(context)

        fill.color = paperColor.backdrop()
        canvas.drawRect(0f, 0f, designWidth.toFloat(), designHeight.toFloat(), fill)

        val rect = RectF(44f, 44f, 1956f, 856f)
        val ticket = Path().apply { addRoundRect(rect, 26f, 26f, Path.Direction.CW) }
        // 左右锯齿：不在圆角矩形上再挖半圆，边缘就只是一条直边
        val holes = Path()
        var holeY = rect.top + 46f
        while (holeY < rect.bottom - 26f) {
            holes.addCircle(rect.left, holeY, 15f, Path.Direction.CW)
            holes.addCircle(rect.right, holeY, 15f, Path.Direction.CW)
            holeY += 58f
        }
        ticket.op(holes, Path.Op.DIFFERENCE)

        canvas.save()
        canvas.clipPath(ticket)
        fill.color = paperColor.resolve()
        canvas.drawRect(rect, fill)

        // 图片区固定 16:9；票根整体 20:9，所以右侧信息区只剩一小条
        val divider = rect.left + rect.height() * 16f / 9f
        drawPhoto(
            canvas = canvas,
            bitmap = photo,
            dst = RectF(rect.left, rect.top, divider, rect.bottom),
            radius = 0f,
            zoom = zoom,
            dx = dx,
            dy = dy,
            placeholder = PHOTO_EMPTY,
        )

        stroke.color = 0x55221D16
        stroke.strokeWidth = 3f
        stroke.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        canvas.drawLine(divider, rect.top + 34f, divider, rect.bottom - 34f, stroke)
        stroke.pathEffect = null

        val left = divider + 30f
        val right = rect.right - 30f
        val width = right - left
        var cursor = rect.top + 64f

        when (layout) {
            TextLayout.HORIZONTAL -> {
                // 横排：中文一行、英文一行（左对齐），不再并排
                drawStyled(canvas, titleZh, left, cursor + 68f, 72f, tint, font, maxWidth = width)
                if (titleEn.isNotBlank()) {
                    drawText(
                        canvas = canvas,
                        value = titleEn,
                        x = left,
                        baseline = cursor + 68f + 48f,
                        size = 32f,
                        color = tint,
                        typeface = Typeface.DEFAULT,
                        letterSpacing = 0.12f,
                        maxWidth = width,
                    )
                    cursor += 48f
                }
                cursor += 112f
            }

            TextLayout.VERTICAL -> {
                // 竖排：中英都竖着读，两列**顶部对齐**在同一根基线上
                val top = rect.top + 40f
                val zhLeft = left + 34f
                val zhHeight = drawStyledVertical(canvas, titleZh, zhLeft, top, 66f, tint, font, 12f)
                if (titleEn.isNotBlank()) {
                    // 旋转后的首字左沿贴在 top 上；中文首字字形顶比 top 低约 7，补一下才真正齐平
                    drawRotatedText(
                        canvas = canvas,
                        value = titleEn,
                        x = zhLeft + 52f,
                        top = top + 7f,
                        size = 30f,
                        color = tint,
                        maxHeight = zhHeight.coerceAtLeast(160f),
                    )
                }
                cursor = top + zhHeight + 26f
            }
        }

        cursor += 48f
        drawText(canvas, dateText, left, cursor, 36f, tint, Typeface.DEFAULT, maxWidth = width)
        cursor += 38f

        val lines = wrap(note, 28f, noteColor.resolve(context), width, 4)
        lines.forEachIndexed { index, line ->
            drawText(canvas, line, left, cursor + 46f + index * 40f, 28f, noteColor.resolve(context))
        }

        // 信息区左下角：品牌字（印章里不再写字，避免重复）
        drawText(
            canvas = canvas,
            value = "Cityfootprint",
            x = left,
            baseline = rect.bottom - 34f,
            size = 26f,
            color = tint,
            typeface = Typeface.DEFAULT,
            letterSpacing = 0.06f,
            maxWidth = right - left - 110f,
        )

        drawStamp(
            canvas = canvas,
            centerX = rect.right - 4f,
            centerY = rect.bottom - 4f,
            radius = 168f,
            style = stampStyle,
            ink = stampColor.resolve(context),
            paper = paperColor.resolve(),
        )
        canvas.restore()
    }

    private companion object {
        const val PHOTO_EMPTY = 0xFFE2D9C6.toInt()
    }
}
