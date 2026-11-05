package top.qxwkstudio.travel.ui.trips

import top.qxwkstudio.travel.ui.common.*
import top.qxwkstudio.travel.ui.picker.CityPickerActivity

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.PhotoJanitor
import top.qxwkstudio.travel.data.TripInput
import top.qxwkstudio.travel.data.TripRepo
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.databinding.ActivityTripEditBinding
import top.qxwkstudio.travel.logic.City
import top.qxwkstudio.travel.logic.Dates
import top.qxwkstudio.travel.logic.Limits
import top.qxwkstudio.travel.logic.Sanitize
import top.qxwkstudio.travel.logic.Timeline
import java.util.Calendar
import java.util.UUID

/**
 * 行程编辑器（2.0）：标题 + **双层时间线** + 备注。
 *
 * 双层结构（见 logic/Timeline）：
 *  - 外层一座城市一张卡：城市名（点一下可换）、删除整座城；
 *  - 内层是这座城市里的「活动 / 交通」：类型、时间、内容、花费。
 *
 * 城市**不单独选时间**：城市段的时间范围取它第一条与最后一条事件；保存时全局时间必须非降序，
 * 校验会把「跨城市逆序」也拦下来。
 *
 * 编辑器是代码动态拼的：城市卡里再套事件行，字段随类型变化（活动 / 交通的提示语不同），
 * 用 XML + 多套布局反而更难对齐。
 */
class TripEditActivity : AppCompatActivity() {

    /** 编辑中的一条内层事件。时间存 UTC 毫秒；[text] 是活动内容或交通方式。 */
    private data class DraftEvent(
        val id: String,
        var time: Long,
        var type: Timeline.Type,
        var text: String = "",
        var costCents: Int? = null,
        var note: String = "",
    )

    /** 编辑中的一座城市（外层）。 */
    private data class DraftSegment(
        var city: City,
        val events: MutableList<DraftEvent> = mutableListOf(),
        var companions: String? = null,
        var rating: Int? = null,
        var photoPath: String? = null,
        var note: String? = null,
        val photoName: String = UUID.randomUUID().toString(),
    )

    private lateinit var binding: ActivityTripEditBinding
    private val segments = mutableListOf<DraftSegment>()

    /** 0 = 新增；非 0 = 编辑这条行程的 id。 */
    private var editingId = 0L

    /** 选城页回来要落到哪一段：-1 = 新增一段；否则是 [segments] 的下标（换城市用）。 */
    private var pickingSegment = -1

    /** 照片要落到哪一段（-1 = 没有待处理的目标）。 */
    private var photoSegment = -1

    /** 选城 / 选照片进行中：防止连点开出多个选择器。 */
    private var picking = false
    private var photoPicking = false

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        picking = false
        val target = pickingSegment
        pickingSegment = -1
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val name = data.getStringExtra(CityPickerActivity.EXTRA_NAME).orEmpty()
        val lat = data.getDoubleExtra(CityPickerActivity.EXTRA_LAT, Double.NaN)
        val lng = data.getDoubleExtra(CityPickerActivity.EXTRA_LNG, Double.NaN)
        if (name.isBlank() || lat.isNaN() || lng.isNaN()) return@registerForActivityResult
        val city = CityStore.all(this).firstOrNull { it.name == name } ?: return@registerForActivityResult
        val picked = city.copy(lat = lat, lng = lng)

