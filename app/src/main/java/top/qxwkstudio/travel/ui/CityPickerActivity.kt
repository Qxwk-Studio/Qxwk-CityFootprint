package top.qxwkstudio.travel.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.databinding.ActivityCityPickerBinding
import top.qxwkstudio.travel.databinding.ItemCityBinding
import top.qxwkstudio.travel.databinding.ItemCityGroupBinding
import top.qxwkstudio.travel.logic.City
import top.qxwkstudio.travel.logic.CitySearch
import java.text.Collator
import java.util.Locale

/**
 * 城市选择页：从 assets/cities.json（由 app/tools/gen-cities.mjs 从 docs/ 生成）里搜索，
 * 选中后把 **城市名 + 坐标**回传给 VisitEditActivity。
 *
 * 为什么用本机的城市表而不是联网搜索：坐标与名称是跨端约定（网页版也用同一份数据），
 * 联网搜出来的城市名与 adcode 未必对得上，地图边界就会取不到。
 *
 * 列表有两种形态（见 applyFilter）：
 *  - 没在搜索 = **按省份分组的浏览态**：组标题吸顶，顺着省份滚下去就能找到城市；
 *  - 在搜索 = 平铺的匹配结果：结果跨省，行尾补上省份，同名或近名的市才分得清。
 */
class CityPickerActivity : AppCompatActivity() {

    // 非空 lateinit：Activity 与视图同生共死，不必在 onDestroy 里置 null
    private lateinit var binding: ActivityCityPickerBinding
    private lateinit var adapter: CityRowAdapter
    private var all: List<City> = emptyList()

    /** 浏览态的行（进页面时分好组，之后在搜索与清空之间来回切就不用再分一次）。 */
    private var grouped: List<CityRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCityPickerBinding.inflate(layoutInflater)
        val b = binding
        // 必须在 setContentView 之前（见 ui/EdgeToEdge.kt）。
        // 这一页没有底栏，把列表外壳 content 当「底」交给它吃导航栏那截高度。
        // topBar 是带 id 的 <include>，ViewBinding 里是 ViewTopBarBinding 而不是 View，取 .root 才是那条栏本身
        applyEdgeToEdge(b.topBar.root, b.content)
        setContentView(b.root)

        // 顶栏（view_top_bar.xml）不自带文案，返回按钮也默认隐藏：这一页两样都要自己填
        b.topBar.title.setText(R.string.edit_city)
        b.topBar.btnBack.visibility = View.VISIBLE
        b.topBar.btnBack.setOnClickListener { finish() }

        // 同步读一次 asset（四百多条，几毫秒）：为了它做异步反而让页面先空一下再闪出列表。
        // CityStore 内部有内存缓存，第二次打开不再重复解析
        all = CityStore.all(this)
        grouped = groupRows(all)

        adapter = CityRowAdapter { city -> pick(city) }
        b.cityList.layoutManager = LinearLayoutManager(this)
        b.cityList.adapter = adapter
        b.cityList.addItemDecoration(StickyProvinceDecoration(this, adapter))
        // 进页面就是分组态：这才是「顺着省份找过去」的形态，搜索是另一条路径
        adapter.submit(grouped, showProvince = false)

