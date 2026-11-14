package top.qxwkstudio.travel.ui.trips

import top.qxwkstudio.travel.ui.common.*
import top.qxwkstudio.travel.ui.picker.CityPickerActivity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.PickVisualMediaRequest
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import top.qxwkstudio.travel.ui.common.LocalizedActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CheckinRepo
import top.qxwkstudio.travel.data.CityBoundaries
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.CheckinEntity
import top.qxwkstudio.travel.databinding.ActivityCheckinEditBinding
import top.qxwkstudio.travel.logic.City
import top.qxwkstudio.travel.logic.DatePrecision
import top.qxwkstudio.travel.logic.Limits
import top.qxwkstudio.travel.logic.Sanitize
import java.util.Calendar
import java.util.UUID

/**
 * 手动打卡页：城市 + 到访时间 + 停留天数 + 可选项（同行人/照片/评分/备注）。
 *
 * 城市与坐标**不是手输**：点一下进现有的选城页（分组浏览 + 搜索，见 CityPickerActivity），
 * 或者点准心让它按定位判城（[CityBoundaries] + logic/CityLocator）。
 *
 * 日期精度（见 logic/DatePrecision）：
 *  - 年 / 月 / 日都选 → 完整日期；
 *  - 月选「仅到年份」→ 只记得年；
 *  - 日选「不记得日」→ 只记得年月；
 *  - 勾上「记不清了」→ 只记得去过（占位日期只用于排序，不参与「时间跨度」）。
 *
 * 落库走 [CheckinRepo.createManual]；写成功由仓库自增 DataVersion，主页/行程页据此重读。
 */
class CheckinEditActivity : LocalizedActivity() {

    private lateinit var binding: ActivityCheckinEditBinding

    /** 选中的城市（名字 + 坐标 + adcode + 省份），由选城页或定位给出。 */
    private var city: City? = null

    /** 可选信息：同行人 code / 评分 1..5 / 本地照片路径。 */
    private var companions: String? = null
    private var rating: Int? = null
    /** 最多 3 张：存成逗号分隔的路径串，读取方只取第一张做缩略图。 */
    private val photoPaths = mutableListOf<String>()
    private var photoPath: String? = null
    private var amountCents: Int? = null
    private var stayDays: Int? = null

    /** 有耗时操作（保存 / 定位）在跑时，其它入口一律忽略，避免重复提交。 */
    private var busy = false

    /** 照片文件名：编辑时固定成 checkin_<id>（换图直接覆盖），新增时用临时 uuid。 */
    private var photoName: String = UUID.randomUUID().toString()

