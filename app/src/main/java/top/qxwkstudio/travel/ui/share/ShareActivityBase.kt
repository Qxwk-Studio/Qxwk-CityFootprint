package top.qxwkstudio.travel.ui.share

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.databinding.ActivityShareBinding
import top.qxwkstudio.travel.ui.common.LocalizedActivity
import top.qxwkstudio.travel.ui.common.Palette
import top.qxwkstudio.travel.ui.common.applyEdgeToEdge
import top.qxwkstudio.travel.ui.common.fadeSlideIn
import top.qxwkstudio.travel.ui.common.notify
import top.qxwkstudio.travel.ui.common.runIo
import java.io.File

/**
 * 分享页通用骨架：顶栏 + 固定预览区（[ShareCanvas]）+ 可滚动调整卡片 + 底部保存/分享。
 *
 * 两个子类只负责「取数据 + 搭卡片 + 把状态灌进画布」；保存与分享、控件样式都在这里，
 * 免得票根和海报各写一遍同样的滑条/色板/开关。
 */
abstract class ShareActivityBase : LocalizedActivity() {

    private lateinit var binding: ActivityShareBinding

    protected lateinit var canvas: ShareCanvas

    private val thumbSlots = Semaphore(2)

    private var pendingGallery: ((String) -> Unit)? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val callback = pendingGallery
        if (uri == null) {
            pendingGallery = null
            return@registerForActivityResult
        }
        lifecycleScope.runIo({ SharePhoto.copyFromUri(this, uri) }) { result ->
            pendingGallery = null
            val path = result.getOrNull()
            when {
                path == null -> notify(R.string.share_no_photo, long = true)
                callback != null -> callback(path)
            }
        }
    }

    protected abstract val titleRes: Int

    /** 导出文件名前缀，例如 checkin-share / trip-share。 */
    protected abstract val filePrefix: String

    protected abstract fun createCanvas(): ShareCanvas

    /** 预览是否钉在屏幕顶部：默认不钉，跟页面内容一起滚（票根与海报都一样）。 */
    protected open val pinnedPreview: Boolean = false

    /** 异步取数据、灌画布、建卡片。 */
    protected abstract fun populate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        binding = ActivityShareBinding.inflate(layoutInflater)
        // 保存/分享是滚动内容的一部分，所以底部 inset 补在内容容器上
        applyEdgeToEdge(binding.topBar.root, binding.scroll) {
            // 键盘弹出时把焦点输入框滚进可见区（备注是多行输入，容易被键盘盖住）
            val focused = currentFocus ?: return@applyEdgeToEdge
            binding.scroll.post {
                focused.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, focused.width, focused.height),
                    true,
                )
            }
        }
        setContentView(binding.root)
        binding.root.fadeSlideIn()
        binding.topBar.btnBack.visibility = View.VISIBLE
        binding.topBar.btnBack.setOnClickListener { finish() }
        binding.topBar.title.setText(titleRes)
        canvas = createCanvas()
        binding.previewHost.addView(
            canvas,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )
        if (!pinnedPreview) {
            // 把预览区整体挪进滚动容器的最前面（海报太高，钉住会把卡片区挤没）
            val host = binding.previewHost
            (host.parent as? ViewGroup)?.removeView(host)
            host.setPadding(0, 0, 0, dp(10))
            binding.cards.addView(host, 0)
        }
        populate()
    }

    /**
     * 保存 / 分享按钮：作为最后一块内容放进滚动区（不钉在屏幕底部），
     * 由子类在卡片全部建好后调用。
     */
    protected fun addActions() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.share_save)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
            setOnClickListener { saveImage() }
        })
        row.addView(MaterialButton(this).apply {
            setText(R.string.share_send)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) }
            setOnClickListener { shareImage() }
        })
        binding.cards.addView(row)
    }

    // ────────────────────────── 保存 / 分享 ──────────────────────────

    private fun saveImage() {
        // Android 7–9 的相册写入走 MediaStore.insertImage，需要 WRITE_EXTERNAL_STORAGE；
        // 不请求就会静默失败（提示「保存失败」但用户没有可操作的东西）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val bitmap = canvas.exportBitmap()
        val name = "$filePrefix-${System.currentTimeMillis()}"
        lifecycleScope.runIo({ ShareExporter.saveToGallery(this, bitmap, name) }) { result ->
            bitmap.recycle()
            val ok = result.getOrNull() == true
            notify(if (ok) R.string.share_saved else R.string.share_save_failed, long = !ok)
        }
    }

    /** 老系统的存储权限：授权后重走一次 saveImage（此时已授权，不会再进这条分支）。 */
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) saveImage() else notify(R.string.photo_failed, long = true)
    }

    private fun shareImage() {
        val bitmap = canvas.exportBitmap()
        val name = "$filePrefix-${System.currentTimeMillis()}"
        val subject = getString(titleRes)
        // 写文件在 IO，调起面板回主线程：startActivity 不能在后台线程干
        lifecycleScope.runIo({ ShareExporter.writeShare(applicationContext, bitmap, name) }) { result ->
            bitmap.recycle()
            val uri = result.getOrNull()
            if (uri == null || !ShareExporter.launchShare(this, uri, subject)) {
                notify(R.string.share_send_failed, long = true)
            }
        }
    }

    // ────────────────────────── 卡片骨架 ──────────────────────────

    /**
     * 一张「图标 + 标题 + 内容区」的卡片；返回内容区，调用方往里填控件。
     * [actionRes] 不为空时在标题右侧加一颗右对齐的文字按钮（如「选择照片」）。
     */
    protected fun addCard(
        iconRes: Int,
        titleRes: Int,
        actionRes: Int? = null,
        onAction: (() -> Unit)? = null,
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(Palette.accent(this@ShareActivityBase))
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        })
        header.addView(TextView(this).apply {
            setText(titleRes)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.fg))
            setPadding(dp(6), 0, 0, 0)
        })
        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        if (actionRes != null) {
            header.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(actionRes)
                textSize = 12f
                minWidth = 0
                minHeight = 0
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(8)
                setPadding(dp(10), 0, dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(30),
                )
                setOnClickListener { onAction?.invoke() }
            })
        }
        card.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(body)
        binding.cards.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) },
        )
        return body
    }

    /** 选择器上方的小标题（「字体」「颜色」这类），让每一行都能自己说明是什么。 */
    protected fun sectionLabel(labelRes: Int): TextView = TextView(this).apply {
        setText(labelRes)
        textSize = 12f
        setTextColor(getColor(R.color.text_tertiary))
        setPadding(0, dp(12), 0, dp(2))
    }

    /** 单选胶囊行（带小标题）；[disabled] 里的项灰掉，是否附「暂未开放」由调用方决定。 */
    protected fun chips(
        labelRes: Int,
        options: List<String>,
        selected: Int,
        disabled: IntArray = IntArray(0),
        onPick: (Int) -> Unit,
    ): View {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        block.addView(sectionLabel(labelRes))
        // 字体/格式这类最多 6 项，一屏放不下：横向滚动，别把卡片撑出屏幕
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scroll.addView(
            row,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        options.forEachIndexed { index, label ->
            val enabled = !disabled.contains(index)
            val active = enabled && index == selected
            row.addView(TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                isEnabled = enabled
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setTextColor(
                    ContextCompat.getColor(
                        this@ShareActivityBase,
                        if (active) R.color.on_accent else if (enabled) R.color.text_secondary else R.color.text_tertiary,
                    ),
                )
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    if (active) {
                        setColor(Palette.accent(this@ShareActivityBase))
                    } else {
                        setColor(ContextCompat.getColor(this@ShareActivityBase, R.color.bg))
                        setStroke(dp(1), ContextCompat.getColor(this@ShareActivityBase, R.color.border))
                    }
                }
                alpha = if (enabled) 1f else 0.45f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
                setOnClickListener { if (enabled) onPick(index) }
            })
        }
        block.addView(scroll)
        return block
    }

    /** 滑条行：左侧标签、右侧当前值，拖动时实时回调（预览跟手）。 */
    protected fun slider(labelRes: Int, min: Int, max: Int, value: Int, onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(TextView(this).apply {
            setText(labelRes)
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val valueText = TextView(this).apply {
            text = "$value%"
            textSize = 12f
            gravity = Gravity.END
            setTextColor(getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            maxHeight = dp(3)
            minHeight = dp(3)
            progressDrawable = ContextCompat.getDrawable(this@ShareActivityBase, R.drawable.seekbar_thick)
            thumbTintList = ColorStateList.valueOf(Palette.accent(this@ShareActivityBase))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                    val shown = p + min
                    valueText.text = "$shown%"
                    if (fromUser) onChange(shown)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
        row.addView(valueText)
        return row
    }

    /** 开关行；[topPadding]/[textSize] 给连排的小开关收高度用（时间线那组会传很小的值）。 */
    protected fun switchRow(
        labelRes: Int,
        checked: Boolean,
        topPadding: Int = 8,
        textSize: Float = 13f,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(topPadding), 0, 0)
            minimumHeight = 0
        }
        row.addView(TextView(this).apply {
            setText(labelRes)
            this.textSize = textSize
            setTextColor(getColor(R.color.fg))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val switch = SwitchCompat(this).apply {
            isChecked = checked
            minimumHeight = 0
            minimumWidth = 0
            thumbTintList = ColorStateList.valueOf(Palette.accent(this@ShareActivityBase))
            trackTintList = ColorStateList.valueOf(getColor(R.color.border))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(switch)
        return row
    }

    /** 十档墨色排在**一行**里（点小一点、间距收紧，窄屏也放得下）。 */
    protected fun colorRow(selected: InkColor, onPick: (InkColor) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        InkColor.ALL.forEach { color ->
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color.resolve(this@ShareActivityBase))
                    if (color == selected) setStroke(dp(3), Palette.accent(this@ShareActivityBase))
                }
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) }
                setOnClickListener { onPick(color) }
            })
        }
        return row
    }

    /** 纸底色色板（票根/海报的「背景」卡）：浅色系，保证墨字始终看得清。 */
    protected fun paperRowLive(labelRes: Int, current: () -> PaperColor, onPick: (PaperColor) -> Unit): View {
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fill() {
            host.removeAllViews()
            host.addView(sectionLabel(labelRes))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            PaperColor.ALL.forEach { paper ->
                row.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(paper.resolve())
                        setStroke(
                            dp(if (paper == current()) 3 else 1),
                            if (paper == current()) {
                                Palette.accent(this@ShareActivityBase)
                            } else {
                                ContextCompat.getColor(this@ShareActivityBase, R.color.border)
                            },
                        )
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) }
                    setOnClickListener {
                        onPick(paper)
                        fill()
                    }
                })
            }
            host.addView(row)
        }
        fill()
        return host
    }

    /** 会自己重画选中描边的色板（带小标题）：换色后不需要调用方再管布局。 */
    protected fun colorRowLive(labelRes: Int, current: () -> InkColor, onPick: (InkColor) -> Unit): View {
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fill() {
            host.removeAllViews()
            host.addView(sectionLabel(labelRes))
            host.addView(colorRow(current()) { picked ->
                onPick(picked)
                fill()
            })
        }
        fill()
        return host
    }

    /** 已有照片的缩略图条；[showPickButton] = false 时只展示缩略图（入口挪到卡片标题行）。 */
    protected fun photoStrip(
        paths: List<String>,
        selected: String?,
        onPick: (String) -> Unit,
        onPicked: (String) -> Unit,
        showPickButton: Boolean = true,
    ): View {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (paths.isEmpty()) {
            block.addView(TextView(this).apply {
                setText(R.string.share_no_photo)
                textSize = 12f
                setTextColor(getColor(R.color.text_tertiary))
                setPadding(0, dp(10), 0, 0)
            })
        } else {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            // 照片多时横向排不下：放进 HorizontalScrollView，手指横向拖才滑得动；
            // 纵向拖动仍然交给外层 ScrollView（只在横向位移更大时抢手势）
            val scroller = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                var downX = 0f
                var downY = 0f
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            if (kotlin.math.abs(event.x - downX) > kotlin.math.abs(event.y - downY)) {
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                        }
                    }
                    false
                }
            }
            paths.forEach { path ->
                val tile = FrameLayout(this).apply {
                    setBackgroundResource(R.drawable.bg_photo_tile)
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(8) }
                    foreground = if (path == selected) {
                        GradientDrawable().apply {
                            cornerRadius = dp(10).toFloat()
                            setStroke(dp(3), Palette.accent(this@ShareActivityBase))
                        }
                    } else {
                        null
                    }
                }
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setOnClickListener { onPick(path) }
                }
                tile.addView(image)
                row.addView(tile)
                lifecycleScope.runIo({ thumbSlots.withPermit { SharePhoto.load(path, 256) } }) { result ->
                    result.getOrNull()?.let { if (image.isAttachedToWindow) image.setImageBitmap(it) }
                }
            }
            scroller.addView(row)
            block.addView(scroller)
        }
        if (showPickButton) {
            block.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.share_pick_gallery)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(36),
                ).apply { topMargin = dp(10) }
                setOnClickListener { pickGallery(onPicked) }
            })
        }
        return block
    }

    protected fun pickGallery(onPicked: (String) -> Unit) {
        pendingGallery = onPicked
        pickImage.launch("image/*")
    }

    protected fun loadPhoto(path: String?, apply: (Bitmap?) -> Unit) {
        if (path.isNullOrBlank()) {
            apply(null)
            return
        }
        lifecycleScope.runIo({ thumbSlots.withPermit { SharePhoto.load(path) } }) { result ->
            apply(result.getOrNull())
        }
    }

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** 分享页的图片读取：先走 App 照片目录（PhotoFiles 有 LruCache），不行再按绝对路径直接解码。 */
internal object SharePhoto {

    fun load(path: String?, maxPx: Int = 2048): Bitmap? {
        if (path.isNullOrBlank()) return null
        if (maxPx <= 256) {
            top.qxwkstudio.travel.ui.common.PhotoStore.load(path, maxPx)?.let { return it }
        } else {
            top.qxwkstudio.travel.ui.common.PhotoStore.load(path, 2048)?.let { return it }
        }
        // 兜底解码也必须按 maxPx 采样：副本不在 PhotoFiles 目录里时会走到这里，
        // 直接 decodeFile 会把 12–48MP 原图整个展开
        return runCatching {
            val target = if (maxPx > 0) maxPx else 2048
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > target) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    /** 相册选图：走 PhotoFiles（两段式解码 + EXIF 转正 + JPEG 压缩），不要原样拷原图。 */
    fun copyFromUri(context: Context, uri: Uri): String? =
        runCatching { top.qxwkstudio.travel.data.PhotoFiles.save(context, uri, "share_${System.currentTimeMillis()}") }
            .getOrNull()
}
