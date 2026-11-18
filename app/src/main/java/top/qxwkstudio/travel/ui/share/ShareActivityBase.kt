package top.qxwkstudio.travel.ui.share

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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

    /** 异步取数据、灌画布、建卡片。 */
    protected abstract fun populate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        binding = ActivityShareBinding.inflate(layoutInflater)
        applyEdgeToEdge(binding.topBar.root, binding.bottomBar)
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
        binding.btnSave.setOnClickListener { saveImage() }
        binding.btnShare.setOnClickListener { shareImage() }
        populate()
    }

    // ────────────────────────── 保存 / 分享 ──────────────────────────

    private fun saveImage() {
        val bitmap = canvas.exportBitmap()
        val name = "$filePrefix-${System.currentTimeMillis()}"
        lifecycleScope.runIo({ ShareExporter.saveToGallery(this, bitmap, name) }) { result ->
            bitmap.recycle()
            val ok = result.getOrNull() == true
            notify(if (ok) R.string.share_saved else R.string.share_save_failed, long = !ok)
        }
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

    /** 一张「图标 + 标题 + 内容区」的卡片；返回内容区，调用方往里填控件。 */
    protected fun addCard(iconRes: Int, titleRes: Int): LinearLayout {
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

    /** 单选胶囊行；[disabled] 里的项灰掉并附「暂未开放」的说明由调用方决定。 */
    protected fun chips(
        options: List<String>,
        selected: Int,
        disabled: IntArray = IntArray(0),
        onPick: (Int) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
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
        return row
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

    protected fun switchRow(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(TextView(this).apply {
            setText(labelRes)
            textSize = 13f
            setTextColor(getColor(R.color.fg))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val switch = SwitchCompat(this).apply {
            isChecked = checked
            thumbTintList = ColorStateList.valueOf(Palette.accent(this@ShareActivityBase))
            trackTintList = ColorStateList.valueOf(getColor(R.color.border))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(switch)
        return row
    }

    /** 四档墨色：选中档加一圈主题色描边。 */
    protected fun colorRow(selected: InkColor, onPick: (InkColor) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        InkColor.ALL.forEach { color ->
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color.resolve(this@ShareActivityBase))
                    if (color == selected) setStroke(dp(3), Palette.accent(this@ShareActivityBase))
                }
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    marginEnd = dp(10)
                }
                setOnClickListener { onPick(color) }
            }
            row.addView(dot)
        }
        return row
    }

    /** 会自己重画选中描边的色板：换色后不需要调用方再管布局。 */
    protected fun colorRowLive(current: () -> InkColor, onPick: (InkColor) -> Unit): View {
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fill() {
            host.removeAllViews()
            host.addView(colorRow(current()) { picked ->
                onPick(picked)
                fill()
            })
        }
        fill()
        return host
    }

    /** 已有照片的缩略图条 + 「从相册选择」。 */
    protected fun photoStrip(
        paths: List<String>,
        selected: String?,
        onPick: (String) -> Unit,
        onPicked: (String) -> Unit,
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
            paths.take(6).forEach { path ->
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
            block.addView(row)
        }
        block.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.share_pick_gallery)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36),
            ).apply { topMargin = dp(10) }
            setOnClickListener { pickGallery(onPicked) }
        })
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
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    fun copyFromUri(context: Context, uri: Uri): String? = runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val target = File(dir, "picked-${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    }.getOrNull()
}