    /** 0 = 新增；非 0 = 编辑这条打卡（从行程页的 `...` → 编辑进来）。 */
    private var editingId = 0L
    private var editingEntity: CheckinEntity? = null

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val name = data.getStringExtra(CityPickerActivity.EXTRA_NAME).orEmpty()
        val lat = data.getDoubleExtra(CityPickerActivity.EXTRA_LAT, Double.NaN)
        val lng = data.getDoubleExtra(CityPickerActivity.EXTRA_LNG, Double.NaN)
        if (name.isBlank() || lat.isNaN() || lng.isNaN()) return@registerForActivityResult
        // 省份与 adcode 从城市表补：选城页只回传名字与坐标，而打卡要存省份（统计与成就都按它）
        val found = CityStore.all(this).firstOrNull { it.name == name }
        if (found == null) {
            showError(getString(R.string.checkin_err_city))
            return@registerForActivityResult
        }
        setCity(found.copy(lat = lat, lng = lng))
    }

    /** 系统相册多选：一次可补到 3 张。 */
    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val remain = 3 - photoPaths.size
        if (remain <= 0) return@registerForActivityResult
        val picked = uris.take(remain)
        lifecycleScope.runIo({
            picked.mapIndexedNotNull { index, uri ->
                PhotoStore.save(this, uri, "${photoName}_${photoPaths.size + index + 1}")
            }
        }) { result ->
            val saved = result.getOrNull().orEmpty()
            if (saved.isEmpty()) {
                notify(R.string.photo_failed, long = true)
                return@runIo
            }
            photoPaths += saved
            renderPhoto()
        }
    }
    private val locatePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locateIntoCity() else notify(R.string.locate_no_permission, long = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题色叠层：必须在 setContentView 之前应用，Material 控件（按钮/开关/输入框）的颜色取自主题
        Palette.apply(this)
        binding = ActivityCheckinEditBinding.inflate(layoutInflater)
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
        b.topBar.title.setText(if (editing) R.string.checkin_edit_title else R.string.checkin_title)
        b.topBar.btnBack.visibility = View.VISIBLE
        b.topBar.btnBack.setOnClickListener { confirmDiscard() }

        setupDateSpinners()
        b.inputCity.setOnClickListener { picker.launch(Intent(this, CityPickerActivity::class.java)) }
        b.btnLocate.setOnClickListener { locateIntoCity() }
        b.btnAddPhoto.setOnClickListener {
            if (photoPaths.size < 3) photoPicker.launch("image/*")
        }
        b.btnSave.setOnClickListener { save() }
        b.topBar.btnSaveIcon.visibility = View.VISIBLE
        b.topBar.btnSaveIcon.setOnClickListener { save() }

        setupRating()
        renderPhoto()

        b.inputCompanions.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                updateCompanionsCounter(s?.length ?: 0)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        updateCompanionsCounter(0)
        b.inputNote.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) = updateCounter(s.length)
        })
        updateCounter(0)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmDiscard()
        })

        if (editing) loadForEdit() else markClean()
    }

    // ────────────────────────── 日期 ──────────────────────────

    private val years: List<Int> = (Calendar.getInstance().get(Calendar.YEAR) downTo 2000).toList()

    private fun setupDateSpinners() {
        val b = binding
        b.spinYear.adapter = ArrayAdapter(
            this, R.layout.item_spinner_date, years.map { getString(R.string.checkin_year, it) },
        )
        // 月份第 0 项 = 仅到年份；日第 0 项 = 仅到月份。精度完全由这两项决定，
        // 不再有「记不清了」开关。
        b.spinMonth.adapter = ArrayAdapter(
            this, R.layout.item_spinner_date,
            listOf(getString(R.string.checkin_month_year_only)) + (1..12).map { getString(R.string.checkin_month, it) },
        )
        b.spinDay.adapter = ArrayAdapter(
            this, R.layout.item_spinner_date,
            listOf(getString(R.string.checkin_day_unknown)) + (1..31).map { getString(R.string.checkin_day, it) },
        )
        val now = Calendar.getInstance()
        b.spinYear.setSelection(years.indexOf(now.get(Calendar.YEAR)).coerceAtLeast(0), false)
        b.spinMonth.setSelection(now.get(Calendar.MONTH) + 1, false)
        b.spinDay.setSelection(now.get(Calendar.DAY_OF_MONTH), false)
    }

    /**
     * 下拉 → (UTC 毫秒, 精度)：年 → 1 月 1 日；年月 → 当月 1 日；完整日期 → 当天。
     * 占位值只影响排序，展示由精度决定（见 TripsFragment.dateText）。
     */
    private fun readDate(): Pair<Long, Int> {
        val b = binding
        val year = years[b.spinYear.selectedItemPosition]
        val monthPos = b.spinMonth.selectedItemPosition
        if (monthPos == 0) return placeholder(year, 1, 1) to DatePrecision.YEAR
        val dayPos = b.spinDay.selectedItemPosition
        if (dayPos == 0) return placeholder(year, monthPos, 1) to DatePrecision.MONTH
        return placeholder(year, monthPos, dayPos) to DatePrecision.DAY
    }
    private fun placeholder(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0) // 12:00 避开时区/夏令时把日期推回前一天
        }.timeInMillis

    // ────────────────────────── 城市 ──────────────────────────

    private fun setCity(city: City) {
        this.city = city
        binding.inputCity.setText(CityStore.displayName(this, city.name))
        binding.textCityError.visibility = View.GONE
    }

    /**
     * 定位判城：与地图/行程编辑器同一套（[CityBoundaries] + CityLocator），
     * 判不到就让用户手选，不猜。
     */
    private fun locateIntoCity() {
        if (busy) return
        if (!Locate.granted(this)) {
            locatePermission.launch(Locate.PERMISSION)
            return
        }
        busy = true
        binding.btnLocate.isEnabled = false
        fun done() {
            busy = false
            binding.btnLocate.isEnabled = true
        }
        notify(R.string.locate_locating)
        Locate.current(this) { loc ->
            done()
            if (loc == null) {
                notify(if (Locate.enabled(this)) R.string.locate_failed else R.string.locate_disabled, long = true)
                return@current
            }
            val found = CityBoundaries.locator(this).locate(loc.latitude, loc.longitude)
            if (found == null) {
                notify(R.string.locate_no_city, long = true)
                return@current
            }
            setCity(found.city)
        }
    }

    // ────────────────────────── 编辑已有打卡 ──────────────────────────

    /**
     * 载入一条已有打卡：城市、日期、可选项。
     *
     * 城市表里查不到名字时（旧数据/搬迁）仍然允许编辑其余字段：城市用「名字 + 空 province」兜着，
     * 用户重新点一次选城就能补全 —— 但不能因为查不到就整页不可用。
     */
    private fun loadForEdit() {
        lifecycleScope.runIo({
            AppDatabase.get(this).checkins().byId(editingId)
        }) { result ->
            val entity = result.getOrNull() ?: run {
                notify(R.string.common_error, long = true)
                finish()
                return@runIo
            }
            editingEntity = entity
            val base = CityStore.all(this).firstOrNull { it.name == entity.cityName }
            city = base?.copy(adcode = entity.cityCode ?: base.adcode)
                ?: City(
                    name = entity.cityName,
                    province = entity.province,
                    lat = 0.0,
                    lng = 0.0,
                    adcode = entity.cityCode,
                )
            binding.inputCity.setText(CityStore.displayName(this, entity.cityName))
            binding.inputNote.setText(entity.note.orEmpty())
            binding.inputCompanions.setText(entity.companions.orEmpty())
            companions = entity.companions
            rating = entity.rating
            photoPaths.clear()
            entity.photoPath?.split(",")?.filter { it.isNotBlank() }?.forEach { photoPaths += it }
            photoPath = entity.photoPath
            amountCents = entity.amountCents
            stayDays = entity.stayDays
            binding.inputAmount.setText(entity.amountCents?.let { (it / 100.0).toString() } ?: "")
            binding.inputDays.setText(entity.stayDays?.toString() ?: "")
            photoName = "checkin_$editingId"
            setupRating()
            renderPhoto()
            applyPrecision(entity)
            // 所有载入动作做完才记「干净状态」：否则一进页面就被算成已修改
            markClean()
        }
    }

    /** 把库里存的精度还原成三个下拉的形态（老的「记不清了」按仅到年份展示）。 */
    private fun applyPrecision(entity: CheckinEntity) {
        val c = Calendar.getInstance().apply { timeInMillis = entity.checkinAt }
        val yearIndex = years.indexOf(c.get(Calendar.YEAR))
        if (yearIndex >= 0) binding.spinYear.setSelection(yearIndex, false)

        when (entity.datePrecision) {
            DatePrecision.YEAR, DatePrecision.UNKNOWN -> {
                binding.spinMonth.setSelection(0, false)
                binding.spinDay.setSelection(0, false)
            }

            DatePrecision.MONTH -> {
                binding.spinMonth.setSelection(c.get(Calendar.MONTH) + 1, false)
                binding.spinDay.setSelection(0, false)
            }

            else -> {
                binding.spinMonth.setSelection(c.get(Calendar.MONTH) + 1, false)
                binding.spinDay.setSelection(c.get(Calendar.DAY_OF_MONTH), false)
            }
        }
    }
    /** 停留天数步进：最少 1 天。 */
    private fun updateCounter(length: Int) {
        binding.textCounter.text = getString(R.string.char_counter, length, 233)
    }

    private fun updateCompanionsCounter(length: Int) {
        binding.textCompanionsCounter.text = getString(R.string.char_counter, length, 23)
    }

    // ────────────────────────── 保存 ──────────────────────────

    // ────────────────────────── 未保存确认 ──────────────────────────

    private var initialSnapshot: String = ""

    private fun markClean() { initialSnapshot = snapshot() }

    private fun snapshot(): String = buildString {
        append(binding.inputCity.text).append('|')
        append(binding.spinYear.selectedItemPosition).append('|')
        append(binding.spinMonth.selectedItemPosition).append('|')
        append(binding.spinDay.selectedItemPosition).append('|')
        append(binding.inputNote.text).append('|')
        append(binding.inputCompanions.text).append('|')
        append(rating).append('|')
        append(photoPaths.joinToString(",")).append('|')
        append(binding.inputAmount.text).append('|')
        append(binding.inputDays.text)
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

    private fun save() {
        if (busy) return
        val picked = city
        if (picked == null) {
            showError(getString(R.string.checkin_err_city))
            return
        }
        val (at, precision) = readDate()
        val photos = photoPaths.joinToString(",").ifBlank { null }
        val note = Sanitize.text(binding.inputNote.text?.toString(), Limits.NOTE)
        val amountText = binding.inputAmount.text?.toString()?.trim().orEmpty()
        amountCents = if (amountText.isEmpty()) {
            null
        } else {
            val yuan = amountText.toDoubleOrNull() ?: 0.0
            (yuan.coerceIn(0.0, 20_000_000.0) * 100).toInt()
        }
        val days = binding.inputDays.text?.toString()?.trim()?.toIntOrNull()
        if (days != null && days !in 1..3650) {
            showError(getString(R.string.checkin_err_days_range))
            binding.inputDays.requestFocus()
            return
        }
        stayDays = days

        companions = Sanitize.line(binding.inputCompanions.text?.toString(), Limits.SHORT).ifBlank { null }

        binding.btnSave.isEnabled = false
        binding.topBar.btnSaveIcon.isEnabled = false
        val existing = editingEntity
        busy = true
        binding.btnSave.isEnabled = false
        binding.topBar.btnSaveIcon.isEnabled = false
        lifecycleScope.runIo({
            val db = AppDatabase.get(this)
            if (existing == null) {
                CheckinRepo.createManual(
                    db = db,
                    city = picked,
                    checkinAt = at,
                    datePrecision = precision,
                    note = note.ifBlank { null },
                    companions = companions,
                    rating = rating,
                    photoPath = photos,
                    amountCents = amountCents,
                    stayDays = stayDays,
                )
            } else {
                // 编辑：整体替换可编辑字段，id / uuid / 编号 / 来源保持不动
                CheckinRepo.updateManual(
                    db,
                    existing.copy(
                        cityName = picked.name,
                        cityCode = picked.adcode,
                        province = picked.province,
                        checkinAt = at,
                        datePrecision = precision,
                        note = note.ifBlank { null },
                        companions = companions,
                        rating = rating,
                        photoPath = photos,
                        amountCents = amountCents,
                        stayDays = stayDays,
                    ),
                )
            }
        }) { result ->
            busy = false
            binding.btnSave.isEnabled = true
            binding.topBar.btnSaveIcon.isEnabled = true
            val error = result.exceptionOrNull()
            if (error != null) {
                showError(error.message ?: getString(R.string.common_error))
                return@runIo
            }
            notify(R.string.checkin_saved)
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    // ────────────────────────── 可选信息 ──────────────────────────

    /** 城市评分：5 颗星，点第 n 颗 = n 星；再点同一颗清零。 */
    /** 城市评分：1~10 的拉条，未评分时显示「未评分」。 */
    private fun setupRating() {
        val b = binding
        b.ratingSeek.max = 9
        b.ratingSeek.progress = (rating ?: 1) - 1
        b.textRatingValue.text = rating?.let { getString(R.string.rating_value, it) }
            ?: getString(R.string.rating_unrated)
        b.ratingSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                rating = progress + 1
                b.textRatingValue.text = getString(R.string.rating_value, progress + 1)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })
    }
    /** 照片瓦片：最多 3 张，等分一行；长按拖动排序，右上角 × 删除。 */
    /** 照片瓦片：固定宽度、最多 3 张；长按拖动排序，右上角 × 删除。 */
    private fun renderPhoto() {
        val row = binding.photoRow
        row.visibility = if (photoPaths.isEmpty()) View.GONE else View.VISIBLE
        binding.btnAddPhoto.visibility = if (photoPaths.size >= 3) View.GONE else View.VISIBLE
        row.removeAllViews()
        photoPaths.forEachIndexed { index, path ->
            val tile = android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = dp(8)
                }
                setBackgroundResource(R.drawable.bg_photo_tile)
                clipToOutline = true
            }
            val image = android.widget.ImageView(this).apply {
                setImageBitmap(PhotoStore.load(path, maxPx = 256))
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setOnClickListener { showPhotoDialog(this@CheckinEditActivity, photoPaths, index) }
                setOnLongClickListener { v ->
                    dragHandled = false
                    v.startDragAndDrop(
                        android.content.ClipData.newPlainText("", ""),
                        android.view.View.DragShadowBuilder(v),
                        index,
                        0,
                    )
                    true
                }
            }
            tile.addView(image)
            tile.addView(TextView(this).apply {
                text = "×"
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x99000000.toInt())
                }
                contentDescription = getString(R.string.photo_remove)
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(20), dp(20), android.view.Gravity.TOP or android.view.Gravity.END)
                    .apply { topMargin = dp(4); marginEnd = dp(4) }
                setOnClickListener {
                    photoPaths.removeAt(index)
                    renderPhoto()
                }
            })
            // 拖动排序：图片和瓦片都要挂监听——手指按在图片上时事件不会冒泡到外层
            val dragListener = android.view.View.OnDragListener { _, event ->
                when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_ENTERED -> tile.alpha = 0.6f
                    android.view.DragEvent.ACTION_DRAG_EXITED,
                    android.view.DragEvent.ACTION_DRAG_ENDED, -> tile.alpha = 1f
                    android.view.DragEvent.ACTION_DROP -> {
                        tile.alpha = 1f
                        val from = event.localState as? Int
                        if (!dragHandled && from != null && from != index) {
                            dragHandled = true
                            val item = photoPaths.removeAt(from)
                            photoPaths.add(index, item)
                            renderPhoto()
                        }
                    }
                }
                true
            }
            tile.setOnDragListener(dragListener)
            image.setOnDragListener(dragListener)
            row.addView(tile)
        }
        binding.textPhotoEmpty.text = if (photoPaths.isEmpty()) {
            getString(R.string.photo_count_hint)
        } else {
            getString(R.string.photo_count_reorder, photoPaths.size)
        }
    }

    private var dragHandled = false
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showError(message: String) {
        notify(ValidationText.localize(this, message), long = true)
    }

    companion object {
        private const val EXTRA_ID = "checkin_id"

        /** 新增传 0（或不传），编辑传打卡 id。 */
        fun intent(context: android.content.Context, checkinId: Long = 0L): Intent =
            Intent(context, CheckinEditActivity::class.java).putExtra(EXTRA_ID, checkinId)
    }
}
