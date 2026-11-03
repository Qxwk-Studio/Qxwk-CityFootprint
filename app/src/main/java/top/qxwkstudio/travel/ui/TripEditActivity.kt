package top.qxwkstudio.travel.ui

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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.TripInput
import top.qxwkstudio.travel.data.TripRepo
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.databinding.ActivityTripEditBinding
import top.qxwkstudio.travel.logic.City
import top.qxwkstudio.travel.logic.Dates
import top.qxwkstudio.travel.logic.Limits
import top.qxwkstudio.travel.logic.Timeline
import top.qxwkstudio.travel.logic.TimelineEvent
import top.qxwkstudio.travel.logic.TimelineType
import java.util.Calendar
import java.util.UUID

/**
 * 行程编辑器（2.0）：标题 + 时间线事件列表 + 备注。
 *
 * 一条行程 = 一个**有序事件数组**（见 logic/Timeline）。四种类型各自必填的字段：
 *  - 到访：城市（点开选城页，带回坐标与 adcode）
 *  - 乘坐：交通方式（2.0 改成自由文本，见口径 11）
 *  - 进行：活动内容
 *  - 前往：地点名称 + 坐标（这一版仍要求先选一座城市来提供坐标；等 2.1 的自动记录会把
 *    「取当前坐标」那条路补上）
 *
 * 日期与时间用系统的 DatePicker/TimePicker 对话框，而不是三个下拉：2.0 设计文档 写的是「下拉」，
 * 但一行事件摆 3~4 个下拉会把手机屏占满、且滚动时容易误触；对话框是原生控件、一次选完。
 *
 * 事件行是**代码动态拼**的：字段随类型变化（到访要城市、乘坐要交通方式），
 * 用 XML + 多套布局反而更难对齐。
 */
class TripEditActivity : AppCompatActivity() {

    /** 编辑中的一条事件。时间存 UTC 毫秒；[text] 是「交通方式 / 活动 / 地点名」之一。 */
    private data class Draft(
        val id: String,
        var time: Long,
        var type: TimelineType,
        var city: City? = null,
        var text: String? = null,
        var costCents: Int? = null,
    )

    private lateinit var binding: ActivityTripEditBinding
    private val drafts = mutableListOf<Draft>()

    /** 0 = 新增；非 0 = 编辑这条行程的 id。 */
    private var editingId = 0L

    /** 正在为哪一个事件选城市（下标）。选城页是异步回来的，得记住是谁发起的。 */
    private var pickingFor = -1

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val index = pickingFor
        pickingFor = -1
        if (result.resultCode != Activity.RESULT_OK || index !in drafts.indices) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val name = data.getStringExtra(CityPickerActivity.EXTRA_NAME).orEmpty()
        val lat = data.getDoubleExtra(CityPickerActivity.EXTRA_LAT, Double.NaN)
        val lng = data.getDoubleExtra(CityPickerActivity.EXTRA_LNG, Double.NaN)
        if (name.isBlank() || lat.isNaN() || lng.isNaN()) return@registerForActivityResult
        val found = CityStore.all(this).firstOrNull { it.name == name } ?: return@registerForActivityResult
        drafts[index].city = found.copy(lat = lat, lng = lng)
        renderEvents()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题色叠层：必须在 setContentView 之前应用，Material 控件（按钮/开关/输入框）的颜色取自主题
        theme.applyStyle(Palette.selected(this).overlay, true)
        binding = ActivityTripEditBinding.inflate(layoutInflater)
        val b = binding
        applyEdgeToEdge(b.topBar.root, null)
        setContentView(b.root)

        editingId = intent.getLongExtra(EXTRA_ID, 0L)
        val editing = editingId != 0L
        b.topBar.title.setText(if (editing) R.string.trip_edit_title else R.string.trip_new_title)
        b.topBar.btnBack.visibility = View.VISIBLE
        b.topBar.btnBack.setOnClickListener { confirmDiscard() }
        b.topBar.btnDelete.visibility = if (editing) View.VISIBLE else View.GONE
        b.topBar.btnDelete.setOnClickListener { confirmDelete() }

        b.btnAddEvent.setOnClickListener {
            drafts += Draft(id = UUID.randomUUID().toString(), time = System.currentTimeMillis(), type = TimelineType.VISIT)
            renderEvents()
        }
        b.btnCancel.setOnClickListener { confirmDiscard() }
        b.btnSave.setOnClickListener { save() }

