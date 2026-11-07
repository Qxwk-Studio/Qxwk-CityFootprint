package top.qxwkstudio.travel.ui.map

import top.qxwkstudio.travel.ui.common.*

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import top.qxwkstudio.travel.R

/**
 * 地图中文地名：原生 Canvas 绘制，不依赖 MapLibre glyphs 字体包。
 *
 * 数据来自本地 384 城表，随缩放分层显示：
 *  - 低缩放：只画省会/直辖市等主要城市，避免全国视角糊成一片；
 *  - 高缩放：逐渐放开全部城市，并按文字包围盒做碰撞剔除；
 *  - 去过的城市始终画在最上层，并用主题色加粗。
 */
class CityLabelsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Label(
        val name: String,
        val latLng: LatLng,
        val visited: Boolean,
        val major: Boolean,
    )

    private var map: MapLibreMap? = null
    private var labels: List<Label> = emptyList()

    private val density = resources.displayMetrics.density

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }
    private val visitedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.accent(context)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.card)
        alpha = 210
    }

    /** 已点亮城市的名字颜色：跟随用户徽章色，而不是主题色。 */
    fun setVisitedColor(color: Int) {
        visitedPaint.color = color
        invalidate()
    }

    fun attach(mapLibre: MapLibreMap) {
        map = mapLibre
        invalidate()
    }

    fun submit(newLabels: List<Label>) {
        labels = newLabels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mapLibre = map ?: return
        val zoom = mapLibre.cameraPosition.zoom.toFloat()
        // 低缩放只保留主要城市；到 z4.2 才放开全部城市
        val showAll = zoom >= 4.2f
        val size = (6f + (zoom - 2f) * 1.5f).coerceIn(6f, 18f) * density
        listOf(basePaint, visitedPaint, haloPaint).forEach { it.textSize = size }
        haloPaint.strokeWidth = (size * 0.10f).coerceAtLeast(1f * density)

        val metrics = basePaint.fontMetrics
        val baselineOffset = -(metrics.ascent + metrics.descent) / 2f
        val occupied = mutableListOf<RectF>()

        // 去过的城市 > 主要城市 > 普通城市；同优先级按名称稳定排序
        val ordered = labels.sortedWith(
            compareByDescending<Label> { it.visited }
                .thenByDescending { it.major }
                .thenBy { it.name },
        )
        ordered.forEach { label ->
            if (!showAll && !label.visited && !label.major) return@forEach
            val point = mapLibre.projection.toScreenLocation(label.latLng)
            if (point.x < 0 || point.y < 0 || point.x > width || point.y > height) return@forEach

            val paint = if (label.visited) visitedPaint else basePaint
            paint.textSize = size
            val textWidth = paint.measureText(label.name)
            if (textWidth > width * 0.36f) return@forEach
            val x = point.x - textWidth / 2f
            val y = point.y + baselineOffset
            val rect = RectF(x, point.y - size, x + textWidth, point.y + size)
            if (occupied.any { RectF.intersects(it, rect) }) return@forEach
            occupied += rect

            canvas.drawText(label.name, x, y, haloPaint)
            canvas.drawText(label.name, x, y, paint)
        }
    }
}
