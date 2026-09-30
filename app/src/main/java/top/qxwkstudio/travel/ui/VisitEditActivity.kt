package top.qxwkstudio.travel.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.ApiException
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.VisitRepo
import top.qxwkstudio.travel.databinding.ActivityVisitEditBinding
import top.qxwkstudio.travel.logic.CitySearch
import top.qxwkstudio.travel.logic.Transport
import top.qxwkstudio.travel.logic.Visit
import top.qxwkstudio.travel.logic.VisitDate
import top.qxwkstudio.travel.logic.VisitDraft
import java.util.Calendar

/**
 * 新增 / 编辑一条足迹（同一个页面，靠 Intent 里有没有 id 区分）。
 *
 * 编辑时把 city/lat/lng/date/note/private **全部经 Intent 传进来**，不再请求一次详情：
 * 列表里本来就有这些字段，为了一条记录多加一个接口与一次往返不值当。
 * 于是本页「保存」= 用表单内容整体替换（与后端 PUT 的语义一致，见 worker.js:183）。
 *
 * 客户端先做一遍与后端**完全相同**的校验（城市非空且 ≤30、日期形态），
 * 目的不是「帮后端把关」，而是让用户不必等一次往返才知道自己填错了。
 */
class VisitEditActivity : AppCompatActivity() {

    // 非空 lateinit：Activity 与视图同生共死，不必像 Fragment 那样在 onDestroyView 里置 null。
    // 异步回调挂在 lifecycleScope 上（onDestroy 取消），所以回调里直接用 binding 是安全的。
    private lateinit var binding: ActivityVisitEditBinding
    private lateinit var store: Store

    /** 0 = 新增；非 0 = 编辑这条 id。 */
    private var editId: Long = 0L

    /** 城市坐标来自城市选择页，用户看不见也改不了（见 layout 里 inputCity 的 focusable=false）。 */
    private var lat: Double? = null
    private var lng: Double? = null

