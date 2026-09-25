package top.qxwkstudio.travel.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.ApiException
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.VisitRepo
import top.qxwkstudio.travel.databinding.ActivityVisitEditBinding
import top.qxwkstudio.travel.logic.Visit
import top.qxwkstudio.travel.logic.VisitDate
import top.qxwkstudio.travel.logic.VisitDraft

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

    private var binding: ActivityVisitEditBinding? = null
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
        binding?.inputCity?.setText(data.getStringExtra(CityPickerActivity.EXTRA_NAME).orEmpty())
        binding?.cityLayout?.error = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val b = ActivityVisitEditBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }

        editId = intent.getLongExtra(EXTRA_ID, 0L)
        val editing = editId != 0L
        b.toolbar.title = getString(if (editing) R.string.edit_title_old else R.string.edit_title_new)
        // 新增时没有「删除」这回事
        b.btnDelete.visibility = if (editing) View.VISIBLE else View.GONE

        if (editing) {
            b.inputCity.setText(intent.getStringExtra(EXTRA_CITY).orEmpty())
            b.inputDate.setText(intent.getStringExtra(EXTRA_DATE).orEmpty())
            b.inputNote.setText(intent.getStringExtra(EXTRA_NOTE).orEmpty())
            b.switchPrivate.isChecked = intent.getBooleanExtra(EXTRA_PRIVATE, false)
            lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN).takeIf { !it.isNaN() }
            lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN).takeIf { !it.isNaN() }
        }

        // 字数提示（0/100）。用 maxLength 从源头挡住超过 100，提示只是让人有预期，
        // 而不是打完再被静默截断
        updateCounter(b.inputNote.text?.length ?: 0)
        b.inputNote.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) = updateCounter(s.length)
        })

        // 城市不是手输的，点整块输入框都当「点选择」。
        // inputCity 在布局里设了 focusable=false，触摸不会被它吃掉，会冒泡到这一层的点击事件
        b.cityLayout.setOnClickListener { openPicker() }

        b.btnSave.setOnClickListener { save() }
        b.btnDelete.setOnClickListener { confirmDelete() }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    private fun openPicker() {
        picker.launch(Intent(this, CityPickerActivity::class.java))
    }

    private fun updateCounter(length: Int) {
        binding?.textNoteCounter?.text = getString(R.string.edit_note_counter, length)
    }

    private fun save() {
        val b = binding ?: return
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

        val rawDate = b.inputDate.text?.toString()
        if (!VisitDate.isValid(rawDate)) {
            b.dateLayout.error = getString(R.string.edit_err_date)
            return
        }
        b.dateLayout.error = null

        val draft = VisitDraft(
            city = city,
            lat = cityLat,
            lng = cityLng,
            visitDate = VisitDate.toWire(rawDate),
            // 与后端 trim 口径一致；长度已由 maxLength=100 挡住
            note = b.inputNote.text?.toString()?.trim().orEmpty(),
            isPrivate = b.switchPrivate.isChecked,
        )

        val token = store.token
        if (token == null) {
            Session.expired(this)
            return
        }

        setBusy(true)
        val id = editId
        Async.run({ if (id == 0L) VisitRepo.create(token, draft) else VisitRepo.update(token, id, draft) }) { result ->
            setBusy(false)
            val e = result.exceptionOrNull()
            if (e == null) {
                // 让列表知道要刷新（见 VisitsFragment 的 editResult）
                setResult(Activity.RESULT_OK)
                finish()
                return@run
            }
            // 401 走统一流程；其余（「无权操作他人的记录」这类）显示在本页的提示位上，
            // 不用 Toast —— 表单还在，提示留在表单旁边更容易对照
            if (e is ApiException && e.code == 401) {
                Session.expired(this)
                return@run
            }
            binding?.let { bd ->
                bd.textEditError.text = e.message ?: getString(R.string.common_error)
                bd.textEditError.visibility = View.VISIBLE
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        val b = binding ?: return
        b.btnSave.isEnabled = !busy
        b.btnDelete.isEnabled = !busy
        b.editProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun confirmDelete() {
        val b = binding ?: return
        val city = b.inputCity.text?.toString().orEmpty()
        AlertDialog.Builder(this)
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
        Async.run({ VisitRepo.delete(token, id) }) { result ->
            setBusy(false)
            val e = result.exceptionOrNull()
            if (e == null) {
                setResult(Activity.RESULT_OK)
                finish()
                return@run
            }
            if (e is ApiException && e.code == 401) {
                Session.expired(this)
                return@run
            }
            binding?.let { bd ->
                bd.textEditError.text = e.message ?: getString(R.string.common_error)
                bd.textEditError.visibility = View.VISIBLE
            }
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
            }
    }
}