package top.qxwkstudio.travel.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.databinding.ItemVisitBinding
import top.qxwkstudio.travel.logic.Visit
import top.qxwkstudio.travel.logic.VisitDate

/**
 * 足迹列表。
 *
 * 用 notifyDataSetChanged 而不是 DiffUtil：每次刷新拿到的是**完整列表**（后端已排好序），
 * 条目也就是几十条，DiffUtil 的比较器写出来换不到可感知的收益。真到了列表很长的那天再说。
 *
 * 点击进编辑、**长按删除**：删除是不可逆动作，不给一个随手能点到的按钮，
 * 但长按之后还有一次二次确认（见 VisitsFragment）。
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

    // 最后一行要特殊处理（不画底分隔线），所以把位置信息一起交给 Holder
    override fun onBindViewHolder(holder: Holder, position: Int) =
        holder.bind(items[position], position == items.size - 1)

    inner class Holder(private val b: ItemVisitBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(v: Visit, isLast: Boolean) {
            b.textCity.text = v.city
            // 库里存 "2024-08"，列表显示 "2024年8月"：存储形态与展示形态分开（见 logic/VisitDate）
            b.textDate.text = VisitDate.display(v.visitDate)

            // 日期与备注之间那个「·」只在有备注时出现，否则会剩一个孤零零的点
            val hasNote = v.note.isNotBlank()
            b.textNote.text = v.note
            b.textNote.visibility = if (hasNote) View.VISIBLE else View.GONE
            b.textSep.visibility = if (hasNote) View.VISIBLE else View.GONE

            // 私密标记只在私密时出现（不是「公开/私密」两态都显示一个标签）
            b.textPrivate.visibility = if (v.isPrivate) View.VISIBLE else View.GONE

            // 最后一行不画底分隔线：列表卡片自带的底描边已经在那儿了，再画一条就成双线
            b.root.setBackgroundResource(if (isLast) 0 else R.drawable.row_divider_bg)

            b.root.setOnClickListener { onEdit(v) }
            b.root.setOnLongClickListener {
                onLongPress(v)
                true // 消费掉事件，避免同时触发点击
            }
        }
    }
}