        b.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) = applyFilter(s.toString())
        })
    }

    /**
     * 过滤是本地纯函数（见 logic/CitySearch），边打边过滤，不必等 ime 的搜索键。
     * 查询被清空时回到分组态（并把列表拉回顶部 —— 之前停在搜索结果中间，回去还是那段就白回去了）。
     */
    private fun applyFilter(query: String) {
        if (query.isBlank()) {
            adapter.submit(grouped, showProvince = false)
            binding.cityList.scrollToPosition(0)
            binding.textCityEmpty.visibility = View.GONE
            return
        }
        val matched = CitySearch.filter(all, query)
        adapter.submit(matched.map { CityRow.Entry(it) }, showProvince = true)
        binding.textCityEmpty.visibility = if (matched.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 按「国家 + 省份」分组：两级都按**拼音**排序（Collator + Locale.CHINA）——
     * 「安徽 → 北京 → 重庆 …」才是人顺着往下找的顺序，直接比字符串得到的是码点序，读着乱七八糟。
     * 城市表里没有拼音字段，所以排序交给 Collator，检索仍按名称/省份包含匹配（见 CitySearch）。
     *
     * 分组键带上国家（现在清一色「中国」）：以后加国外城市时，重名的省份（好几个国家都有「中央省」
     * 这类）不会被并进同一组，排序也自然按国家聚成一堆。标题只在非中国城市上显示国家前缀 ——
     * 否则每个标题都挂着「中国 ·」，白占地方。
     *
     * 省份为空的城市归到一个「其他」组里，而不是丢掉：少一座城市比多一个奇怪的分组更难查。
     */
    private fun groupRows(cities: List<City>): List<CityRow> {
        val collator = Collator.getInstance(Locale.CHINA)
        return cities
            .groupBy { it.country to it.province.ifBlank { getString(R.string.city_group_unknown) } }
            .entries
            .sortedWith { a, b ->
                collator.compare(a.key.first, b.key.first).takeIf { it != 0 }
                    ?: collator.compare(a.key.second, b.key.second)
            }
            .flatMap { (key, inGroup) ->
                val (country, province) = key
                val title = if (country == City.DEFAULT_COUNTRY) province else "$country · $province"
                buildList {
                    add(CityRow.Header(getString(R.string.city_group_title, title, inGroup.size)))
                    inGroup.sortedWith { a, b -> collator.compare(a.name, b.name) }
                        .forEach { add(CityRow.Entry(it)) }
                }
            }
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

/**
 * 列表里的一行：省份分组标题（[Header.title] 已拼好文案，吸顶条直接拿去用），或者一座城市。
 * Header 存拼好的字符串而不是省份名 + 数量两个字段：文案只有一处拼装，
 * 列表里的组标题与吸顶条就不可能拼出两种样子。
 */
private sealed class CityRow {
    data class Header(val title: String) : CityRow()
    data class Entry(val city: City) : CityRow()
}

/**
 * 城市列表的适配器，两种行（见 [CityRow]）。
 * 分组态下城市行不再重复显示省份（组标题已经写着），平铺的搜索结果里则必须显示。
 */
private class CityRowAdapter(private val onClick: (City) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<CityRow> = emptyList()
    private var showProvince = false

    fun submit(newRows: List<CityRow>, showProvince: Boolean) {
        rows = newRows
        this.showProvince = showProvince
        notifyDataSetChanged()
    }

    /** 第 [position] 行挂在哪个分组标题下（吸顶条用）：往回找最近的那个。 */
    fun headerAt(position: Int): CityRow.Header? {
        for (i in minOf(position, rows.lastIndex) downTo 0) {
            (rows.getOrNull(i) as? CityRow.Header)?.let { return it }
        }
        return null
    }

    /** 第 [position] 行**之后**第一个分组标题的下标，没有则 -1（吸顶条靠它被下一段顶走）。 */
    fun nextHeaderIndex(position: Int): Int {
        for (i in position + 1 until rows.size) {
            if (rows[i] is CityRow.Header) return i
        }
        return -1
    }

    fun isHeader(position: Int): Boolean = rows.getOrNull(position) is CityRow.Header

    override fun getItemViewType(position: Int): Int = if (isHeader(position)) TYPE_HEADER else TYPE_CITY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemCityGroupBinding.inflate(inflater, parent, false))
        } else {
            EntryHolder(ItemCityBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is CityRow.Header -> (holder as HeaderHolder).bind(row)
            is CityRow.Entry -> (holder as EntryHolder).bind(row.city)
        }
    }

    private class HeaderHolder(private val b: ItemCityGroupBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: CityRow.Header) {
            b.root.text = row.title
        }
    }

    // inner：Holder 要用外层的 onClick 与 showProvince（非 inner 的内部类拿不到外层实例）
    private inner class EntryHolder(private val b: ItemCityBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(city: City) {
            b.textCityName.text = city.name
            b.textProvince.text = city.province
            b.textProvince.visibility = if (showProvince) View.VISIBLE else View.GONE
            b.root.setOnClickListener { onClick(city) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CITY = 1
    }
}

/**
 * 省份分组标题吸顶：滚到哪一段，就把该段的省份钉在列表顶部。
 *
 * 用真实的 item_city_group 布局来画，而不是自己拿 Paint 画一遍文字 —— 吸顶条与列表里那些
 * 分组标题因此永远是同一份样式（改字号、改内边距都只改布局一处）。
 * 下一个分组标题顶上来时，吸顶条被一起往上推出去，看起来就是「被顶走」。
 */
private class StickyProvinceDecoration(
    context: Context,
    private val adapter: CityRowAdapter,
) : RecyclerView.ItemDecoration() {

    private val sticky: TextView = ItemCityGroupBinding.inflate(LayoutInflater.from(context)).root

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val first = parent.getChildAt(0) ?: return
        val position = parent.getChildAdapterPosition(first)
        if (position == RecyclerView.NO_POSITION) return

        // 顶行本身就是分组标题、而且完整露着：它和吸顶条位置重合，再画一张只是白画
        if (adapter.isHeader(position) && first.top == 0) return
        val header = adapter.headerAt(position) ?: return

        sticky.text = header.title
        sticky.measure(
            View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = sticky.measuredHeight

        // 下一个分组标题已经顶进吸顶条的位置时，把它连同吸顶条一起上推（经典吸顶写法）
        var offset = 0
        val nextIndex = adapter.nextHeaderIndex(position)
        if (nextIndex != -1) {
            val next = parent.findViewHolderForAdapterPosition(nextIndex)?.itemView
            if (next != null && next.top < height) offset = next.top - height
        }

        c.save()
        c.translate(0f, offset.toFloat())
        sticky.layout(0, 0, parent.width, height)
        sticky.draw(c)
        c.restore()
    }
}