        if (target < 0) {
            // 新城市：默认带一条「活动」，用户点进去就能填；时间接在已有事件之后
            segments += DraftSegment(picked, mutableListOf(newEvent()))
        } else if (target in segments.indices) {
            segments[target].city = picked
        }
        renderSegments()
    }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val index = photoSegment
        photoSegment = -1
        photoPicking = false
        if (uris.isNullOrEmpty() || index !in segments.indices) return@registerForActivityResult
        val segment = segments[index]
        val current = segment.photoPath?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        val remain = 3 - current.size
        if (remain <= 0) return@registerForActivityResult
        val picked = uris.take(remain)
        lifecycleScope.runIo({
            picked.mapIndexedNotNull { i, uri ->
                PhotoStore.save(this, uri, "${segment.photoName}_${current.size + i + 1}")
            }
        }) { result ->
            val saved = result.getOrNull().orEmpty()
            if (saved.isEmpty()) {
                notify(getString(R.string.photo_failed), long = true)
                return@runIo
            }
            segment.photoPath = (current + saved).joinToString(",")
            renderSegments()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题色叠层：必须在 setContentView 之前应用，Material 控件的颜色取自主题
        Palette.apply(this)
        binding = ActivityTripEditBinding.inflate(layoutInflater)
        val b = binding
        applyEdgeToEdge(b.topBar.root, b.scroll) {
            // 等这次 padding 布局生效后再滚，否则算出来的目标位置还是键盘弹出前的
            val focused = currentFocus ?: return@applyEdgeToEdge
            b.scroll.post {
                focused.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, focused.width, focused.height),
                    true,
                )
            }
        }
        setContentView(b.root)

        editingId = intent.getLongExtra(EXTRA_ID, 0L)
        val editing = editingId != 0L
        b.topBar.title.setText(if (editing) R.string.trip_edit_title else R.string.trip_new_title)
        b.topBar.btnBack.visibility = View.VISIBLE
        b.topBar.btnBack.setOnClickListener { confirmDiscard() }
        // 编辑页右上角不放图标：删除统一在详情页做
        b.topBar.btnDelete.visibility = View.GONE

        b.btnAddEvent.setOnClickListener { startPickCity(-1) }
        b.btnCancel.setOnClickListener { confirmDiscard() }
        b.btnSave.setOnClickListener { save() }
        b.topBar.btnSaveIcon.visibility = View.VISIBLE
        b.topBar.btnSaveIcon.setOnClickListener { save() }

        // 系统返回（手势/返回键）也要走「未保存就确认」这条
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmDiscard()
        })

        bindCounters()

        if (editing) {
            loadExisting()
        } else {
            renderSegments()
            markClean()
        }
    }

    private fun bindCounters() {
        binding.textTitleCounter.text = getString(R.string.trip_counter, 0, Limits.SHORT)
        binding.textNoteCounter.text = getString(R.string.trip_counter, 0, Limits.NOTE)
        binding.inputTitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                binding.textTitleCounter.text = getString(R.string.trip_counter, s.length, Limits.SHORT)
            }
        })
        binding.inputNote.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                binding.textNoteCounter.text = getString(R.string.trip_counter, s.length, Limits.NOTE)
            }
        })
    }

    private fun loadExisting() {
        lifecycleScope.runIo({
            val db = AppDatabase.get(this)
            TripRepo.byId(db, editingId)
        }) { result ->
            val (entity, timeline) = result.getOrNull() ?: run {
                notify(getString(R.string.common_error), long = true)
                finish()
                return@runIo
            }
            binding.inputTitle.setText(entity.title)
            binding.inputNote.setText(entity.note.orEmpty())
            val cities = CityStore.all(this).associateBy { it.name }
            timeline.segments.forEach { segment ->
                val city = cities[segment.city] ?: return@forEach
                segments += DraftSegment(
                    city = city,
                    companions = Sanitize.line(segment.companions, Limits.SHORT).ifBlank { null },
                    rating = segment.rating,
                    photoPath = segment.photoPath,
                    note = segment.note,
                    events = segment.events.map { event ->
                        DraftEvent(
                            id = event.id,
                            time = event.time,
                            type = event.type,
                            text = event.text,
                            costCents = event.costCents,
                            note = event.note.orEmpty(),
                        )
                    }.toMutableList(),
                )
            }
            renderSegments()
            markClean()
        }
    }

    // ────────────────────────── 编辑器 ──────────────────────────

    private fun startPickCity(segmentIndex: Int) {
        if (picking) return
        picking = true
        pickingSegment = segmentIndex
        picker.launch(Intent(this, CityPickerActivity::class.java))
    }

    private fun renderSegments() {
        val container = binding.eventContainer
        container.removeAllViews()
        segments.forEachIndexed { index, segment ->
            container.addView(segmentCard(index, segment))
        }
        if (segments.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.trip_add_city_hint)
                setTextColor(getColor(R.color.text_tertiary))
                textSize = 13f
                setPadding(0, dp(8), 0, dp(20))
            })
        }
    }

    /** 外层：一座城市一张卡；顺序同打卡编辑页（城市 / 时间线 / 同行人 / 照片 / 评分 / 备注）。 */
    private fun segmentCard(index: Int, segment: DraftSegment): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(0, dp(8), 0, dp(20))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        // 城市标题
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), 0)
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_city)
            imageTintList = android.content.res.ColorStateList.valueOf(Palette.accent(this@TripEditActivity))
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        header.addView(TextView(this).apply {
            text = segment.city.name
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.fg))
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startPickCity(index) }
        })
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete)
            background = null
            setColorFilter(getColor(R.color.destructive))
            contentDescription = getString(R.string.trip_remove_city)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener {
                segments.removeAt(index)
                renderSegments()
            }
        })
        card.addView(header)

        // 城市标题下的只读统计：时间跨度 / 项数 / 金额
        val times = segment.events.map { it.time }.sorted()
        val spanText = if (times.isEmpty()) "-" else Dates.ymd(times.first()) + " → " + Dates.ymd(times.last())
        val daysSum = segmentDays(segment)
        card.addView(summaryRow(R.drawable.ic_span, spanText + " · " + daysSum + " 天"))
        card.addView(summaryRow(R.drawable.ic_trips, segment.events.size.toString() + " 项"))
        val costText = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.text_tertiary))
        }
        fun costFormula(): String {
            val sum = segment.events.sumOf { it.costCents ?: 0 }
            return if (segment.events.isEmpty()) {
                "￥0"
            } else {
                segment.events.joinToString(" + ") { "￥" + ((it.costCents ?: 0) / 100) } + " = ￥" + (sum / 100)
            }
        }
        costText.text = costFormula()
        // 金额输入/步进时只刷这一行，不整页重绘（避免输入框失焦）
        fun refreshMoney() { costText.text = costFormula() }
        card.addView(summaryRow(R.drawable.ic_wallet, costText))
        // 第二个标题：时间线
        card.addView(fieldLabel(R.drawable.ic_span, getString(R.string.trip_segment_timeline_label)))

        // 时间线事件
        if (segment.events.isEmpty()) {
            card.addView(TextView(this).apply {
                text = getString(R.string.trip_segment_empty)
                setTextColor(getColor(R.color.text_tertiary))
                textSize = 12f
                setPadding(dp(16), dp(6), dp(16), 0)
            })
        } else {
            segment.events.forEachIndexed { eventIndex, event ->
                card.addView(eventBlock(index, eventIndex, event) { refreshMoney() })
            }
        }
        // 时间线底部的圆形「+」：点一下加一条活动
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        addRow.addView(android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(36))
            addView(View(this@TripEditActivity).apply {
                setBackgroundColor(getColor(R.color.border))
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL)
            })
            addView(TextView(this@TripEditActivity).apply {
                text = "+"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.on_accent))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Palette.accent(this@TripEditActivity))
                }
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
                setOnClickListener {
                    segment.events += newEvent()
                    renderSegments()
                }
            })
        })
        card.addView(addRow)

        card.addView(dividerLine())

        // 同行人
        card.addView(fieldLabel(R.drawable.ic_person, getString(R.string.companions_label)))
        card.addView(EditText(this).apply {
            hint = getString(R.string.companions_hint)
            setText(segment.companions.orEmpty())
            textSize = 14f
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(23))
            background = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.bg_input_box)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8); marginStart = dp(16); marginEnd = dp(16) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    segment.companions = s.toString().trim().ifBlank { null }
                }
            })
        })

        // 照片（与打卡同一套：固定宽度瓦片 + 加号）
        card.addView(dividerLine())
        card.addView(fieldLabel(R.drawable.ic_camera, getString(R.string.photo_label)))
        card.addView(photoTiles(index, segment))

        // 评分（1~10 拉条）
        card.addView(dividerLine())
        card.addView(fieldLabel(R.drawable.ic_star, getString(R.string.rating_label)))
        card.addView(ratingRow(segment))

        // 备注
        card.addView(dividerLine())
        card.addView(fieldLabel(R.drawable.ic_note, getString(R.string.checkin_note)))
        card.addView(EditText(this).apply {
            hint = getString(R.string.trip_segment_note_hint)
            setText(segment.note.orEmpty())
            gravity = Gravity.TOP
            textSize = 14f
            maxLines = 3
            minLines = 3
            background = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.bg_input_box)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8); marginStart = dp(16); marginEnd = dp(16) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    segment.note = s.toString().trim().ifBlank { null }
                }
            })
        })
        return card
    }

    private fun summaryRow(icon: Int, text: String): LinearLayout {
        return summaryRow(icon, TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(getColor(R.color.text_tertiary))
        })
    }

    private fun summaryRow(icon: Int, value: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(6), dp(16), 0)
        addView(ImageView(this@TripEditActivity).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@TripEditActivity, R.color.text_tertiary),
            )
            layoutParams = LinearLayout.LayoutParams(dp(13), dp(13))
        })
        value.setPadding(dp(5), 0, 0, 0)
        addView(value)
    }
    private fun fieldLabel(icon: Int, text: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), 0)
        addView(ImageView(this@TripEditActivity).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(
                Palette.accent(this@TripEditActivity),
            )
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        })
        addView(TextView(this@TripEditActivity).apply {
            this.text = text
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@TripEditActivity, R.color.fg))
            setPadding(dp(6), 0, 0, 0)
        })
    }

    private fun dividerLine(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.border))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply { topMargin = dp(20); marginStart = dp(16); marginEnd = dp(16) }
    }

    /** 本段天数：最早与最晚事件按本地日期差 +1。 */
    private fun segmentDays(segment: DraftSegment): Int {
        if (segment.events.isEmpty()) return 0
        val first = segment.events.minOf { it.time }
        val last = segment.events.maxOf { it.time }
        val c1 = java.util.Calendar.getInstance().apply { timeInMillis = first }
        val c2 = java.util.Calendar.getInstance().apply { timeInMillis = last }
        val d1 = c1.get(java.util.Calendar.YEAR) * 366 + c1.get(java.util.Calendar.DAY_OF_YEAR)
        val d2 = c2.get(java.util.Calendar.YEAR) * 366 + c2.get(java.util.Calendar.DAY_OF_YEAR)
        return (d2 - d1) + 1
    }

    /** 城市段照片：最多 3 张，固定宽度；点加号多选，× 删除。 */
    private fun photoTiles(index: Int, segment: DraftSegment): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val paths = segment.photoPath?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        paths.take(3).forEachIndexed { i, path ->
            val tile = android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(8) }
                setBackgroundResource(R.drawable.bg_photo_tile)
                clipToOutline = true
            }
            tile.addView(ImageView(this).apply {
                setImageBitmap(PhotoStore.load(path, maxPx = 256))
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })
            tile.addView(TextView(this).apply {
                text = "×"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x99000000.toInt())
                }
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END)
                    .apply { topMargin = dp(4); marginEnd = dp(4) }
                setOnClickListener {
                    val left = paths.toMutableList().also { it.removeAt(i) }
                    segment.photoPath = left.joinToString(",").ifBlank { null }
                    renderSegments()
                }
            })
            row.addView(tile)
        }
        if (paths.size < 3) {
            row.addView(android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
                setBackgroundResource(R.drawable.bg_photo_add)
                setOnClickListener {
                    if (photoPicking) return@setOnClickListener
                    photoPicking = true
                    photoSegment = index
                    photoPicker.launch("image/*")
                }
                addView(TextView(this@TripEditActivity).apply {
                    text = "+"
                    textSize = 26f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@TripEditActivity, R.color.text_tertiary))
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                })
            })
        }
        return row
    }

    /** 城市评分：1~10 拉条，与打卡编辑页同一套。 */
    private fun ratingRow(segment: DraftSegment): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), 0)
        }
        val value = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.END
            setTextColor(getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT)
            text = segment.rating?.let { getString(R.string.rating_value, it) } ?: getString(R.string.rating_unrated)
        }
        val seek = android.widget.SeekBar(this).apply {
            max = 9
            progress = (segment.rating ?: 1) - 1
            maxHeight = dp(3)
            minHeight = dp(3)
            progressDrawable = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.seekbar_thick)
            thumbTintList = android.content.res.ColorStateList.valueOf(Palette.accent(this@TripEditActivity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    segment.rating = p + 1
                    value.text = getString(R.string.rating_value, p + 1)
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
            })
        }
        row.addView(seek)
        row.addView(value)
        return row
    }
    private fun addAction(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = "+ $text"
        textSize = 13f
        setTextColor(Palette.accent(this@TripEditActivity))
        setPadding(0, dp(4), dp(16), dp(4))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** 内层：一条事件（类型胶囊 + 时间 + 内容 + 花费）。 */
    private fun eventBlock(
        segmentIndex: Int,
        eventIndex: Int,
        event: DraftEvent,
        onMoneyChanged: () -> Unit,
    ): View {
        val bullet = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT)
            addView(View(this@TripEditActivity).apply {
                setBackgroundColor(getColor(R.color.border))
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            })
            addView(View(this@TripEditActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Palette.accent(this@TripEditActivity))
                }
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                    .apply { topMargin = dp(18) }
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(0), dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // 第一行：日期 / 时间 / 类型 / 删除
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(Chips.tag(this, Dates.ymd(event.time)) { pickDate(segmentIndex, eventIndex) })
        top.addView(Chips.tag(this, hm(event.time)) { pickTime(segmentIndex, eventIndex) }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) }
        })
        top.addView(typePill(event) {
            chooseEventType(segmentIndex, eventIndex)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) }
        })
        top.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        top.addView(TextView(this).apply {
            text = "▲"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(getColor(if (eventIndex > 0) R.color.text_secondary else R.color.text_tertiary))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(32))
            setOnClickListener {
                if (eventIndex <= 0) return@setOnClickListener
                val list = segments[segmentIndex].events
                val item = list.removeAt(eventIndex)
                list.add(eventIndex - 1, item)
                renderSegments()
            }
        })
        top.addView(TextView(this).apply {
            text = "▼"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(getColor(if (eventIndex < segments[segmentIndex].events.lastIndex) R.color.text_secondary else R.color.text_tertiary))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(32))
            setOnClickListener {
                val list = segments[segmentIndex].events
                if (eventIndex >= list.lastIndex) return@setOnClickListener
                val item = list.removeAt(eventIndex)
                list.add(eventIndex + 1, item)
                renderSegments()
            }
        })
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete)
            background = null
            setColorFilter(getColor(R.color.destructive))
            contentDescription = getString(R.string.common_delete)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener {
                segments[segmentIndex].events.removeAt(eventIndex)
                renderSegments()
            }
        })
        box.addView(top)

        // 第二行：活动内容 + 金额
        val second = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(8), 0, 0)
        }
        val textCounter = TextView(this).apply {
            text = getString(R.string.trip_counter, event.text.length, Limits.SHORT)
            textSize = 11f
            gravity = Gravity.END
            setTextColor(getColor(R.color.text_tertiary))
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(EditText(this).apply {
            hint = getString(
                when (event.type) {
                    Timeline.Type.RIDE -> R.string.trip_event_transport_hint
                    Timeline.Type.VISIT -> R.string.trip_event_visit_hint
                    else -> R.string.trip_event_activity_hint
                },
            )
            setText(event.text)
            textSize = 14f
            maxLines = 1
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(Limits.SHORT))
            background = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.bg_input_box)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40),
            ).apply { bottomMargin = dp(2) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    event.text = s.toString().trim()
                    textCounter.text = getString(R.string.trip_counter, s.length, Limits.SHORT)
                }
            })
        })
        textColumn.addView(textCounter)
        second.addView(textColumn)
        val costBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.bg_input_box)
            setPadding(dp(8), 0, dp(2), 0)
            layoutParams = LinearLayout.LayoutParams(dp(118), dp(40)).apply { marginStart = dp(8) }
        }
        val costInput = EditText(this).apply {
            hint = getString(R.string.trip_event_cost_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(event.costCents?.let { (it / 100.0).toString() } ?: "")
            textSize = 13f
            maxLines = 1
            background = null
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    val raw = s.toString().trim()
                    event.costCents = if (raw.isEmpty()) null else ((raw.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 20_000_000.0) * 100).toInt()
                    onMoneyChanged()
                }
            })
        }
        val stepper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun step(delta: Int) {
            val next = ((event.costCents ?: 0) + delta).coerceAtLeast(0)
            event.costCents = next
            costInput.setText((next / 100.0).toString())
            costInput.setSelection(costInput.text?.length ?: 0)
            onMoneyChanged()
        }
        stepper.addView(TextView(this).apply {
            text = "+"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Palette.accent(this@TripEditActivity))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16))
            setOnClickListener { step(100) }
        })
        stepper.addView(TextView(this).apply {
            text = "-"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Palette.accent(this@TripEditActivity))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16))
            setOnClickListener { step(-100) }
        })
        costBox.addView(costInput)
        costBox.addView(stepper)
        second.addView(costBox)
        box.addView(second)

        // 第三行：备注
        val noteCounter = TextView(this).apply {
            text = getString(R.string.trip_counter, event.note.length, Limits.NOTE)
            textSize = 11f
            gravity = Gravity.END
            setTextColor(getColor(R.color.text_tertiary))
        }
        box.addView(EditText(this).apply {
            hint = getString(R.string.trip_event_note_hint)
            setText(event.note)
            textSize = 13f
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 6
            filters = arrayOf(android.text.InputFilter.LengthFilter(Limits.NOTE))
            background = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.bg_input_box)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    event.note = s.toString().trim()
                    noteCounter.text = getString(R.string.trip_counter, s.length, Limits.NOTE)
                }
            })
        })
        box.addView(noteCounter)

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(bullet)
            addView(box)
        }
    }
    private fun typePill(event: DraftEvent, onClick: () -> Unit): TextView =
        Chips.tag(
            this,
            getString(
                when (event.type) {
                    Timeline.Type.RIDE -> R.string.trip_type_ride
                    Timeline.Type.VISIT -> R.string.trip_type_visit
                    else -> R.string.trip_type_activity
                },
            ) + " ▾",
            muted = false,
            onClick = onClick,
        )
    /** 点击类型徽章：弹窗选活动 / 交通 / 到访。 */
    private fun chooseEventType(segmentIndex: Int, eventIndex: Int) {
        val event = segments.getOrNull(segmentIndex)?.events?.getOrNull(eventIndex) ?: return
        val types = arrayOf(Timeline.Type.ACTIVITY, Timeline.Type.RIDE, Timeline.Type.VISIT)
        val labels = types.map {
            getString(
                when (it) {
                    Timeline.Type.RIDE -> R.string.trip_type_ride
                    Timeline.Type.VISIT -> R.string.trip_type_visit
                    else -> R.string.trip_type_activity
                },
            )
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trip_event_type)
            .setSingleChoiceItems(labels, types.indexOf(event.type)) { dialog, which ->
                dialog.dismiss()
                event.type = types[which]
                renderSegments()
            }
            .show()
    }

    private fun pickDate(segmentIndex: Int, eventIndex: Int) {
        val event = eventAt(segmentIndex, eventIndex) ?: return
        val calendar = Calendar.getInstance().apply { timeInMillis = event.time }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                calendar.set(year, month, day)
                event.time = calendar.timeInMillis
                renderSegments()
            },
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun pickTime(segmentIndex: Int, eventIndex: Int) {
        val event = eventAt(segmentIndex, eventIndex) ?: return
        val calendar = Calendar.getInstance().apply { timeInMillis = event.time }
        TimePickerDialog(
            this,
            { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                event.time = calendar.timeInMillis
                renderSegments()
            },
            calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true,
        ).show()
    }

    private fun eventAt(segmentIndex: Int, eventIndex: Int): DraftEvent? {
        val segment = segments.getOrNull(segmentIndex) ?: return null
        return segment.events.getOrNull(eventIndex)
    }

    private fun hm(millis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return "%02d:%02d".format(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
    }

    /** 新事件的时间接在最后一条之后（没有就取现在），避免一添加就逆序。 */
    private fun newEvent(type: Timeline.Type = Timeline.Type.ACTIVITY): DraftEvent {
        val last = segments.flatMap { it.events }.maxOfOrNull { it.time }
        return DraftEvent(
            id = UUID.randomUUID().toString(),
            time = last ?: System.currentTimeMillis(),
            type = type,
        )
    }

    // ────────────────────────── 保存/删除 ──────────────────────────

    private fun save() {
        val title = Sanitize.line(binding.inputTitle.text?.toString(), Limits.SHORT)
        if (title.isEmpty()) {
            showError(getString(R.string.trip_err_title))
            return
        }
        val timeline = Timeline(
            segments = segments.map { segment ->
                Timeline.Segment(
                    city = segment.city.name,
                    cityCode = segment.city.adcode,
                    province = segment.city.province,
                    events = segment.events.map { it.toEvent() },
                    companions = segment.companions,
                    rating = segment.rating,
                    photoPath = segment.photoPath,
                    note = Sanitize.text(segment.note, Limits.NOTE).ifBlank { null },
                )
            },
        )
        timeline.validate()?.let {
            showError(it)
            return
        }

        val input = TripInput(
            title = title,
            note = Sanitize.text(binding.inputNote.text?.toString(), Limits.NOTE).ifBlank { null },
            timeline = timeline,
        )
        val id = editingId
        binding.btnSave.isEnabled = false
        binding.topBar.btnSaveIcon.isEnabled = false
        lifecycleScope.runIo({
            val db = AppDatabase.get(this)
            val lookup = CityStore.all(this).associateBy { it.name }
            if (id == 0L) TripRepo.create(db, input, { lookup[it] })
            else TripRepo.update(db, id, input, { lookup[it] })
        }) { result ->
            binding.btnSave.isEnabled = true
            binding.topBar.btnSaveIcon.isEnabled = true
            val error = result.exceptionOrNull()
            if (error != null) {
                showError(error.message ?: getString(R.string.common_error))
                return@runIo
            }
            notify(R.string.trip_saved)
            PhotoJanitor.sweepAsync(this)
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun confirmDiscard() {
        if (!isDirty()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setPositiveButton(R.string.discard_positive) { _, _ -> finish() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private var initialSnapshot: String = ""

    private fun markClean() {
        initialSnapshot = snapshot()
    }

    private fun snapshot(): String =
        binding.inputTitle.text.toString() + "|" + binding.inputNote.text.toString() + "|" +
            segments.joinToString(";") { segment ->
                segment.city.name + "|" + segment.companions + "|" + segment.note + "|" + segment.rating + "|" + segment.photoPath + "|" +
                    segment.events.joinToString(",") { "${it.id}:${it.time}:${it.type}:${it.note}" }
            }

    private fun isDirty(): Boolean = initialSnapshot.isNotEmpty() && snapshot() != initialSnapshot

    private fun DraftEvent.toEvent(): Timeline.Event {
        val safeText = Sanitize.line(text, Limits.SHORT)
        val safeNote = Sanitize.text(note, Limits.NOTE).ifBlank { null }
        val tzOffsetMinutes = java.util.TimeZone.getDefault().getOffset(time) / 60_000
        return Timeline.Event(
            id = id,
            time = time,
            type = type,
            tz = tzOffsetMinutes,
            text = safeText,
            costCents = costCents,
            note = safeNote,
        )
    }

    /** 所有错误提示统一走 Toast（见 ui/Notify.kt）。 */
    private fun showError(message: String) {
        notify(message, long = true)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ID = "trip_id"

        /** 新增传 0，编辑传行程 id。 */
        fun intent(context: android.content.Context, tripId: Long = 0L): Intent =
            Intent(context, TripEditActivity::class.java).putExtra(EXTRA_ID, tripId)
    }
}
