package top.qxwkstudio.travel.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs

/**
 * 轻量图片浏览器：左右滑动切换、双指缩放、双击缩放、放大后拖动。
 *
 * 不引第三方大图库；照片本身已经压缩到 2560px，本地解码足以覆盖查看需求。
 */
internal class ZoomablePhotoPager(
    context: Context,
    private val paths: List<String>,
    startIndex: Int,
    private val onClose: () -> Unit,
    private val onPageChanged: (index: Int, count: Int) -> Unit,
) : FrameLayout(context) {

    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
        isClickable = false
    }

    /** 上一张/下一张的预加载层；拖动时和当前图一起移动。 */
    private val incoming = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
        isClickable = false
        alpha = 0f
    }

    private var index = startIndex.coerceIn(0, (paths.size - 1).coerceAtLeast(0))
    private var bitmap: Bitmap? = null
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var dragX = 0f
    private var swipeHandled = false
    private var switching = false
    private var incomingIndex = -1

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (scale * detector.scaleFactor).coerceIn(1f, 5f)
            if (next <= 1f) {
                resetZoom(false)
                return true
            }
            val factor = next / scale
            offsetX -= (detector.focusX - width / 2f) * (factor - 1f)
            offsetY -= (detector.focusY - height / 2f) * (factor - 1f)
            scale = next
            clampOffsets()
            applyTransform()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (scale <= 1.01f) onClose() else resetZoom(true)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1.01f) {
                resetZoom(true)
            } else {
                scale = 2.5f
                offsetX = (width / 2f - e.x) * (scale - 1f)
                offsetY = (height / 2f - e.y) * (scale - 1f)
                clampOffsets()
                applyTransform()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale <= 1.01f) {
                dragX += (e1?.x ?: e2.x) - e2.x
                image.translationX = -dragX
                if (abs(dragX) > dp(4)) {
                    val direction = if (dragX > 0) 1 else -1
                    prepareIncoming(direction)
                    incoming.translationX =
                        (if (direction > 0) width.toFloat() else -width.toFloat()) - dragX
                    incoming.alpha = 1f
                } else {
                    incoming.alpha = 0f
                }
            } else {
                offsetX -= dx
                offsetY -= dy
                clampOffsets()
                applyTransform()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scale <= 1.01f && abs(velocityX) > dp(220)) {
                swipeHandled = true
                slideTo(if (velocityX < 0) 1 else -1)
            }
            return true
        }
    })

    init {
        addView(
            incoming,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        addView(
            image,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        loadCurrent()
    }

    fun currentBitmap(): Bitmap? = bitmap

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            dragX = 0f
            swipeHandled = false
            incomingIndex = -1
            incoming.alpha = 0f
            incoming.translationX = 0f
        }
        if (switching) return true
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!swipeHandled && scale <= 1.01f) {
                if (abs(dragX) > width * 0.16f && incomingIndex >= 0) {
                    commitSlide(if (dragX > 0) 1 else -1)
                } else if (dragX != 0f) {
                    settleBack()
                }
            }
            dragX = 0f
            swipeHandled = false
        }
        return true
    }

    private fun prepareIncoming(direction: Int) {
        val next = index + direction
        if (next !in paths.indices) {
            incomingIndex = -1
            incoming.alpha = 0f
            return
        }
        if (incomingIndex == next) return
        incomingIndex = next
        incoming.setImageBitmap(PhotoStore.load(paths[next], maxPx = 2048))
        incoming.alpha = 1f
    }

    private fun settleBack() {
        image.animate().translationX(0f).setDuration(140).start()
        incoming.animate().translationX(0f).alpha(0f).setDuration(140).start()
        incomingIndex = -1
    }

    private fun commitSlide(direction: Int) {
        if (switching) return
        val next = index + direction
        if (next !in paths.indices) {
            settleBack()
            return
        }
        if (incomingIndex != next) {
            prepareIncoming(direction)
            incoming.translationX = if (direction > 0) width.toFloat() else -width.toFloat()
        }
        switching = true
        val outX = if (direction > 0) -width.toFloat() else width.toFloat()
        image.animate().translationX(outX).setDuration(180).withEndAction {
            index = next
            loadCurrent()
            switching = false
        }.start()
        incoming.animate().translationX(0f).setDuration(180).start()
    }

    private fun slideTo(direction: Int) {
        if (switching) return
        if (index + direction !in paths.indices) {
            settleBack()
            return
        }
        prepareIncoming(direction)
        incoming.translationX = if (direction > 0) width.toFloat() else -width.toFloat()
        incoming.alpha = 1f
        commitSlide(direction)
    }

    private fun loadCurrent() {
        resetZoom(false)
        bitmap = PhotoStore.load(paths.getOrNull(index), maxPx = 2048)
        image.setImageBitmap(bitmap)
        incomingIndex = -1
        incoming.alpha = 0f
        incoming.translationX = 0f
        onPageChanged(index, paths.size)
    }

    private fun applyTransform() {
        image.scaleX = scale
        image.scaleY = scale
        image.translationX = offsetX
        image.translationY = offsetY
    }

    private fun clampOffsets() {
        val maxX = width * (scale - 1f) / 2f
        val maxY = height * (scale - 1f) / 2f
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    private fun resetZoom(animate: Boolean) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        if (animate) {
            image.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(160).start()
        } else {
            applyTransform()
        }
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}
