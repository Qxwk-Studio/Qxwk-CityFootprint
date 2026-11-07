package top.qxwkstudio.travel.ui.trips

import top.qxwkstudio.travel.ui.common.*

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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import top.qxwkstudio.travel.logic.Sanitize
import top.qxwkstudio.travel.logic.Timeline
import top.qxwkstudio.travel.ui.picker.CityPickerActivity
import java.util.Calendar
import java.util.UUID

/**
 * 行程编辑器：一条扁平时间线。
 *
 * 到访城市与活动/交通是同级项，顺序即列表顺序；保存后由 [Timeline.segments] 按城市切段，
 * 派生打卡与统计都从切段结果来。城市之前/之后的交通活动允许存在，只是不归属任何城市。
 */
class TripEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTripEditBinding
    private val items = mutableListOf<DraftItem>()

    private var editingId = 0L

    /** 选城目标：-1 = 新增城市；否则替换该下标的城市。 */
    private var picking = false
    private var pickingIndex = -1

    /** 照片目标项下标。 */
    private var photoPicking = false
    private var photoIndex = -1

    private var initialSnapshot: String = ""

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        picking = false
        val target = pickingIndex
        pickingIndex = -1
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val name = data.getStringExtra(CityPickerActivity.EXTRA_NAME).orEmpty()
        val lat = data.getDoubleExtra(CityPickerActivity.EXTRA_LAT, Double.NaN)
        val lng = data.getDoubleExtra(CityPickerActivity.EXTRA_LNG, Double.NaN)
        if (name.isBlank() || lat.isNaN() || lng.isNaN()) return@registerForActivityResult
        val city = CityStore.all(this).firstOrNull { it.name == name } ?: return@registerForActivityResult
        val picked = city.copy(lat = lat, lng = lng)

        if (target in items.indices && items[target].isCity) {
            items[target].text = picked.name
            items[target].cityCode = picked.adcode
            items[target].province = picked.province
        } else {
            items += DraftItem(
                id = UUID.randomUUID().toString(),
                type = Timeline.Type.VISIT,
                text = picked.name,
                cityCode = picked.adcode,
                province = picked.province,
            )
        }
        render()
    }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val index = photoIndex
        photoIndex = -1
        photoPicking = false
        if (uris.isNullOrEmpty() || index !in items.indices) return@registerForActivityResult
        val item = items[index]
        val current = item.photoPath?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        val remain = 3 - current.size
        if (remain <= 0) return@registerForActivityResult
        val picked = uris.take(remain)
        lifecycleScope.runIo({
            picked.mapIndexedNotNull { i, uri ->
                PhotoStore.save(this, uri, "item_${item.id}_${current.size + i + 1}")
            }
        }) { result ->
            val saved = result.getOrNull().orEmpty()
            if (saved.isEmpty()) {
                notify(getString(R.string.photo_failed), long = true)
                return@runIo
            }
            item.photoPath = (current + saved).joinToString(",")
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        binding = ActivityTripEditBinding.inflate(layoutInflater)
        applyEdgeToEdge(binding.topBar.root, binding.scroll) {
            val focused = currentFocus ?: return@applyEdgeToEdge
            binding.scroll.post {
                focused.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, focused.width, focused.height),
                    true,
                )
            }
        }
        setContentView(binding.root)

        editingId = intent.getLongExtra(EXTRA_ID, 0L)
        binding.topBar.title.setText(
            if (editingId == 0L) R.string.trip_new_title else R.string.trip_edit_title,
        )
        binding.topBar.btnBack.visibility = View.VISIBLE
        binding.topBar.btnBack.setOnClickListener { confirmDiscard() }
        binding.topBar.btnSaveIcon.visibility = View.VISIBLE
        binding.topBar.btnSaveIcon.setOnClickListener { save() }

        binding.btnCancel.setOnClickListener { confirmDiscard() }
        binding.btnSave.setOnClickListener { save() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmDiscard()
        })
        bindCounters()

        if (editingId != 0L) loadExisting() else {
            render()
            markClean()
        }
    }

    // ────────────────────────── 载入 ──────────────────────────

    private fun loadExisting() {
        lifecycleScope.runIo({ TripRepo.byId(AppDatabase.get(this), editingId) }) { result ->
            val (entity, timeline) = result.getOrNull() ?: run {
                notify(getString(R.string.common_error), long = true)
                finish()
                return@runIo
            }
            binding.inputTitle.setText(entity.title)
            binding.inputNote.setText(entity.note.orEmpty())
            items.clear()
            items += timeline.items.map { DraftItem.from(it) }
            render()
            markClean()
        }
    }

    // ────────────────────────── 列表渲染 ──────────────────────────

    private fun render() {
        val container = binding.eventContainer
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.trip_empty)
                setTextColor(getColor(R.color.text_tertiary))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
        }
        val cityColors = CityColors.assign(items.filter { it.isCity }.map { it.id })
        var currentColor = getColor(R.color.text_tertiary)
        items.forEachIndexed { index, item ->
            if (item.isCity) currentColor = cityColors[item.id] ?: currentColor
            val card = if (item.isCity) cityCard(index, item) else activityCard(index, item)
            container.addView(withRail(card, currentColor))
        }
        container.addView(addRailRow())
    }

    /** 左侧时间线竖线 + 节点，右侧卡片。 */
    private fun withRail(card: View, dotColor: Int): LinearLayout {
        card.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { bottomMargin = dp(10); marginStart = dp(8) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(timelineRail(dotColor))
            addView(card)
        }
    }

    /** 一段贯通竖线 + 顶部节点；节点颜色由所属城市决定。 */
    private fun timelineRail(dotColor: Int): android.widget.FrameLayout = android.widget.FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT)
        addView(View(this@TripEditActivity).apply {
            setBackgroundColor(getColor(R.color.border))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                dp(1),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            )
        })
        addView(View(this@TripEditActivity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(dotColor)
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(
                dp(12), dp(12), Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(20) }
        })
    }

    /** 时间线底部的圆形「+」：点开添加菜单。 */
    private fun addRailRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(android.widget.FrameLayout(this@TripEditActivity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(40))
            addView(View(this@TripEditActivity).apply {
                setBackgroundColor(getColor(R.color.border))
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    dp(1),
                    dp(14),
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                )
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
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    dp(28), dp(28), Gravity.CENTER,
                )
                setOnClickListener { showAddDialog() }
            })
        })
    }

    private fun showAddDialog() {
        val options = arrayOf(
            getString(R.string.trip_add_city_item),
            getString(R.string.trip_type_activity_label),
            getString(R.string.trip_type_ride_label),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trip_add_item)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startPickCity(-1)
                    1 -> addActivity(Timeline.Type.ACTIVITY)
                    else -> addActivity(Timeline.Type.RIDE)
                }
            }
            .show()
    }

    private fun addActivity(type: Timeline.Type) {
        items += DraftItem(
            id = UUID.randomUUID().toString(),
            type = type,
            time = items.lastActivityTime(),
            tz = tzOffset(),
        )
        render()
    }

    private fun startPickCity(index: Int) {
        if (picking) return
        picking = true
        pickingIndex = index
        picker.launch(Intent(this, CityPickerActivity::class.java))
    }

    private fun moveItem(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        val item = items.removeAt(from)
        items.add(to, item)
        render()
    }

    /** 城市卡：城市 + 同行人 / 照片 / 评分 / 备注。 */
    private fun cityCard(index: Int, item: DraftItem): View {
        val card = card()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), 0)
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_city)
            imageTintList = android.content.res.ColorStateList.valueOf(Palette.accent(this@TripEditActivity))
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.trip_type_visit_label)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.fg))
            setPadding(dp(6), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(itemActions(index))
        card.addView(header)

        // 城市：图标 + 城市 + 输入框（单行，点击换城）
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
            addView(ImageView(this@TripEditActivity).apply {
                setImageResource(R.drawable.ic_city)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@TripEditActivity, R.color.text_secondary),
                )
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            })
            addView(TextView(this@TripEditActivity).apply {
                text = getString(R.string.trip_city_label)
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(6), 0, dp(2), 0)
            })
            addView(starView())
            addView(View(this@TripEditActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
            addView(TextView(this@TripEditActivity).apply {
                text = item.text.ifBlank { getString(R.string.trip_add_city_item) }
                textSize = 15f
                setTextColor(getColor(if (item.text.isBlank()) R.color.text_tertiary else R.color.fg))
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.bg_input_box)
                setPadding(dp(10), 0, dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                setOnClickListener { startPickCity(index) }
            })
        })

        // 同行人：图标 + 名称 + 输入框一行
        val companionsCounter = fieldCounter()
        val companionsEdit = EditText(this).apply {
            hint = getString(R.string.companions_hint)
            setText(item.companions.orEmpty())
            textSize = 14f
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(Limits.SHORT))
            background = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.bg_input_box)
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) { item.companions = s.toString().trim().ifBlank { null } }
            })
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
            addView(ImageView(this@TripEditActivity).apply {
                setImageResource(R.drawable.ic_person)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@TripEditActivity, R.color.text_secondary),
                )
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            })
            addView(TextView(this@TripEditActivity).apply {
                text = getString(R.string.companions_label)
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(6), 0, dp(8), 0)
            })
            addView(companionsEdit)
        })
        card.addView(companionsCounter)
        bindCounter(companionsEdit, companionsCounter, Limits.SHORT)

        card.addView(ratingInline(item))

        card.addView(divider())
        card.addView(photoHeader(index, item))
        card.addView(photoRow(index, item))
        card.addView(photoHint())

        card.addView(divider())
        card.addView(noteBlock(item, R.string.trip_segment_note_hint))
        return card
    }

    /** 活动/交通卡：时间与类型徽章 + 内容/金额 + 备注 + 照片。 */
    private fun activityCard(index: Int, item: DraftItem): View {
        val card = card()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), 0)
        }
        header.addView(ImageView(this).apply {
            setImageResource(if (item.type == Timeline.Type.RIDE) R.drawable.ic_car else R.drawable.ic_flag)
            imageTintList = android.content.res.ColorStateList.valueOf(Palette.accent(this@TripEditActivity))
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        })
        header.addView(TextView(this).apply {
            text = typeTitle(item.type)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.fg))
            setPadding(dp(6), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { chooseType(item) }
        })
        header.addView(itemActions(index))
        card.addView(header)

        // 日期 / 时间 / 花费：一行三段
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(dateTimeBox(Dates.ymd(item.time)) { pickDate(index, item) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            })
            addView(dateTimeBox(hm(item.time)) { pickTime(index, item) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) }
            })
            val costInput = EditText(this@TripEditActivity).apply {
                hint = getString(R.string.trip_event_cost_hint)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(item.costCents?.let { (it / 100.0).toString() } ?: "")
                textSize = 13f
                maxLines = 1
                background = null
                setPadding(dp(6), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable) {
                        val raw = s.toString().trim()
                        item.costCents = if (raw.isEmpty()) null
                        else ((raw.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 20_000_000.0) * 100).toInt()
                    }
                })
            }
            addView(LinearLayout(this@TripEditActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.bg_input_box)
                setPadding(dp(2), 0, dp(6), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) }
                addView(costInput)
            })
        })

        // 名称：图标 + 名称 + 输入框
        val nameCounter = fieldCounter()
        val nameEdit = EditText(this).apply {
            hint = getString(
                if (item.type == Timeline.Type.RIDE) R.string.trip_event_transport_hint
                else R.string.trip_event_activity_hint,
            )
            setText(item.text)
            textSize = 14f
            maxLines = 1
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(Limits.SHORT))
            background = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.bg_input_box)
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) { item.text = s.toString().trim() }
            })
        }
        card.addView(inlineInput(if (item.type == Timeline.Type.RIDE) R.drawable.ic_car else R.drawable.ic_flag, getString(R.string.trip_event_name), true, nameEdit))
        card.addView(nameCounter)
        bindCounter(nameEdit, nameCounter, Limits.SHORT)


        card.addView(divider())
        card.addView(photoHeader(index, item))
        card.addView(photoRow(index, item))
        card.addView(photoHint())

        card.addView(divider())
        card.addView(noteBlock(item, R.string.trip_event_note_hint))
        return card
    }

    // ────────────────────────── 小部件 ──────────────────────────

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(0, dp(8), 0, dp(20))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun itemActions(index: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(actionIcon(R.drawable.ic_arrow_up, R.color.text_secondary, R.string.trip_move_up) {
            moveItem(index, index - 1)
        })
        addView(actionIcon(R.drawable.ic_arrow_down, R.color.text_secondary, R.string.trip_move_down) {
            moveItem(index, index + 1)
        })
        addView(actionIcon(R.drawable.ic_delete, R.color.destructive, R.string.common_delete) {
            items.removeAt(index)
            render()
        })
    }

    private fun actionIcon(icon: Int, tintRes: Int, descRes: Int, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(tintRes))
            setBackgroundResource(R.drawable.bg_icon_badge)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = getString(descRes)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(4) }
            setOnClickListener { onClick() }
        }

    private fun actionText(symbol: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = symbol
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(if (symbol == "🗑") getColor(R.color.destructive) else getColor(R.color.text_secondary))
        background = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.bg_icon_badge)
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun fieldLabel(icon: Int, text: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), 0)
        addView(ImageView(this@TripEditActivity).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(Palette.accent(this@TripEditActivity))
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

    private fun divider(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.border))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply { topMargin = dp(20); marginStart = dp(16); marginEnd = dp(16) }
    }

    /** 照片标题行：图标 + 照片 + 右侧主题色「添加照片」。 */
    private fun photoHeader(index: Int, item: DraftItem): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), 0)
        addView(ImageView(this@TripEditActivity).apply {
            setImageResource(R.drawable.ic_camera)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@TripEditActivity, R.color.text_secondary),
            )
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        })
        addView(TextView(this@TripEditActivity).apply {
            text = getString(R.string.photo_label)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.fg))
            setPadding(dp(6), 0, 0, 0)
        })
        addView(View(this@TripEditActivity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        addView(com.google.android.material.button.MaterialButton(this@TripEditActivity).apply {
            visibility = if ((item.photoPath?.split(",")?.count { it.isNotBlank() } ?: 0) >= 3) View.GONE else View.VISIBLE
            text = getString(R.string.photo_add)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Palette.accent(this@TripEditActivity))
            setTextColor(getColor(R.color.on_accent))
            minWidth = 0
            minHeight = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(8)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32),
            )
            setOnClickListener {
                if (photoPicking) return@setOnClickListener
                val count = item.photoPath?.split(",")?.count { it.isNotBlank() } ?: 0
                if (count >= 3) return@setOnClickListener
                photoPicking = true
                photoIndex = index
                photoPicker.launch("image/*")
            }
        })
    }

    /** 照片数量提示，和打卡页口径一致。 */
    private fun photoHint(): TextView = TextView(this).apply {
        text = getString(R.string.photo_count_hint)
        textSize = 12f
        setTextColor(getColor(R.color.text_tertiary))
        setPadding(dp(16), dp(6), dp(16), 0)
    }

    private fun photoRow(index: Int, item: DraftItem): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val paths = item.photoPath?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        val shown = paths.take(3)
        shown.forEachIndexed { i, path ->
            val tile = android.widget.FrameLayout(this).apply {
                layoutParams = if (shown.size == 3) {
                    LinearLayout.LayoutParams(0, dp(96), 1f).apply { if (i < shown.lastIndex) marginEnd = dp(8) }
                } else {
                    LinearLayout.LayoutParams(dp(96), dp(96)).apply { if (i < shown.lastIndex) marginEnd = dp(8) }
                }
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
                setOnClickListener { showPhotoDialog(this@TripEditActivity, path) }
            })
            tile.addView(TextView(this).apply {
                text = "×"; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x99000000.toInt())
                }
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END)
                    .apply { topMargin = dp(4); marginEnd = dp(4) }
                setOnClickListener {
                    val left = paths.toMutableList().also { it.removeAt(i) }
                    item.photoPath = left.joinToString(",").ifBlank { null }
                    render()
                }
            })
            row.addView(tile)
        }
        return row
    }

    private fun ratingInline(item: DraftItem): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_star)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@TripEditActivity, R.color.text_secondary),
            )
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        })
        row.addView(TextView(this).apply {
            text = getString(R.string.rating_label)
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(6), 0, dp(8), 0)
        })
        val value = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.END
            setTextColor(getColor(R.color.text_secondary))
            text = item.rating?.let { getString(R.string.rating_value, it) } ?: getString(R.string.rating_unrated)
            layoutParams = LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(android.widget.SeekBar(this).apply {
            max = 9
            progress = (item.rating ?: 1) - 1
            maxHeight = dp(3)
            minHeight = dp(3)
            progressDrawable = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.seekbar_thick)
            thumbTintList = android.content.res.ColorStateList.valueOf(Palette.accent(this@TripEditActivity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    item.rating = p + 1
                    value.text = getString(R.string.rating_value, p + 1)
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
            })
        })
        row.addView(value)
        return row
    }

    private fun dateTimeBox(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.fg))
        background = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.bg_input_box)
        setOnClickListener { onClick() }
    }

    /** 备注：第一行主题色图标 + 标题，第二行输入框。 */
    private fun noteBlock(item: DraftItem, hintRes: Int): LinearLayout {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
            addView(LinearLayout(this@TripEditActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@TripEditActivity).apply {
                    setImageResource(R.drawable.ic_note)
                    imageTintList = android.content.res.ColorStateList.valueOf(Palette.accent(this@TripEditActivity))
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                })
                addView(TextView(this@TripEditActivity).apply {
                    text = getString(R.string.checkin_note)
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(getColor(R.color.fg))
                    setPadding(dp(6), 0, 0, 0)
                })
            })
        }
        val counter = fieldCounter()
        val edit = EditText(this).apply {
            hint = getString(hintRes)
            setText(item.note)
            textSize = 13f
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 6
            filters = arrayOf(android.text.InputFilter.LengthFilter(Limits.NOTE))
            background = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.bg_input_box)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) { item.note = s.toString().trim() }
            })
        }
        block.addView(edit)
        block.addView(counter)
        bindCounter(edit, counter, Limits.NOTE)
        return block
    }

    /** 备注：图标 + 名称 + 输入框（多行，和其他输入框同起止）。 */
    private fun noteInline(item: DraftItem, hintRes: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(dp(16), dp(10), dp(16), 0)
        addView(ImageView(this@TripEditActivity).apply {
            setImageResource(R.drawable.ic_note)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@TripEditActivity, R.color.text_secondary),
            )
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { topMargin = dp(12) }
        })
        addView(TextView(this@TripEditActivity).apply {
            text = getString(R.string.checkin_note)
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(6), dp(12), dp(8), 0)
        })
        addView(EditText(this@TripEditActivity).apply {
            hint = getString(hintRes)
            setText(item.note)
            textSize = 13f
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 6
            filters = arrayOf(android.text.InputFilter.LengthFilter(Limits.NOTE))
            background = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.bg_input_box)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) { item.note = s.toString().trim() }
            })
        })
    }

    /** 必填星号：独立小红字，避免 Spannable 测量问题。 */
    private fun starView(): TextView = TextView(this).apply {
        text = "*"
        textSize = 12f
        setTextColor(getColor(R.color.destructive))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun inlineInput(icon: Int, label: String, required: Boolean = false, edit: EditText): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(8), dp(16), 0)
        addView(ImageView(this@TripEditActivity).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@TripEditActivity, R.color.text_secondary),
            )
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        })
        addView(TextView(this@TripEditActivity).apply {
            text = label
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(6), 0, if (required) dp(2) else dp(8), 0)
        })
        if (required) {
            addView(starView())
            addView(View(this@TripEditActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), 1)
            })
        }
        addView(edit)
    }

    private fun ratingRow(item: DraftItem): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), 0)
        }
        val value = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.END
            setTextColor(getColor(R.color.text_secondary))
            text = item.rating?.let { getString(R.string.rating_value, it) } ?: getString(R.string.rating_unrated)
            layoutParams = LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(android.widget.SeekBar(this).apply {
            max = 9
            progress = (item.rating ?: 1) - 1
            maxHeight = dp(3)
            minHeight = dp(3)
            progressDrawable = ContextCompat.getDrawable(this@TripEditActivity, R.drawable.seekbar_thick)
            thumbTintList = android.content.res.ColorStateList.valueOf(Palette.accent(this@TripEditActivity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    item.rating = p + 1
                    value.text = getString(R.string.rating_value, p + 1)
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
            })
        })
        row.addView(value)
        return row
    }

    // ────────────────────────── 事件交互 ──────────────────────────

    private fun chooseType(item: DraftItem) {
        val types = arrayOf(Timeline.Type.ACTIVITY, Timeline.Type.RIDE)
        val labels = arrayOf(getString(R.string.trip_type_activity), getString(R.string.trip_type_ride))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trip_event_type)
            .setSingleChoiceItems(labels, types.indexOf(item.type)) { dialog, which ->
                dialog.dismiss()
                item.type = types[which]
                render()
            }
            .show()
    }

    private fun pickDate(index: Int, item: DraftItem) {
        val calendar = Calendar.getInstance().apply { timeInMillis = item.time }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                calendar.set(year, month, day)
                item.time = calendar.timeInMillis
                item.tz = tzOffset()
                render()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun pickTime(index: Int, item: DraftItem) {
        val calendar = Calendar.getInstance().apply { timeInMillis = item.time }
        TimePickerDialog(
            this,
            { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                item.time = calendar.timeInMillis
                item.tz = tzOffset()
                render()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true,
        ).show()
    }

    private fun typeTitle(type: Timeline.Type): String = getString(
        when (type) {
            Timeline.Type.RIDE -> R.string.trip_type_ride_label
            Timeline.Type.VISIT -> R.string.trip_type_visit_label
            else -> R.string.trip_type_activity_label
        },
    )

    private fun typeLabel(type: Timeline.Type): String = getString(
        when (type) {
            Timeline.Type.RIDE -> R.string.trip_type_ride
            Timeline.Type.VISIT -> R.string.trip_type_visit
            else -> R.string.trip_type_activity
        },
    )

    private fun tzOffset(): Int = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    private fun hm(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(java.util.Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    // ────────────────────────── 保存/退出 ──────────────────────────

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

    private fun save() {
        val title = Sanitize.line(binding.inputTitle.text?.toString(), Limits.SHORT)
        if (title.isEmpty()) {
            showError(getString(R.string.trip_err_title))
            return
        }
        val timeline = Timeline(items.map { it.toItem() })
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
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun markClean() { initialSnapshot = snapshot() }

    private fun snapshot(): String = buildString {
        append(binding.inputTitle.text).append('|')
        append(binding.inputNote.text).append('|')
        items.forEach { item ->
            append(item.id).append(':').append(item.type).append(':').append(item.time)
                .append(':').append(item.text).append(':').append(item.note)
                .append(':').append(item.rating).append(':').append(item.photoPath)
                .append(':').append(item.companions).append(';')
        }
    }

    private fun isDirty(): Boolean = initialSnapshot.isNotEmpty() && snapshot() != initialSnapshot

    private fun confirmDiscard() {
        if (!isDirty()) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setPositiveButton(R.string.discard_positive) { _, _ -> finish() }
            .setNegativeButton(R.string.discard_negative, null)
            .show()
    }

    private fun showError(message: String) {
        notify(message, long = true)
    }

    private fun fieldCounter(): TextView = TextView(this).apply {
        textSize = 11f
        gravity = Gravity.END
        setTextColor(getColor(R.color.text_tertiary))
        setPadding(dp(16), dp(4), dp(16), 0)
    }

    private fun bindCounter(edit: EditText, counter: TextView, max: Int) {
        fun sync(length: Int) {
            counter.text = getString(R.string.trip_counter, length, max)
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) = sync(s.length)
        })
        sync(edit.length())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ID = "trip_id"
        fun intent(context: android.content.Context, tripId: Long = 0L): Intent =
            Intent(context, TripEditActivity::class.java).putExtra(EXTRA_ID, tripId)
    }
}
