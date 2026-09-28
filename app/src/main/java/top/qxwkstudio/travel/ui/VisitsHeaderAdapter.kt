package top.qxwkstudio.travel.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import top.qxwkstudio.travel.databinding.ItemVisitsOverviewBinding

/**
 * 主页页头（「📊 足迹统计」五张小卡 + 「📍 行程记录」标题与新增按钮），铺成列表的第 0 项。
 *
 * 主页要求**整页一起滚**（原先统计卡钉在上面，只有行程列表自己滚），页头就得归同一个
 * RecyclerView 管，于是做成一个单项 adapter、与 VisitAdapter 一起交给 ConcatAdapter（见 VisitsFragment）。
 *
 * 只有一项：itemCount 恒为 1，值变了直接 notifyItemChanged(0)，不必上 DiffUtil。
 */
class VisitsHeaderAdapter(private val onAdd: () -> Unit) : RecyclerView.Adapter<VisitsHeaderAdapter.Holder>() {

    private var overview: VisitsOverview? = null

    fun submit(value: VisitsOverview) {
        overview = value
        notifyItemChanged(0)
    }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemVisitsOverviewBinding.inflate(LayoutInflater.from(parent.context), parent, false), onAdd)

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(overview)

    class Holder(
        private val b: ItemVisitsOverviewBinding,
        private val onAdd: () -> Unit,
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(o: VisitsOverview?) {
            // 首帧还没拉到数据时保持布局里写的占位（0 / —），拉到之后才换成真值
            if (o != null) {
                b.valueRange.text = o.range
                b.valueCities.text = o.cities
                b.valueVisits.text = o.visits
                b.valueProvinces.text = o.provinces
                b.valueTransport.text = o.transport
            }
            b.btnAdd.setOnClickListener { onAdd() }
        }
    }
}

/**
 * 主页五张小卡要显示的文本。全部是**已格式化**的串 —— 汇总口径留在 VisitsFragment.renderOverview，
 * 这里只负责搬运，免得同一套「城市去重 / 省份查表 / 日期取首尾」的规则散到两个文件里。
 */
data class VisitsOverview(
    val range: String,
    val cities: String,
    val visits: String,
    val provinces: String,
    val transport: String,
)