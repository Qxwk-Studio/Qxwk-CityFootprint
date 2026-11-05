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
        var transport: String? = null,
        var note: String? = null,
        val photoName: String = UUID.randomUUID().toString(),
    )

    private lateinit var binding: ActivityTripEditBinding
    private val segments = mutableListOf<DraftSegment>()

    /** 0 = 新增；非 0 = 编辑这条行程的 id。 */
    private var editingId = 0L

    /** 选城页回来要落到哪一段：-1 = 新增一段；否则是 [segments] 的下标（换城市用）。 */
    private var pickingSegment = -1

    /** 城市 → 现存最早打卡时间（首访判定用）；null/缺省表示还没查到或没有打卡。 */
    private var firstVisitTimes: Map<String, Long> = emptyMap()

    /** 照片要落到哪一段（-1 = 没有待处理的目标）。 */
    private var photoSegment = -1

    /** 选城 / 选照片进行中：防止连点开出多个选择器。 */
    private var picking = false
    private var photoPicking = false

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val index = photoSegment
        photoSegment = -1
        if (uri == null || index !in segments.indices) return@registerForActivityResult
        val segment = segments[index]
        lifecycleScope.runIo({ PhotoStore.save(this, uri, segment.photoName) }) { result ->
            val path = result.getOrNull()
            if (path == null) {
                notify(getString(R.string.photo_failed), long = true)
                return@runIo
            }
            PhotoStore.delete(segment.photoPath)
            segment.photoPath = path
            renderSegments()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题色叠层：必须在 setContentView 之前应用，Material 控件的颜色取自主题
        theme.applyStyle(Palette.selected(this).overlay, true)
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
        b.topBar.btnDelete.visibility = if (editing) View.VISIBLE else View.GONE
        b.topBar.btnDelete.setOnClickListener { confirmDelete() }

        b.btnAddEvent.setOnClickListener { startPickCity(-1) }
        b.btnCancel.setOnClickListener { confirmDiscard() }
        b.btnSave.setOnClickListener { save() }

        // 系统返回（手势/返回键）也要走「未保存就确认」这条
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmDiscard()
        })

        bindCounters()
        loadFirstVisitTimes()

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
                    companions = segment.companions,
                    rating = segment.rating,
                    photoPath = segment.photoPath,
                    transport = segment.transport,
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
                setPadding(0, dp(8), 0, dp(8))
            })
        }
    }

    /** 外层：一座城市一张卡（城市名 + 内层事件 + 添加按钮）。 */
    private fun segmentCard(index: Int, segment: DraftSegment): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        // 城市行：图标 + 城市名（点一下换城市）+ 删除整段
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
            setColorFilter(getColor(R.color.text_tertiary))
            contentDescription = getString(R.string.trip_remove_city)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                segments.removeAt(index)
                renderSegments()
            }
        })
        // 首访角标：这座城市现存最早的打卡就是本段时显示
        if (isFirstVisit(segment)) {
            header.addView(TextView(this).apply {
                text = getString(R.string.first_visit)
                textSize = 11f
                setTextColor(getColor(R.color.first_visit))
                setBackgroundResource(R.drawable.bg_first_visit)
                setPadding(dp(7), dp(2), dp(7), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(4) }
            })
        }
        card.addView(header)
        card.addView(segmentOptions(index, segment))

        // 内层事件
        if (segment.events.isEmpty()) {
            card.addView(TextView(this).apply {
                text = getString(R.string.trip_segment_empty)
                setTextColor(getColor(R.color.text_tertiary))
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })
        } else {
            segment.events.forEachIndexed { eventIndex, event ->
                card.addView(eventBlock(index, eventIndex, event))
            }
        }

        // 添加活动 / 添加交通
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(addAction(getString(R.string.trip_add_activity)) {
            segment.events += newEvent()
            renderSegments()
        })
        actions.addView(addAction(getString(R.string.trip_add_ride)) {
            segment.events += newEvent(Timeline.Type.RIDE)
            renderSegments()
        })
        card.addView(actions)
        return card
    }

    /** 城市段的可选项目：同行人 / 评分 / 照片（与手动打卡同一套字段）。 */
    private fun segmentOptions(index: Int, segment: DraftSegment): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        box.addView(EditText(this).apply {
            hint = getString(R.string.trip_transport_hint)
            setText(segment.transport.orEmpty())
            textSize = 14f
            maxLines = 1
            setPadding(0, dp(6), 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    segment.transport = s.toString().trim().ifBlank { null }
                }
            })
        })
        box.addView(EditText(this).apply {
            hint = getString(R.string.trip_segment_note_hint)
            setText(segment.note.orEmpty())
            textSize = 13f
            maxLines = 2
            setPadding(0, dp(6), 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    segment.note = s.toString().trim().ifBlank { null }
                }
            })
        })
        box.addView(EditText(this).apply {
            hint = getString(R.string.companions_hint)
            setText(segment.companions.orEmpty())
            textSize = 14f
            maxLines = 1
            setPadding(0, dp(6), 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    segment.companions = s.toString().trim().ifBlank { null }
                }
            })
        })
        val starsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        for (star in 1..5) {
            starsRow.addView(ImageView(this).apply {
                setImageResource(if ((segment.rating ?: 0) >= star) R.drawable.ic_star else R.drawable.ic_star_outline)
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.first_visit))
                contentDescription = getString(R.string.rating_label)
                setPadding(dp(2), dp(2), dp(2), dp(2))
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                setOnClickListener {
                    segment.rating = if (segment.rating == star) null else star
                    renderSegments()
                }
            })
        }
        box.addView(starsRow)

        val photoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val thumb = PhotoStore.load(segment.photoPath)
        if (thumb != null) {
            photoRow.addView(ImageView(this).apply {
                setImageBitmap(thumb)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(8) }
            })
        }
        photoRow.addView(addAction(getString(if (thumb == null) R.string.photo_pick else R.string.photo_change)) {
            if (photoPicking) return@addAction
            photoPicking = true
            photoSegment = index
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        box.addView(photoRow)
        return box
    }

    /** 首访：这座城现存最早的打卡时间不早于本段第一条事件，就说明本段是首访。 */
    private fun isFirstVisit(segment: DraftSegment): Boolean {
        val earliest = segment.events.minOfOrNull { it.time } ?: return false
        val known = firstVisitTimes[segment.city.name] ?: return true
        return earliest <= known
    }

    private fun loadFirstVisitTimes() {
        lifecycleScope.runIo({
            AppDatabase.get(this).checkins().listChronological()
        }) { result ->
            val list = result.getOrNull() ?: return@runIo
            firstVisitTimes = list.groupBy { it.cityName }.mapValues { (_, group) -> group.minOf { it.checkinAt } }
            renderSegments()
        }
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
    private fun eventBlock(segmentIndex: Int, eventIndex: Int, event: DraftEvent): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(typePill(event, Timeline.Type.ACTIVITY) {
            event.type = Timeline.Type.ACTIVITY
            renderSegments()
        })
        // 注意：TextView 还没加进父容器时 layoutParams 是 null，不能直接强转 ——
        // 之前这一行在「添加城市」后渲染首条事件时抛 NPE，进程被杀、看起来像「回退主页面」
        top.addView(typePill(event, Timeline.Type.RIDE) {
            event.type = Timeline.Type.RIDE
            renderSegments()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) }
        })
        top.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        top.addView(Chips.tag(this, Dates.ymd(event.time)) {
            pickDate(segmentIndex, eventIndex)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
        })
        top.addView(Chips.tag(this, hm(event.time)) {
            pickTime(segmentIndex, eventIndex)
        })
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete)
            background = null
            setColorFilter(getColor(R.color.destructive))
            contentDescription = getString(R.string.common_delete)
            setPadding(dp(6), dp(6), 0, dp(6))
            setOnClickListener {
                segments[segmentIndex].events.removeAt(eventIndex)
                renderSegments()
            }
        })
        box.addView(top)

        box.addView(EditText(this).apply {
            hint = getString(
                if (event.type == Timeline.Type.RIDE) R.string.trip_event_transport_hint
                else R.string.trip_event_activity_hint,
            )
            setText(event.text)
            textSize = 14f
            maxLines = 1
            setPadding(0, dp(6), 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    event.text = s.toString().trim()
                }
            })
        })

        box.addView(EditText(this).apply {
            hint = getString(R.string.trip_event_note_hint)
            setText(event.note)
            textSize = 13f
            maxLines = 1
            setPadding(0, dp(6), 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    event.note = s.toString().trim()
                }
            })
        })

        val costRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        costRow.addView(EditText(this).apply {
            hint = getString(R.string.trip_event_cost_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(event.costCents?.let { (it / 100.0).toString() } ?: "")
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    val raw = s.toString().trim()
                    event.costCents = if (raw.isEmpty()) null else (raw.toDoubleOrNull()?.times(100))?.toInt()
                }
            })
        })
        costRow.addView(TextView(this).apply {
            text = getString(R.string.trip_event_cost_unit)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
        })
        box.addView(costRow)
        return box
    }

    private fun typePill(event: DraftEvent, type: Timeline.Type, onClick: () -> Unit): TextView =
        Chips.pill(
            this,
            getString(if (type == Timeline.Type.RIDE) R.string.trip_type_ride else R.string.trip_type_activity),
            event.type == type,
            onClick,
        )

    // ────────────────────────── 日期/时间 ──────────────────────────

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
        val title = binding.inputTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            showError(getString(R.string.trip_title_label))
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
                    transport = segment.transport,
                    note = segment.note,
                )
            },
        )
        timeline.validate()?.let {
            showError(it)
            return
        }

        val input = TripInput(
            title = title,
            note = binding.inputNote.text?.toString()?.trim().orEmpty().ifBlank { null },
            timeline = timeline,
        )
        val id = editingId
        binding.btnSave.isEnabled = false
        lifecycleScope.runIo({
            val db = AppDatabase.get(this)
            val lookup = CityStore.all(this).associateBy { it.name }
            if (id == 0L) TripRepo.create(db, input, { lookup[it] })
            else TripRepo.update(db, id, input, { lookup[it] })
        }) { result ->
            binding.btnSave.isEnabled = true
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

    private fun confirmDelete() {
        if (editingId == 0L) return
        val title = binding.inputTitle.text?.toString().orEmpty()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, title))
            .setPositiveButton(R.string.common_delete) { _, _ -> delete() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun delete() {
        if (!binding.topBar.btnDelete.isEnabled) return
        binding.topBar.btnDelete.isEnabled = false
        val id = editingId
        binding.btnSave.isEnabled = false
        lifecycleScope.runIo({ TripRepo.delete(AppDatabase.get(this), id) }) { result ->
            binding.btnSave.isEnabled = true
            if (result.isFailure) {
                binding.topBar.btnDelete.isEnabled = true
                showError(result.exceptionOrNull()?.message ?: getString(R.string.common_error))
                return@runIo
            }
            PhotoJanitor.sweepAsync(this)
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    /** 有未保存修改时返回要问一句。用快照对比：标题、备注、城市与事件的标识/时间/类型。 */
    private fun confirmDiscard() {
        if (!isDirty()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setPositiveButton(R.string.discard_positive) { _, _ -> finish() }
            .setNegativeButton(R.string.discard_negative, null)
            .show()
    }

    private var initialSnapshot: String = ""

    private fun markClean() {
        initialSnapshot = snapshot()
    }

    private fun snapshot(): String =
        binding.inputTitle.text.toString() + "|" + binding.inputNote.text.toString() + "|" +
            segments.joinToString(";") { segment ->
                segment.city.name + "|" + segment.companions + "|" + segment.note + "|" + segment.rating + "|" +
                    segment.events.joinToString(",") { "${it.id}:${it.time}:${it.type}:${it.note}" }
            }

    private fun isDirty(): Boolean = initialSnapshot.isNotEmpty() && snapshot() != initialSnapshot

    private fun DraftEvent.toEvent(): Timeline.Event {
        val tzOffsetMinutes = java.util.TimeZone.getDefault().getOffset(time) / 60_000
        return Timeline.Event(
            id = id,
            time = time,
            type = type,
            tz = tzOffsetMinutes,
            text = text,
            costCents = costCents,
            note = note.ifBlank { null },
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
