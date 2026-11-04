package top.qxwkstudio.travel.ui.map

import top.qxwkstudio.travel.ui.common.*

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import top.qxwkstudio.travel.R

/**
 * 去过的城市名——**用原生 Canvas 画在地图上**，不走 MapLibre 的文字图层。
 *
 * 为什么不用 SymbolLayer：文字图层需要 glyphs 字体包，离线打包一套中文字形要么几十 MB、
 * 要么得自己起本地 HTTP 服务；而这一版要的只是「点亮的城市叫什么」，原生绘制零依赖、
 * 中文直接吃系统字体，也不受底图缺字影响。
 *
 * 只画 [submit] 进来的城市（也就是本地库里去过的那些），不是底图全量地名 —— 底图 z7+ 的
 * 全量地名与区域包等有托管位置后再做。
 *
 * 文字是城市唯一的地图标识：不再单独渲染城市圆点。字号随缩放走（[sizeForZoom]），
 * 坐标点上下左右居中。
 */
class CityLabelsView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    private var map: MapLibreMap? = null
    private var labels: List<Pair<String, LatLng>> = emptyList()

    private val density = resources.displayMetrics.density

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.fg)
        // 半透明：文字压在颜色面层之下时会被面染一层，这里直接给出那种观感
        alpha = 190
    }

    /** 描边当光晕：压在底图/城市面上也读得清；粗细跟着字号走。 */
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.card)
        alpha = 170
    }

    /**
     * 字号随缩放：z2 约 6dp（全国视角允许看不清），z6 约 12dp，封顶 18dp ——
     * 这个量级才和底图标注接近；原来 17dp 起跳会盖过大片城市面。
     */
    private fun sizeForZoom(zoom: Float): Float = (6f + (zoom - 2f) * 1.5f).coerceIn(6f, 18f)

    fun attach(mapLibre: MapLibreMap) {
        map = mapLibre
        invalidate()
    }

    fun submit(newLabels: List<Pair<String, LatLng>>) {
        labels = newLabels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mapLibre = map ?: return
        val projection = mapLibre.projection
        val size = sizeForZoom(mapLibre.cameraPosition.zoom.toFloat()) * density
        if (textPaint.textSize != size) {
            textPaint.textSize = size
            haloPaint.textSize = size
            // 光晕只做「描一圈」的量，0.28 会把字糊成一团白块
            haloPaint.strokeWidth = (size * 0.10f).coerceAtLeast(1f * density)
        }
        val metrics = textPaint.fontMetrics
        val baselineOffset = -(metrics.ascent + metrics.descent) / 2f
        labels.forEach { (name, latLng) ->
            val point = projection.toScreenLocation(latLng)
            if (point.x < 0 || point.y < 0 || point.x > width || point.y > height) return@forEach
            // 以城市坐标为中心：左右按文字宽度回退一半，上下按字形中心对齐
            val x = point.x.toFloat() - textPaint.measureText(name) / 2f
            val y = point.y.toFloat() + baselineOffset
            canvas.drawText(name, x, y, haloPaint)
            canvas.drawText(name, x, y, textPaint)
        }
    }
}
