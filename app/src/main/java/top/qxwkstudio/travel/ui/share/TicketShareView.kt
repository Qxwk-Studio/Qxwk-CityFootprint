package top.qxwkstudio.travel.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import top.qxwkstudio.travel.R

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
    var font = CardFont.DEFAULT
    var layout = TextLayout.HORIZONTAL

    var dateText = ""
    var note = ""
    var noteColor = InkColor.BLACK

    var qrOn = true
    var qrColor = InkColor.BLACK
    var qrContent = ""

    var stampColor = InkColor.ACCENT
    var stampStyle = StampStyle.ROUND

    fun refresh() = invalidate()

    override fun drawDesign(canvas: Canvas) {
        val tint = InkColor.BLACK.resolve(context)

        fill.color = PAPER_BACKDROP
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
        fill.color = PAPER
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
                // 横排：中英同一行，中文大、英文小一号紧跟其后
                val zhWidth = measureStyled(titleZh, 62f, font).coerceAtMost(width - 40f)
                drawStyled(canvas, titleZh, left, cursor + 58f, 62f, tint, font, maxWidth = width - 40f)
                if (titleEn.isNotBlank()) {
                    drawText(
                        canvas = canvas,
                        value = titleEn,
                        x = left + zhWidth + 14f,
                        baseline = cursor + 58f,
                        size = 28f,
                        color = tint,
                        typeface = Typeface.DEFAULT,
                        letterSpacing = 0.12f,
                        maxWidth = (width - zhWidth - 14f).coerceAtLeast(48f),
                    )
                }
                cursor += 96f
            }

            TextLayout.VERTICAL -> {
                // 竖排：中英都竖着读，中文一列、英文旋转 90° 紧贴右侧一列
                val zhLeft = left + 28f
                val zhHeight = drawStyledVertical(canvas, titleZh, zhLeft, cursor, 56f, tint, font, 10f)
                if (titleEn.isNotBlank()) {
                    drawRotatedText(
                        canvas = canvas,
                        value = titleEn,
                        x = zhLeft + 44f,
                        top = cursor - 2f,
                        size = 24f,
                        color = tint,
                        maxHeight = zhHeight.coerceAtLeast(140f),
                    )
                }
                cursor += zhHeight + 22f
            }
        }

        cursor += 44f
        drawText(canvas, dateText, left, cursor, 30f, tint, Typeface.DEFAULT, maxWidth = width)
        cursor += 30f

        val lines = wrap(note, 24f, noteColor.resolve(context), width, 5)
        lines.forEachIndexed { index, line ->
            drawText(canvas, line, left, cursor + 40f + index * 34f, 24f, noteColor.resolve(context))
        }

        if (qrOn) {
            drawQr(
                canvas = canvas,
                dst = RectF(left, rect.bottom - 34f - 120f, left + 120f, rect.bottom - 34f),
                content = qrContent,
                ink = qrColor.resolve(context),
                label = context.getString(R.string.share_qr_placeholder),
            )
        }

        drawStamp(
            canvas = canvas,
            centerX = rect.right - 4f,
            centerY = rect.bottom - 4f,
            radius = 148f,
            style = stampStyle,
            ink = stampColor.resolve(context),
            paper = PAPER,
            topLine = "CITY",
            bottomLine = "FOOTPRINT",
        )
        canvas.restore()
    }

    private companion object {
        const val PAPER = 0xFFFBF6EA.toInt()
        const val PAPER_BACKDROP = 0xFFEAE2D1.toInt()
        const val PHOTO_EMPTY = 0xFFE2D9C6.toInt()
    }
}