        // 系统返回（手势/返回键）也要走「未保存就确认」这条：只拦顶栏那颗返回按钮
        // 等于没拦 —— 用户按的都是系统返回。
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmDiscard()
        })

        bindCounters()

        if (editing) loadExisting() else {
            // 新行程：先给一个到访事件，用户不至于面对一片空白
            drafts += Draft(id = UUID.randomUUID().toString(), time = System.currentTimeMillis(), type = TimelineType.VISIT)
            renderEvents()
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
            timeline.events.forEach { event ->
                drafts += Draft(
                    id = event.id,
                    time = event.time,
                    type = event.type,
                    city = event.cityName?.let { name ->
                        cities[name]?.let { base ->
                            base.copy(
                                lat = event.lat ?: base.lat,
                                lng = event.lng ?: base.lng,
                            )
                        }
                    },
                    text = event.transport ?: event.activity ?: event.place,
                    costCents = event.costCents,
                )
            }
            renderEvents()
            markClean()
        }
    }

    // ────────────────────────── 事件行 ──────────────────────────

    private fun renderEvents() {
        val container = binding.eventContainer
        container.removeAllViews()
        drafts.forEachIndexed { index, draft -> container.addView(buildEventRow(index, draft)) }
        if (drafts.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.trip_add_event)
                setTextColor(getColor(R.color.text_tertiary))
                textSize = 13f
                setPadding(0, dp(8), 0, dp(8))
            })
        }
    }

    private fun buildEventRow(index: Int, draft: Draft): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        // 第一行：类型 + 删除
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Spinner(this).apply {
            adapter = ArrayAdapter(
                this@TripEditActivity,
                R.layout.item_spinner,
                listOf(
                    getString(R.string.trip_event_visit),
                    getString(R.string.trip_event_ride),
                    getString(R.string.trip_event_activity),
                    getString(R.string.trip_event_goto),
                ),
            )
            setSelection(draft.type.ordinal, false)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val type = TimelineType.entries[position]
                    if (draft.type == type) return
                    draft.type = type
                    renderEvents()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        })
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete)
            background = null
            setColorFilter(getColor(R.color.destructive))
            contentDescription = getString(R.string.common_delete)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                drafts.removeAt(index)
                renderEvents()
            }
        })
        card.addView(header)

        // 第二行：日期 + 时间
        val dateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        // 日期/时间用圆角小块（Chips.tag）：之前复用了「名次圆」的椭圆底，看起来像两颗药丸
        dateRow.addView(Chips.tag(this, Dates.ymd(draft.time)) { pickDate(index) }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
        })
        dateRow.addView(Chips.tag(this, hm(draft.time)) { pickTime(index) })
        card.addView(dateRow)

        // 第三行：内容（到访/前往 → 选城市；乘坐/进行 → 文本）
        card.addView(buildContentView(index, draft))

        // 第四行：花费（可选，单位元）
        val costRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        costRow.addView(EditText(this).apply {
            hint = getString(R.string.trip_event_cost_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(draft.costCents?.let { (it / 100.0).toString() } ?: "")
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    // 空字符串 = 没填（null），与「花了 0 元」区分开
                    val raw = s.toString().trim()
                    draft.costCents = if (raw.isEmpty()) null else (raw.toDoubleOrNull()?.times(100))?.toInt()
                }
            })
        })
        costRow.addView(TextView(this).apply {
            text = getString(R.string.trip_event_cost_unit)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
        })
        card.addView(costRow)
        return card
    }

    private fun buildContentView(index: Int, draft: Draft): View = when (draft.type) {
        TimelineType.VISIT, TimelineType.GOTO -> TextView(this).apply {
            text = draft.city?.name ?: getString(R.string.trip_event_city)
            setTextColor(getColor(if (draft.city == null) R.color.text_tertiary else R.color.fg))
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                pickingFor = index
                picker.launch(Intent(this@TripEditActivity, CityPickerActivity::class.java))
            }
        }

        TimelineType.RIDE, TimelineType.ACTIVITY -> EditText(this).apply {
            hint = getString(
                if (draft.type == TimelineType.RIDE) R.string.trip_event_transport_hint
                else R.string.trip_event_activity_hint,
            )
            setText(draft.text.orEmpty())
            textSize = 14f
            maxLines = 1
            setPadding(0, dp(6), 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    draft.text = s.toString().trim().ifEmpty { null }
                }
            })
        }
    }

    // ────────────────────────── 日期/时间 ──────────────────────────

    private fun pickDate(index: Int) {
        if (index !in drafts.indices) return
        val c = Calendar.getInstance().apply { timeInMillis = drafts[index].time }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                c.set(year, month, day)
                drafts[index].time = c.timeInMillis
                renderEvents()
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun pickTime(index: Int) {
        if (index !in drafts.indices) return
        val c = Calendar.getInstance().apply { timeInMillis = drafts[index].time }
        TimePickerDialog(
            this,
            { _, hour, minute ->
                c.set(Calendar.HOUR_OF_DAY, hour)
                c.set(Calendar.MINUTE, minute)
                drafts[index].time = c.timeInMillis
                renderEvents()
            },
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true,
        ).show()
    }

    private fun hm(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    // ────────────────────────── 保存/删除 ──────────────────────────

    private fun save() {
        val title = binding.inputTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            showError(getString(R.string.trip_title_label))
            return
        }
        val timeline = Timeline(drafts.map { it.toEvent() })
        // 与后端时代同一套校验：类型必填、时间线正序、短文本 23 字
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
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun confirmDelete() {
        if (editingId == 0L) return
        val title = binding.inputTitle.text?.toString().orEmpty()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trip_delete_confirm_title)
            .setMessage(getString(R.string.trip_delete_confirm_message, title))
            .setPositiveButton(R.string.common_delete) { _, _ -> delete() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun delete() {
        val id = editingId
        binding.btnSave.isEnabled = false
        lifecycleScope.runIo({ TripRepo.delete(AppDatabase.get(this), id) }) { result ->
            binding.btnSave.isEnabled = true
            if (result.isFailure) {
                showError(result.exceptionOrNull()?.message ?: getString(R.string.common_error))
                return@runIo
            }
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    /**
     * 有未保存修改时返回要问一句（2.0 工程口径文档 的防误触要求）。
     * 判定用「标题/备注/事件数与原值不同」——比逐字段 diff 便宜，也不会因为一次选类型就误报太多。
     */
    private fun confirmDiscard() {
        if (!isDirty()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trip_cancel)
            .setMessage(R.string.trip_discard_message)
            .setPositiveButton(R.string.common_confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private var initialSnapshot: String = ""

    /** 载入/保存后把当前状态记成「干净」，之后的任何改动都会让它变脏。 */
    private fun markClean() {
        initialSnapshot = snapshot()
    }

    private fun snapshot(): String =
        binding.inputTitle.text.toString() + "|" + binding.inputNote.text.toString() + "|" +
            drafts.joinToString(",") { "${it.id}:${it.time}:${it.type}" }

    private fun isDirty(): Boolean = initialSnapshot.isNotEmpty() && snapshot() != initialSnapshot

    private fun Draft.toEvent(): TimelineEvent {
        val city = this.city
        val tzOffsetMinutes = (java.util.TimeZone.getDefault().getOffset(time) / 60_000)
        return when (type) {
            TimelineType.VISIT -> TimelineEvent(
                id = id, time = time, type = type, tz = tzOffsetMinutes,
                cityName = city?.name, cityCode = city?.adcode, lat = city?.lat, lng = city?.lng,
                costCents = costCents,
            )

            TimelineType.RIDE -> TimelineEvent(
                id = id, time = time, type = type, tz = tzOffsetMinutes,
                transport = text, costCents = costCents,
            )

            TimelineType.ACTIVITY -> TimelineEvent(
                id = id, time = time, type = type, tz = tzOffsetMinutes,
                activity = text, costCents = costCents,
            )

            TimelineType.GOTO -> TimelineEvent(
                id = id, time = time, type = type, tz = tzOffsetMinutes,
                place = text ?: city?.name, lat = city?.lat, lng = city?.lng, costCents = costCents,
            )
        }
    }

    /** 所有错误提示统一走 Toast（见 ui/Notify.kt）；内联红字在长表单里看不到，已去掉。 */
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
