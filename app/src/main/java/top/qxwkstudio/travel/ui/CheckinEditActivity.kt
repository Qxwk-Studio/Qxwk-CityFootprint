package top.qxwkstudio.travel.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
import java.util.Calendar

/**
 * 手动打卡页（2.0）：城市 + 到访时间（四档精度）+ 备注。
 *
 * 城市与坐标**不是手输**：点一下进现有的选城页（分组浏览 + 搜索，见 CityPickerActivity），
 * 或者点准心让它按定位判城（[CityBoundaries] + logic/CityLocator）。
 *
 * 日期四档（见 logic/DatePrecision）：
 *  - 年 / 月 / 日都选 → 完整日期；
 *  - 月选「仅到年份」→ 只记得年；
 *  - 日选「不记得日」→ 只记得年月；
 *  - 勾上「记不清了」→ 只记得去过（占位日期只用于排序，不参与「时间跨度」）。
 *
 * 落库走 [CheckinRepo.createManual]；写成功由仓库自增 DataVersion，主页/行程页据此重读。
 */
class CheckinEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckinEditBinding

    /** 选中的城市（名字 + 坐标 + adcode + 省份），由选城页或定位给出。 */
    private var city: City? = null

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

    private val locatePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locateIntoCity() else notify(R.string.locate_no_permission, long = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题色叠层：必须在 setContentView 之前应用，Material 控件（按钮/开关/输入框）的颜色取自主题
        theme.applyStyle(Palette.selected(this).overlay, true)
        binding = ActivityCheckinEditBinding.inflate(layoutInflater)
        val b = binding
        applyEdgeToEdge(b.topBar.root, null)
        setContentView(b.root)

        editingId = intent.getLongExtra(EXTRA_ID, 0L)
        val editing = editingId != 0L
        b.topBar.title.setText(if (editing) R.string.checkin_edit_title else R.string.checkin_title)
        b.topBar.btnBack.visibility = View.VISIBLE
        b.topBar.btnBack.setOnClickListener { finish() }

        setupDateSpinners()
        b.inputCity.setOnClickListener { picker.launch(Intent(this, CityPickerActivity::class.java)) }
        b.btnLocate.setOnClickListener { locateIntoCity() }
        b.btnToday.setOnClickListener { selectToday() }
        b.switchForgot.setOnCheckedChangeListener { _, forgot -> applyForgot(forgot) }
        b.btnSave.setOnClickListener { save() }

        b.inputNote.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) = updateCounter(s.length)
        })
        updateCounter(0)

        if (editing) loadForEdit()
    }

    // ────────────────────────── 日期 ──────────────────────────

    private val years: List<Int> = (Calendar.getInstance().get(Calendar.YEAR) downTo 2000).toList()

    private fun setupDateSpinners() {
        val b = binding
        b.spinYear.adapter = ArrayAdapter(
            this, R.layout.item_spinner, years.map { getString(R.string.checkin_year, it) },
        )
        // 月份第 0 项就是「仅到年份」：选了它等于这一档只到年。
        // 不另放一个「空」项，是因为「有没有日期」已经由「记不清了」开关表达。
        b.spinMonth.adapter = ArrayAdapter(
            this, R.layout.item_spinner,
            listOf(getString(R.string.checkin_month_year_only)) + (1..12).map { getString(R.string.checkin_month, it) },
        )
        b.spinDay.adapter = ArrayAdapter(
            this, R.layout.item_spinner,
            listOf(getString(R.string.checkin_day_unknown)) + (1..31).map { getString(R.string.checkin_day, it) },
        )
        selectToday()
    }

    private fun selectToday() {
        val now = Calendar.getInstance()
        binding.spinYear.setSelection(years.indexOf(now.get(Calendar.YEAR)).coerceAtLeast(0), false)
        binding.spinMonth.setSelection(now.get(Calendar.MONTH) + 1, false) // 第 0 项是「仅到年份」
        binding.spinDay.setSelection(now.get(Calendar.DAY_OF_MONTH), false) // 第 0 项是「不记得日」
    }

    /** 勾上「记不清了」就把三个下拉清空并置灰：与 1.x toggleDate 同口径，别留看着还选着的值。 */
    private fun applyForgot(forgot: Boolean) {
        val b = binding
        listOf(b.spinYear, b.spinMonth, b.spinDay).forEach { it.isEnabled = !forgot }
        b.btnToday.isEnabled = !forgot
        if (forgot) {
            b.spinYear.setSelection(0, false)
            b.spinMonth.setSelection(0, false)
            b.spinDay.setSelection(0, false)
        }
    }

    /**
     * 下拉 → (UTC 毫秒, 精度)。
     *
     * 补位规则：年 → 1 月 1 日；年月 → 当月 1 日；记不清 → 当年 1 月 1 日。
     * 这些占位值只影响排序，展示由精度决定（见 TripsFragment.dateText）。
     */
    private fun readDate(): Pair<Long, Int> {
        val b = binding
        val year = years[b.spinYear.selectedItemPosition]
        if (b.switchForgot.isChecked) {
            return placeholder(year, month = 1, day = 1) to DatePrecision.UNKNOWN
        }
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
        binding.inputCity.setText(city.name)
        binding.textCityError.visibility = View.GONE
    }

    /**
     * 定位判城：与地图/行程编辑器同一套（[CityBoundaries] + CityLocator），
     * 判不到就让用户手选，不猜。
     */
    private fun locateIntoCity() {
        if (!Locate.granted(this)) {
            locatePermission.launch(Locate.PERMISSION)
            return
        }
        notify(R.string.locate_locating)
        Locate.current(this) { loc ->
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
     * 载入一条已有打卡：城市、四档日期、备注。
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
            binding.inputCity.setText(entity.cityName)
            binding.inputNote.setText(entity.note.orEmpty())
            applyPrecision(entity)
        }
    }

    /** 把库里存的精度还原成三个下拉 + 「记不清了」开关的形态。 */
    private fun applyPrecision(entity: CheckinEntity) {
        val c = Calendar.getInstance().apply { timeInMillis = entity.checkinAt }
        val yearIndex = years.indexOf(c.get(Calendar.YEAR))
        if (yearIndex >= 0) binding.spinYear.setSelection(yearIndex, false)

        when (entity.datePrecision) {
            DatePrecision.UNKNOWN -> {
                binding.switchForgot.isChecked = true
                applyForgot(true)
            }

            DatePrecision.YEAR -> {
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

    // ────────────────────────── 保存 ──────────────────────────

    private fun save() {
        val picked = city
        if (picked == null) {
            showError(getString(R.string.checkin_err_city))
            return
        }
        val (at, precision) = readDate()
        val note = binding.inputNote.text?.toString()?.trim().orEmpty().take(Limits.NOTE)

        binding.btnSave.isEnabled = false
        val existing = editingEntity
        lifecycleScope.runIo({
            val db = AppDatabase.get(this)
            if (existing == null) {
                CheckinRepo.createManual(
                    db = db,
                    city = picked,
                    checkinAt = at,
                    datePrecision = precision,
                    note = note.ifBlank { null },
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
                    ),
                )
            }
        }) { result ->
            binding.btnSave.isEnabled = true
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

    private fun updateCounter(length: Int) {
        binding.textCounter.text = getString(R.string.checkin_counter, length)
    }

    /** 所有错误提示统一走 Toast（见 ui/Notify.kt），不再往表单里塞内联红字。 */
    private fun showError(message: String) {
        notify(message, long = true)
    }

    companion object {
        private const val EXTRA_ID = "checkin_id"

        /** 新增传 0（或不传），编辑传打卡 id。 */
        fun intent(context: android.content.Context, checkinId: Long = 0L): Intent =
            Intent(context, CheckinEditActivity::class.java).putExtra(EXTRA_ID, checkinId)
    }
}
