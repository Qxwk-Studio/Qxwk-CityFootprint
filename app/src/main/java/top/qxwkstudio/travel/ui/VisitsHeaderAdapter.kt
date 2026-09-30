package top.qxwkstudio.travel.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import top.qxwkstudio.travel.databinding.ItemVisitsOverviewBinding

/**
 * 主页页头（未读公告横幅 + 「📊 足迹统计」五张小卡 + 「📍 行程记录」标题与新增按钮），铺成列表的第 0 项。
 *
 * 主页要求**整页一起滚**（原先统计卡钉在上面，只有行程列表自己滚），页头就得归同一个
 * RecyclerView 管，于是做成一个单项 adapter、与 VisitAdapter 一起交给 ConcatAdapter（见 VisitsFragment）。
 *
 * 只有一项：itemCount 恒为 1，值变了直接 notifyItemChanged(0)，不必上 DiffUtil。
 */
class VisitsHeaderAdapter(
    private val onAdd: () -> Unit,
    private val onNotice: () -> Unit,
) : RecyclerView.Adapter<VisitsHeaderAdapter.Holder>() {

    private var overview: VisitsOverview? = null
    private var noticeUnread = false

    fun submit(value: VisitsOverview) {
        overview = value
        notifyItemChanged(0)
    }

    /**
     * 顶部那条未读公告横幅的显隐。与 [submit] **分开传**：公告是异步拉的（见 VisitsFragment.loadNotices），
     * 汇总值与它谁先回来不一定，合并成一个方法的话，后回来的那个会把前一个已写好的状态冲掉。
     * 值没变就直接返回，免得白刷一次页头。
     */
    fun submitNotice(unread: Boolean) {
        if (noticeUnread == unread) return
        noticeUnread = unread
        notifyItemChanged(0)
    }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemVisitsOverviewBinding.inflate(LayoutInflater.from(parent.context), parent, false), onAdd, onNotice)

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(overview, noticeUnread)

    class Holder(
        private val b: ItemVisitsOverviewBinding,
        private val onAdd: () -> Unit,
        private val onNotice: () -> Unit,
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(o: VisitsOverview?, noticeUnread: Boolean) {
            // 首帧还没拉到数据时保持布局里写的占位（0 / —），拉到之后才换成真值
            if (o != null) {
                b.valueRange.text = o.range
                b.valueCities.text = o.cities
                b.valueVisits.text = o.visits
                b.valueProvinces.text = o.provinces
                b.valueTransport.text = o.transport
            }
            // 横幅与汇总值无关：没拉到公告（或没有未读）时它就是 GONE，页头其余部分照常
            b.noticeBanner.visibility = if (noticeUnread) View.VISIBLE else View.GONE
            b.noticeBanner.setOnClickListener { onNotice() }
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