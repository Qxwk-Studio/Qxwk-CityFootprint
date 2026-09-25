package top.qxwkstudio.travel.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.databinding.ActivityCityPickerBinding
import top.qxwkstudio.travel.databinding.ItemCityBinding
import top.qxwkstudio.travel.logic.City
import top.qxwkstudio.travel.logic.CitySearch

/**
 * 城市选择页：从 assets/cities.json（由 tools/gen-cities.mjs 从 frontend 生成）里搜索，
 * 选中后把 **城市名 + 坐标**回传给 VisitEditActivity。
 *
 * 为什么用本机的城市表而不是联网搜索：坐标与名称是跨端约定（网页版也用同一份数据），
 * 联网搜出来的城市名与 adcode 未必对得上，地图边界就会取不到。
 */
class CityPickerActivity : AppCompatActivity() {

    private var binding: ActivityCityPickerBinding? = null
    private lateinit var adapter: CityAdapter
    private var all: List<City> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityCityPickerBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }

        // 同步读一次 asset（四百多条，几毫秒）：为了它做异步反而让页面先空一下再闪出列表。
        // CityStore 内部有内存缓存，第二次打开不再重复解析
        all = CityStore.all(this)

        adapter = CityAdapter { city -> pick(city) }
        b.cityList.layoutManager = LinearLayoutManager(this)
        b.cityList.adapter = adapter
        adapter.submit(all)

        b.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) = applyFilter(s.toString())
        })
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    /** 过滤是本地纯函数（见 logic/CitySearch），边打边过滤，不必等 ime 的搜索键。 */
    private fun applyFilter(query: String) {
        val matched = CitySearch.filter(all, query)
        adapter.submit(matched)
        binding?.textCityEmpty?.visibility = if (matched.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun pick(city: City) {
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_NAME, city.name)
                .putExtra(EXTRA_LAT, city.lat)
                .putExtra(EXTRA_LNG, city.lng)
        )
        finish()
    }

    companion object {
        const val EXTRA_NAME = "city_name"
        const val EXTRA_LAT = "city_lat"
        const val EXTRA_LNG = "city_lng"
    }
}

/** 城市列表的一行（名称 + 省份）。列表里必须显示省份：同名或简称相似的市不少，靠省份才分得清。 */
private class CityAdapter(private val onClick: (City) -> Unit) : RecyclerView.Adapter<CityAdapter.Holder>() {

    private val items = mutableListOf<City>()

    fun submit(list: List<City>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCityBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    // inner：Holder 要用外层的 onClick 回调（非 inner 的内部类拿不到外层实例）
    inner class Holder(private val b: ItemCityBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(city: City) {
            b.textCityName.text = city.name
            b.textProvince.text = city.province
            b.root.setOnClickListener { onClick(city) }
        }
    }
}