    // 城市选择页的回调。写成字段初始化（而不是 onCreate 里）是 ActivityResultRegistry 的要求：
    // registerForActivityResult 必须在 Activity 被创建之前调用
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val pickedLat = data.getDoubleExtra(CityPickerActivity.EXTRA_LAT, Double.NaN)
        val pickedLng = data.getDoubleExtra(CityPickerActivity.EXTRA_LNG, Double.NaN)
        // 坐标缺了就不接受这次选择：只有城市名、没有坐标的记录在地图上打不出点
        if (pickedLat.isNaN() || pickedLng.isNaN()) return@registerForActivityResult
        lat = pickedLat
        lng = pickedLng
        binding.inputCity.setText(data.getStringExtra(CityPickerActivity.EXTRA_NAME).orEmpty())
        binding.cityLayout.error = null
    }

    /**
     * 定位权限回调：给了就接着定位回填，被拒就提示一句。
     *
     * 只有用户点准心才会走到这里（进页面**不再自动定位**，见下方 cityLayout.setEndIconOnClickListener），
     * 所以被拒时提示一句是应该的 —— 是用户自己要的定位。
     */
    private val locatePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locateIntoCity()
        else toast(R.string.locate_no_permission, long = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        binding = ActivityVisitEditBinding.inflate(layoutInflater)
        val b = binding
        // 必须在 setContentView 之前（见 ui/EdgeToEdge.kt）。
        // 这一页没有底栏，把表单滚动容器 content 当「底」交给它吃导航栏那截高度。
        // topBar 是带 id 的 <include>，ViewBinding 里是 ViewTopBarBinding 而不是 View，取 .root 才是那条栏本身
        applyEdgeToEdge(b.topBar.root, b.content)
        setContentView(b.root)

        // 顶栏（view_top_bar.xml）不自带文案，返回按钮也默认隐藏：这一页两样都要自己填
        b.topBar.btnBack.visibility = View.VISIBLE
        b.topBar.btnBack.setOnClickListener { finish() }

        editId = intent.getLongExtra(EXTRA_ID, 0L)
        val editing = editId != 0L
        b.topBar.title.text = getString(if (editing) R.string.edit_title_old else R.string.edit_title_new)
        // 新增时没有「删除」这回事。删除按钮在顶栏右上角（view_top_bar.xml 的 btnDelete），
        // 所以这里取的是 topBar 那一层，不是这一页自己的视图
        b.topBar.btnDelete.visibility = if (editing) View.VISIBLE else View.GONE

        if (editing) {
            b.inputCity.setText(intent.getStringExtra(EXTRA_CITY).orEmpty())
            b.inputNote.setText(intent.getStringExtra(EXTRA_NOTE).orEmpty())
            b.switchPrivate.isChecked = intent.getBooleanExtra(EXTRA_PRIVATE, false)
            lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN).takeIf { !it.isNaN() }
            lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN).takeIf { !it.isNaN() }
        }
        // 新增时**不自动定位**：进页面就弹系统权限框、还顺手把城市填掉，用户没要过这个动作。
        // 要定位就点城市栏右侧那颗准心（用户主动，才值得请求权限）。

        // 年月两个下拉：编辑时回填已存的日期，新增时默认填「本月」——与网页添加弹窗
        // （docs/index.js 的 openQuickModal）同口径：添加行程时最常见的值，用户不动就是对的。
        // 编辑时不兜底：有日期就回填、没有就是「记不清了」（开关由 setupDateSelects 勾上）
        val now = Calendar.getInstance()
        setupDateSelects(
            if (editing) intent.getStringExtra(EXTRA_DATE).orEmpty()
            else "%04d-%02d".format(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)
        )

        // 出行方式 chips：新增时没有「已选」（getStringArrayListExtra 回 null），编辑时按已存的回填
        buildTransportChips(intent.getStringArrayListExtra(EXTRA_TRANSPORT).orEmpty())

        // 字数提示（0/100）。用 maxLength 从源头挡住超过 100，提示只是让人有预期，
        // 而不是打完再被静默截断
        updateCounter(b.inputNote.text?.length ?: 0)
        b.inputNote.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) = updateCounter(s.length)
        })

        // 城市不是手输的：点输入框本身弹出城市选择页。
        // 监听器必须挂在 inputCity（EditText）而不是外层 cityLayout —— EditText 即使
        // focusable=false 也照样吃掉触摸事件，挂在外层收不到点击（这正是「点城市没反应」的原因）。
        b.inputCity.setOnClickListener { openPicker() }

        // 到访时间不再点开对话框：两个下拉就摆在表单里（见 setupDateSelects）

        b.btnSave.setOnClickListener { save() }
        // 取消 = 不保存退出，与顶栏返回等价（网页编辑弹窗那颗「取消」的位置就靠它对齐）
        b.btnCancel.setOnClickListener { finish() }
        b.topBar.btnDelete.setOnClickListener { confirmDelete() }

        // 城市栏右侧那颗准心：用户主动定位，覆盖已选中的城市
        b.cityLayout.setEndIconOnClickListener { locateIntoCity() }
    }

    /**
     * 定位并把「城市」回填成最近的一座（对齐网页 docs/index.js 的 locateCity）。
     *
     * 只由用户点准心触发（进页面不再自动定位），所以命中就覆盖城市，不做「已选就跳过」的分支。
     * 权限还没给就顺手拉起系统权限弹窗，结果会回 [locatePermission] 再调回这里。
     */
    private fun locateIntoCity() {
        if (!Locate.granted(this)) {
            locatePermission.launch(Locate.PERMISSION)
            return
        }
        toast(R.string.locate_locating)
        // Locate.current 的回调在主线程；拿不到位置时只能区分「没开定位」与「其它」，见 Locate 的注释
        Locate.current(this) { loc ->
            if (loc == null) {
                toast(if (Locate.enabled(this)) R.string.locate_failed else R.string.locate_disabled, long = true)
                return@current
            }
            val city = CitySearch.nearest(CityStore.all(this), loc.latitude, loc.longitude)
            if (city == null) {
                toast(R.string.locate_no_city, long = true)
                return@current
            }
            lat = city.lat
            lng = city.lng
            binding.inputCity.setText(city.name)
            binding.cityLayout.error = null
        }
    }

    private fun toast(resId: Int, long: Boolean = false) {
        Toast.makeText(this, resId, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun openPicker() {
        picker.launch(Intent(this, CityPickerActivity::class.java))
    }

    /**
     * 年月两个下拉：与网页 docs/visits.html 编辑弹窗的 visitYear / visitMonth + forgotDate 同一套。
     *
     * 选项：年份「今年 → 2000」，月份「仅年份 / 1 月 … 12 月」。刻意不给两个下拉加空首项（网页有，
     * 因为 select 得靠它取消选择）—— app 这边「没有日期」由「记不清了」开关表达，下拉一旦被点开
     * 就总能选到值，不必再多一个「空」选项去猜用户的意思。
     *
     * [date] 是已存的日期（'' 或 YYYY / YYYY-MM）：能解析出年份就回填两个下拉，否则勾上「记不清了」。
     */
    private fun setupDateSelects(date: String) {
        val b = binding
        val years = (Calendar.getInstance().get(Calendar.YEAR) downTo 2000).toList()
        b.inputYear.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, years.map { "$it 年" })
        )
        b.inputMonth.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                listOf(getString(R.string.edit_date_year_only)) + (1..12).map { "$it 月" },
            )
        )

        val parts = date.trim().split("-")
        val year = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in years }
        val month = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 12)
        if (year != null) {
            b.inputYear.setText("$year 年", false)
            // 月份缺省（YYYY 形态）时选下标 0，也就是「仅年份」
            b.inputMonth.setText(b.inputMonth.adapter.getItem(month ?: 0).toString(), false)
        }

        // 先定勾选态、再挂监听：isChecked 的赋值不该被当成用户操作走一遍 applyForgot
        b.switchForgot.isChecked = year == null
        b.switchForgot.setOnCheckedChangeListener { _, forgot -> applyForgot(forgot) }
        applyForgot(year == null)
    }

    /**
     * 「记不清了」= 这条足迹没有日期（后端允许 visit_date 为空）：清空并置灰两个下拉。
     * 与网页 toggleDate 同口径 —— 那边也是 disabled + value=''，而不是把已选的年月留在框里看着还在。
     */
    private fun applyForgot(forgot: Boolean) {
        if (forgot) {
            binding.inputYear.setText("", false)
            binding.inputMonth.setText("", false)
        }
        listOf(binding.inputYear, binding.inputMonth).forEach { it.isEnabled = !forgot }
        listOf(binding.yearLayout, binding.monthLayout).forEach { it.isEnabled = !forgot }
        binding.yearLayout.error = null
    }

    /**
     * 由两个下拉组合出要提交的日期：'2024-08'，月份选的是「仅年份」时是 '2024'；没选年份返回 null（= 没日期）。
     *
     * 从「2024 年」里取数字而不是比对后缀：月份那栏的「仅年份」正好取不到数字，于是「月份缺失」
     * 与「年份缺失」两种情况都能用一次 [digitsOf] 区分开，不用再引两个「年/月」后缀字符串。
     */
    private fun dateValue(): String? {
        val year = digitsOf(binding.inputYear.text) ?: return null
        val month = digitsOf(binding.inputMonth.text)
        return if (month == null) "%04d".format(year) else "%04d-%02d".format(year, month)
    }

    /** 取出文本里的数字（「2024 年」→ 2024，「仅年份」→ null）。 */
    private fun digitsOf(text: CharSequence?): Int? =
        // 先 toString 再 filter：CharSequence 版的 filter 返回的仍是 CharSequence，
        // 而 toIntOrNull 只挂在 String 上（Editable 上直接 filter 会编不过）
        text?.toString()?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    private fun updateCounter(length: Int) {
        binding.textNoteCounter.text = getString(R.string.edit_note_counter, length)
    }

    /**
     * 铺出行方式 chips：按 [Transport] 的枚举顺序（= 后端 TRANSPORTS、网页端顺序），
     * 把 [selected] 里已存的 code 勾上。选项不写在布局里就是为了这个同源约束（见 activity_visit_edit.xml）。
     *
     * 必须给每个 chip 生成独立 id：ChipGroup 内部按 id 记勾选状态，若全是没有 id（NO_ID，都是 -1）的
     * 视图，它会通过 findViewById 找到**第一个** chip 去改状态 —— 症状是勾任意一个都只亮第一个。
     * code 挂在 tag 上，收集时直接用 tag，不依赖 id 的可读性。
     */
    private fun buildTransportChips(selected: List<String>) {
        val checked = selected.toSet()
        Transport.entries.forEach { transport ->
            val chip = layoutInflater.inflate(R.layout.item_tp_chip, binding.groupTransport, false) as Chip
            chip.id = View.generateViewId()
            chip.text = transport.label
            chip.tag = transport.code
            chip.isChecked = transport.code in checked
            binding.groupTransport.addView(chip)
        }
    }

    /**
     * 收集勾选的 code。按子 View 顺序（= 枚举顺序）返回，与后端 pickTransports 的落库顺序一致
     * —— 后端会重排，但两端顺序相同，报文与库里就对得上，看日志时不必再脑内换算。
     */
    private fun selectedTransports(): List<String> =
        (0 until binding.groupTransport.childCount)
            .mapNotNull { i -> binding.groupTransport.getChildAt(i) as? Chip }
            .filter { it.isChecked }
            .mapNotNull { it.tag as? String }

    private fun save() {
        val b = binding
        b.textEditError.visibility = View.GONE

        val city = b.inputCity.text?.toString()?.trim().orEmpty()
        val cityLat = lat
        val cityLng = lng
        // 与后端同一口径：city 非空且 ≤30（worker.js:145）；坐标必须有，否则地图上打不出点
        if (city.isEmpty() || city.length > 30 || cityLat == null || cityLng == null) {
            b.cityLayout.error = getString(R.string.edit_err_city)
            return
        }
        b.cityLayout.error = null

        // 日期由两个下拉现组合（见 dateValue）：形态只可能是 '' / YYYY / YYYY-MM，
        // 理论上过不了校验的情况已经不存在。这道与后端同口径的检查留着，是因为它同时也是
        // 「万一以后又有人把这个字段改回手输」的兜底，成本只是一次正则
        val rawDate = dateValue()
        if (!VisitDate.isValid(rawDate)) {
            b.yearLayout.error = getString(R.string.edit_err_date)
            return
        }
        b.yearLayout.error = null

        val draft = VisitDraft(
            city = city,
            lat = cityLat,
            lng = cityLng,
            visitDate = VisitDate.toWire(rawDate),
            // 与后端 trim 口径一致；长度已由 maxLength=100 挡住
            note = b.inputNote.text?.toString()?.trim().orEmpty(),
            isPrivate = b.switchPrivate.isChecked,
            // 一个都没勾就发空数组：与后端 pickTransports 的「空 → 存 []」一致
            transport = selectedTransports(),
        )

        val token = store.token
        if (token == null) {
            Session.expired(this)
            return
        }

        setBusy(true)
        val id = editId
        lifecycleScope.runIo({
            if (id == 0L) VisitRepo.create(token, draft) else VisitRepo.update(token, id, draft)
        }) { result ->
            setBusy(false)
            val e = result.exceptionOrNull()
            if (e == null) {
                // 自己的足迹变了 → 作废报文缓存（足迹/成就/统计三份），否则列表回去会读到旧的一天缓存
                store.invalidatePayloads()
                // 让列表知道要刷新（见 VisitsFragment 的 editResult）
                setResult(Activity.RESULT_OK)
                finish()
                return@runIo
            }
            // 401 走统一流程；其余（「无权操作他人的记录」这类）显示在本页的提示位上，
            // 不用 Toast —— 表单还在，提示留在表单旁边更容易对照
            if (e is ApiException && e.code == 401) {
                Session.expired(this)
                return@runIo
            }
            binding.textEditError.text = e.message ?: getString(R.string.common_error)
            binding.textEditError.visibility = View.VISIBLE
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnSave.isEnabled = !busy
        binding.topBar.btnDelete.isEnabled = !busy
        binding.editProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun confirmDelete() {
        val city = binding.inputCity.text?.toString().orEmpty()
        // MaterialAlertDialogBuilder：M3 那套圆角弹窗壳，别改回 appcompat 的 AlertDialog（系统弹窗外观）
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.visits_delete_confirm_title)
            .setMessage(getString(R.string.visits_delete_confirm_message, city))
            .setPositiveButton(R.string.common_delete) { _, _ -> delete() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun delete() {
        val token = store.token ?: return
        val id = editId
        if (id == 0L) return
        setBusy(true)
        lifecycleScope.runIo({ VisitRepo.delete(token, id) }) { result ->
            setBusy(false)
            val e = result.exceptionOrNull()
            if (e == null) {
                store.invalidatePayloads() // 同 save()：删掉了就作废缓存，别让列表读到旧报文
                setResult(Activity.RESULT_OK)
                finish()
                return@runIo
            }
            if (e is ApiException && e.code == 401) {
                Session.expired(this)
                return@runIo
            }
            binding.textEditError.text = e.message ?: getString(R.string.common_error)
            binding.textEditError.visibility = View.VISIBLE
        }
    }

    companion object {
        private const val EXTRA_ID = "visit_id"
        private const val EXTRA_CITY = "visit_city"
        private const val EXTRA_LAT = "visit_lat"
        private const val EXTRA_LNG = "visit_lng"
        private const val EXTRA_DATE = "visit_date"
        private const val EXTRA_NOTE = "visit_note"
        private const val EXTRA_PRIVATE = "visit_private"

        /** 出行方式 code 数组。用 StringArrayList 传（ArrayList<String> 才有现成的 getStringArrayListExtra）。 */
        private const val EXTRA_TRANSPORT = "visit_transport"

        /** 新增传 null，编辑传列表里的那条。extras 只在「本 app 内部」用，不对外暴露。 */
        fun intent(context: Context, visit: Visit?): Intent =
            Intent(context, VisitEditActivity::class.java).apply {
                if (visit == null) return@apply
                putExtra(EXTRA_ID, visit.id)
                putExtra(EXTRA_CITY, visit.city)
                putExtra(EXTRA_LAT, visit.lat)
                putExtra(EXTRA_LNG, visit.lng)
                putExtra(EXTRA_DATE, visit.visitDate)
                putExtra(EXTRA_NOTE, visit.note)
                putExtra(EXTRA_PRIVATE, visit.isPrivate)
                putStringArrayListExtra(EXTRA_TRANSPORT, ArrayList(visit.transport))
            }
    }
}
