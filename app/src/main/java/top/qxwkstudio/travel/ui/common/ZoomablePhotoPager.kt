package top.qxwkstudio.travel.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs

/**
 * 轻量图片浏览器：跟手连续翻页 + 双指缩放 + 双击缩放 + 放大后拖动。
 *
 * 三张图（上一张 / 当前 / 下一张）常驻并排摆着，拖动时一起位移，相邻那张是真的滑进来；
 * 松手按「位移超过 22% 宽」或「速度够快」决定翻不翻，再以减速插值滑到目标位并吸附，
 * 所以是连续横滑，不是一张张点按切换。
 *
 * 放大状态（scale > 1）下横滑是平移图片、不翻页 —— 与 2.3 之前一致，缩回 1 倍才翻页。
 * 不引第三方大图库；照片本身已经压缩到 2560px，本地解码足以覆盖查看需求。
 */
internal class ZoomablePhotoPager(
    context: Context,
    private val paths: List<String>,
    startIndex: Int,
    private val onClose: () -> Unit,
    private val onPageChanged: (index: Int, count: Int) -> Unit,
) : FrameLayout(context) {

    private val left = photoView()
    private val center = photoView()
    private val right = photoView()

    private var index = startIndex.coerceIn(0, (paths.size - 1).coerceAtLeast(0))

    /** 只留当前三张：翻很多张也不会把整组照片攒在内存里（PhotoFiles 自己还有一层 LruCache）。 */
    private val bitmaps = mutableMapOf<String, Bitmap>()

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    /** 未放大时手指带出来的横向位移：负数往左拖（看下一张），正数看上一张。 */
    private var dragOffset = 0f
    private var downX = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    /** 双指期间不做翻页位移，否则捏合的同时页面会跟着跑。 */
    private var multiTouch = false
    private var settling: ValueAnimator? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** 每次挂到窗口再取，避免 detach 后又被复用时用到已经 recycle 的实例。 */
    private var velocity: VelocityTracker? = null

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
        // 横滑位移与翻页判定都自己算（见 onTouchEvent）：GestureDetector 只负责轻点/双击
    })

    init {
        addView(left, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(center, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(right, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bindAll()
        onPageChanged(index, paths.size)
    }

    fun currentBitmap(): Bitmap? = paths.getOrNull(index)?.let { bitmaps[it] }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setDragOffset(dragOffset)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (velocity == null) velocity = VelocityTracker.obtain()
    }

    override fun onDetachedFromWindow() {
        settling?.let { settling = null; it.cancel() }
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 上一次吸附还没走完就再按下：就地接管，别等它把位置改回去
                settling?.let { settling = null; it.cancel() }
                downX = event.x
                lastX = event.x
                lastY = event.y
                dragging = false
                multiTouch = false
                velocity?.clear()
                velocity?.addMovement(event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                dragging = false
                if (scale <= 1.01f) animateOffsetTo(0f, 140L) {}
            }

            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (multiTouch) {
                    lastX = event.x
                    lastY = event.y
                    return true
                }
                if (scale > 1.01f) {
                    offsetX -= event.x - lastX
                    offsetY -= event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    clampOffsets()
                    applyTransform()
                } else {
                    val dx = event.x - downX
                    if (!dragging && abs(dx) > touchSlop) dragging = true
                    if (dragging) setDragOffset(edgeResisted(dx))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000)
                if (dragging && scale <= 1.01f) {
                    val vx = if (event.actionMasked == MotionEvent.ACTION_UP) velocity?.xVelocity ?: 0f else 0f
                    settle(vx)
                } else if (scale <= 1.01f && abs(dragOffset) > 0.5f) {
                    animateOffsetTo(0f, 140L) {}
                }
                dragging = false
                multiTouch = false
            }
        }
        return true
    }

    // ────────────────────────── 翻页 ──────────────────────────

    /** 松手：够远或够快就翻到相邻那张，否则回到原位；两者都是滑过去而不是瞬间改位。 */
    private fun settle(velocityX: Float) {
        val width = width.toFloat().coerceAtLeast(1f)
        val canPrev = index > 0
        val canNext = index < paths.lastIndex
        val fling = abs(velocityX) > dp(420)
        val target = when {
            dragOffset < 0 && canNext && (dragOffset < -width * 0.22f || (fling && velocityX < 0)) -> -width
            dragOffset > 0 && canPrev && (dragOffset > width * 0.22f || (fling && velocityX > 0)) -> width
            else -> 0f
        }
        val duration = (140 + 160 * (abs(target - dragOffset) / width)).toLong().coerceIn(140L, 300L)
        animateOffsetTo(target, duration) {
            if (target != 0f) {
                index += if (target < 0) 1 else -1
                // 新页的槽位要落在 0 位，先把上一次的位移清掉再重铺
                dragOffset = 0f
                resetZoom(false)
                bindAll()
                onPageChanged(index, paths.size)
            }
        }
    }

    private fun animateOffsetTo(target: Float, duration: Long, onEnd: () -> Unit) {
        settling?.let { settling = null; it.cancel() }
        val start = dragOffset
        if (abs(target - start) < 0.5f) {
            setDragOffset(target)
            onEnd()
            return
        }
        val animator = ValueAnimator.ofFloat(start, target).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { setDragOffset(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 只有「仍在生效」的那个才回调：被新动画或手指打断的不能改 index
                    if (settling === animation) {
                        settling = null
                        onEnd()
                    }
                }
            })
        }
        settling = animator
        animator.start()
    }

    /** 三张图按位移整体摆位；两张邻居始终在屏幕外侧一格，滑进来的是真内容。 */
    private fun setDragOffset(value: Float) {
        dragOffset = value
        val width = width.toFloat()
        left.translationX = -width + value
        center.translationX = value
        right.translationX = width + value
    }

    /** 到头了还往外拖就给阻尼，免得空白越拖越多。 */
    private fun edgeResisted(dx: Float): Float = when {
        dx > 0 && index <= 0 -> dx * 0.3f
        dx < 0 && index >= paths.lastIndex -> dx * 0.3f
        else -> dx
    }

    // ────────────────────────── 图片 ──────────────────────────

    private fun bindAll() {
        bind(center, index, sync = true)
        bind(left, index - 1, sync = false)
        bind(right, index + 1, sync = false)
        // 只留当前三张：翻很多张也不会把整组照片攒在内存里
        bitmaps.keys.retainAll(
            setOfNotNull(paths.getOrNull(index - 1), paths.getOrNull(index), paths.getOrNull(index + 1)),
        )
        setDragOffset(dragOffset)
        updateSlotVisibility()
    }

    /**
     * 当前这张同步解码（打开时就要看到），邻居走后台线程 —— 否则每次打开都要在
     * 主线程多解两张 2048px 的图，白屏时间明显变长。
     */
    private fun bind(view: ImageView, target: Int, sync: Boolean) {
        val path = paths.getOrNull(target)
        if (path == null) {
            view.tag = null
            view.setImageDrawable(null)
            return
        }
        view.tag = path
        bitmaps[path]?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        if (sync) {
            val bitmap = PhotoStore.load(path, maxPx = 2048) ?: return
            bitmaps[path] = bitmap
            view.setImageBitmap(bitmap)
        } else {
            Thread {
                val bitmap = PhotoStore.load(path, maxPx = 2048) ?: return@Thread
                post {
                    // 期间可能又翻页了：路径对不上就丢，别把旧图贴到别的槽位上
                    if (!isAttachedToWindow || view.tag != path) return@post
                    bitmaps[path] = bitmap
                    view.setImageBitmap(bitmap)
                }
            }.start()
        }
    }

    private fun updateSlotVisibility() {
        val zoomed = scale > 1.01f
        left.visibility = if (!zoomed && index > 0) View.VISIBLE else View.INVISIBLE
        right.visibility = if (!zoomed && index < paths.lastIndex) View.VISIBLE else View.INVISIBLE
    }

    private fun photoView(): ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
        isClickable = false
    }

    // ────────────────────────── 缩放 ──────────────────────────

    private fun applyTransform() {
        center.scaleX = scale
        center.scaleY = scale
        if (scale > 1.01f) {
            center.translationX = offsetX
            center.translationY = offsetY
        } else {
            center.translationX = dragOffset
            center.translationY = 0f
        }
        updateSlotVisibility()
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
            center.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(160)
                .withEndAction { applyTransform() }
                .start()
        } else {
            applyTransform()
        }
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}
