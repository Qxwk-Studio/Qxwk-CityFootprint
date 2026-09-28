package top.qxwkstudio.travel.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.databinding.ItemVisitBinding
import top.qxwkstudio.travel.logic.Visit
import top.qxwkstudio.travel.logic.VisitDate
import top.qxwkstudio.travel.logic.transportLabels

/**
 * 足迹列表（对齐网页端 docs/visits.html 左栏的「行程记录」）：每条足迹一张独立小卡，
 * 卡内是「序号圆点 + 城市·时间 + 出行方式徽章 + 备注」。
 *
 * 点击进编辑、**长按删除**：删除是不可逆动作，不给一个随手能点到的按钮，
 * 但长按之后还有一次二次确认（见 VisitsFragment）。网页端是显式的分享/编辑/删除三颗按钮，
 * 这里刻意保留原生手势 —— 一排按钮在窄屏上会把城市名挤没。
 *
 * 用 notifyDataSetChanged 而不是 DiffUtil：每次刷新拿到的是**完整列表**（后端已排好序），
 * 条目也就是几十条，DiffUtil 的比较器写出来换不到可感知的收益。真到了列表很长的那天再说。
 */
class VisitAdapter(
    private val onEdit: (Visit) -> Unit,
    private val onLongPress: (Visit) -> Unit,
) : RecyclerView.Adapter<VisitAdapter.Holder>() {

    private val items = mutableListOf<Visit>()

    fun submit(list: List<Visit>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemVisitBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    // 序号要「越新越大」（最新那条 = 总条数），所以得把列表长度一起交给 Holder
    override fun onBindViewHolder(holder: Holder, position: Int) =
        holder.bind(items[position], items.size - position)

    inner class Holder(private val b: ItemVisitBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(v: Visit, index: Int) {
            b.textIndex.text = index.toString()
            b.textCity.text = v.city
            // 库里存 "2024-08"，列表显示 "2024年8月"：存储形态与展示形态分开（见 logic/VisitDate）
            b.textMeta.text = b.root.context.getString(R.string.visits_meta, VisitDate.display(v.visitDate))

            // 私密标记只在私密时出现（不是「公开/私密」两态都显示一个标签）
            b.textPrivate.visibility = if (v.isPrivate) View.VISIBLE else View.GONE

            b.textNote.text = v.note
            b.textNote.visibility = if (v.note.isNotBlank()) View.VISIBLE else View.GONE

            bindTransport(v.transport)

            b.root.setOnClickListener { onEdit(v) }
            b.root.setOnLongClickListener {
                onLongPress(v)
                true // 消费掉事件，避免同时触发点击
            }
        }

        /**
         * 出行方式徽章：先清空再按顺序铺。清空是必须的 —— RecyclerView 复用 Holder 时，
         * 上一条的徽章会留在视图里（这是「条目串味」的经典来源）。
         * 一条都没填就整组隐藏，不留一条空白带。
         */
        private fun bindTransport(codes: List<String>) {
            val labels = transportLabels(codes)
            b.groupTransport.removeAllViews()
            b.groupTransport.visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
            labels.forEach { label ->
                val chip = LayoutInflater.from(b.root.context)
                    .inflate(R.layout.item_tp_badge, b.groupTransport, false) as Chip
                chip.text = label
                b.groupTransport.addView(chip)
            }
        }
    }
}