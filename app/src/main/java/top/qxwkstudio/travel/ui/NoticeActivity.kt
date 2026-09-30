package top.qxwkstudio.travel.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.Update
import top.qxwkstudio.travel.databinding.ActivityNoticeBinding
import top.qxwkstudio.travel.databinding.ItemNoticeBinding
import top.qxwkstudio.travel.logic.Notice

/**
 * 公告页：把网页根下清单里的 `notices` 一条条列出来（见 data/Update 与 logic/Models 的 [Notice]）。
 *
 * 数据源与「检查更新」是**同一个静态文件**：整份清单都靠手改，不值得为公告单开一个后端接口，
 * 也不必在两端各维护一份公告（网页 news.html 的公告区是手写的，不跟着这份清单走）。
 *
 * **进页面就算读过**：渲染完就把当前最大的 id 记进 Store（见 Store.noticeReadId），
 * 主页顶部那条「有新公告」的横幅下次自然就收了。不做「划到底才算读」那一套 ——
 * 公告就那么几条，看一眼就够，再多一层判定只会多一种出错的方式。
 */
class NoticeActivity : AppCompatActivity() {

    // 非空 lateinit：Activity 与视图同生共死，不必在 onDestroy 里置 null
    private lateinit var binding: ActivityNoticeBinding
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoticeBinding.inflate(layoutInflater)
        val b = binding
        // 必须在 setContentView 之前（见 ui/EdgeToEdge.kt）。
        // 这一页没有底栏，把内容区 content 当「底」交给它吃导航栏那截高度。
        // topBar 是带 id 的 <include>，ViewBinding 里是 ViewTopBarBinding 而不是 View，取 .root 才是那条栏本身
        applyEdgeToEdge(b.topBar.root, b.content)
        setContentView(b.root)

        // 顶栏（view_top_bar.xml）不自带文案，返回按钮也默认隐藏：这一页两样都要自己填
        b.topBar.title.setText(R.string.notice_title)
        b.topBar.btnBack.visibility = View.VISIBLE
        b.topBar.btnBack.setOnClickListener { finish() }

        store = Store(this)
        load()
    }

    /**
     * 拉清单并渲染。拉不到就弹一句、页面留空 —— 这一页本来就是用户点进来看的，
     * 静默吞掉失败会让人以为「没有公告」，那比一句提示更糟。
     * （主页那条横幅是另一回事：它失败就静默，见 ui/VisitsFragment.loadNotices。）
     */
    private fun load() {
        // 挂在 lifecycleScope 上：这一屏销毁时请求自动取消，回调里不用再判 binding
        lifecycleScope.runIo({ Update.fetch() }) { result ->
            val notices = result.getOrNull()?.notices
            if (notices == null) {
                Toast.makeText(this, R.string.notice_load_failed, Toast.LENGTH_SHORT).show()
                return@runIo
            }
            render(notices)
        }
    }

    /**
     * 逐条摊开。**按 id 从大到小**：最新的一条排在最上面，进来第一眼看到的就是它
     * （清单里是顺着写下来的，id 越大越新）。
     */
    private fun render(notices: List<Notice>) {
        val b = binding
        notices.sortedByDescending { it.id }.forEach { notice ->
            // 每条都新建一个 View（不回收）：公告就这么几条，复用一套 viewType/Holder 反而是负担
            val item = ItemNoticeBinding.inflate(layoutInflater, b.noticeList, false)
            item.textNoticeTitle.text = notice.title
            item.textNoticeBody.text = notice.body
            // 清单里没填日期就整条收起来，别留一段空白把标题顶偏
            item.textNoticeDate.text = notice.date
            item.textNoticeDate.visibility = if (notice.date.isBlank()) View.GONE else View.VISIBLE
            b.noticeList.addView(item.root)
        }
        b.textNoticeEmpty.visibility = if (notices.isEmpty()) View.VISIBLE else View.GONE

        // 进页面即已读：记下最大的那条（Store 那边只涨不落）。清单为空时没有可记的
        notices.maxOfOrNull { it.id }?.let { store.saveNoticeReadId(it) }
    }

    companion object {
        /** 「我的 → 公告」那一行与主页的公告横幅都从这里进来（见 ProfileFragment / VisitsFragment）。 */
        fun intent(context: Context): Intent = Intent(context, NoticeActivity::class.java)
    }
}