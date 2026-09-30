package top.qxwkstudio.travel.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.Update
import top.qxwkstudio.travel.data.VisitRepo
import top.qxwkstudio.travel.databinding.DialogVisitDetailBinding
import top.qxwkstudio.travel.databinding.FragmentVisitsBinding
import top.qxwkstudio.travel.logic.Notice
import top.qxwkstudio.travel.logic.Visit
import top.qxwkstudio.travel.logic.VisitDate
import top.qxwkstudio.travel.logic.topTransportLabel
import top.qxwkstudio.travel.logic.transportLabels

/**
 * 「主页」：网页端「足迹管理页」的内容 —— 顶部有未读公告时先出一条横幅，然后是足迹统计概览 + 行程列表（下拉刷新）。
 * 接口：GET /api/my-visits（后端按 created_at DESC 排好序）。
 * 本页只「读」和给编辑入口：点条目看只读详情弹窗，铅笔进 VisitEditActivity，
 * 增删改三个请求都在那一页里发（POST / PUT / DELETE）。
 * 顶部那条公告横幅的数据不走上面这个接口，而是网页根下的静态清单（见 [loadNotices]）。
 */
class VisitsFragment : Fragment() {

    private var _binding: FragmentVisitsBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store
    private lateinit var adapter: VisitAdapter
    private lateinit var headerAdapter: VisitsHeaderAdapter

    /**
     * 拉回来的公告（见 [loadNotices]），只为页头那条「有新公告」的横幅服务：
     * 有 id 比 Store.noticeReadId 大的就是未读。
     * 留着它而不是只留一个「有没有未读」的布尔，是因为从公告页回来时要**重新判一次**
     * （那边刚把已读 id 提上去，别再打一次网络 —— 见 [onResume]）。
     */
    private var notices: List<Notice> = emptyList()

    /**
     * 新增/编辑页回来就重新拉一次列表。
     * 为什么需要它、而不是靠 onResume：四个 tab 用 add/hide/show 切换，
     * 本页在别的 tab 显示时**仍是 RESUMED**，从编辑页回来时也不会重新 resume ——
     * 不注册这个回调就会看到「明明保存了，列表还是旧的」。
     */
    private val editResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) load()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVisitsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = Store(requireContext())

        adapter = VisitAdapter(
            onView = { visit -> showDetail(visit) },
            onEdit = { visit -> editResult.launch(VisitEditActivity.intent(requireContext(), visit)) })
        // 页头也是列表的一项（第 0 项），这样整页共用一个滚动容器（见 fragment_visits.xml / VisitsHeaderAdapter）
        headerAdapter = VisitsHeaderAdapter(
            onAdd = { editResult.launch(VisitEditActivity.intent(requireContext(), null)) },
            onNotice = { startActivity(NoticeActivity.intent(requireContext())) })

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = ConcatAdapter(headerAdapter, adapter)
        binding.swipe.setColorSchemeResources(R.color.accent)
        binding.swipe.setOnRefreshListener {
            load(force = true)
            // 顺带重拉公告：横幅的判定数据只在页面创建时取过一次，用户主动下拉就是要「整页刷新」——
            // 不跟着刷新的话，新发的公告要等下次进主页才提示，已经撤回的公告也一直挂在横幅上（见 loadNotices）
            loadNotices()
        }
        // 列表外垫了层 FrameLayout（放空态提示），SwipeRefreshLayout 默认会去问它「滚过没有」，
        // 它永远答没有 —— 于是滑到中间也能下拉。这里把判断交回真正的列表
        binding.swipe.setOnChildScrollUpCallback { _, _ -> binding.list.canScrollVertically(-1) }

        load()
        loadNotices()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /**
     * 这一页在别的 tab 显示时仍是 RESUMED（四个 tab 是 add/hide/show，见 MainActivity），
     * 所以 onResume 只在「从公告页回来 / 整个 Activity 回到前台」时才轮到它 ——
     * 正好是重新判一次横幅显隐的时机：用户刚在公告页把已读 id 提上去了，
     * 那条横幅要立刻收掉，否则看起来像「点了没生效」。
     *
     * 这里**只重判、不打网络**：公告清单还在 [notices] 里，读没读过看的是本机的已读 id。
     */
    override fun onResume() {
        super.onResume()
        refreshNoticeBanner()
    }

    /**
     * 拉公告（与「检查更新」同一个静态清单，见 data/Update），决定页头那条横幅显不显示。
     * **失败就静默**：公告不是这一页的主体，为主页拉不到公告弹一句错只会打扰人，
     * 横幅不出现即可（公告页那边相反，拉不到要明确说一声，见 NoticeActivity.load）。
     *
     * 只在两处调用：**页面创建**（onViewCreated）与**用户下拉刷新**。
     * 切 tab 不重拉 —— 视图还在，没理由为一条横幅每次切回来都打一次网络；
     * 但下拉刷新是用户主动要「整页重来」，所以要跟着取一次，
     * 否则横幅会停在打开这一页那一刻的快照上：这期间新发的公告不冒出来、撤回的公告也不收
     * （维护者发的公告随时在变，而这一页可能挂在前台很久）。
     */
    private fun loadNotices() {
        viewLifecycleOwner.lifecycleScope.runIo({ Update.fetch() }) { result ->
            notices = result.getOrNull()?.notices.orEmpty()
            refreshNoticeBanner()
        }
    }

    /** 清单里只要有一条 id 比「已读的最大 id」大，就是有未读（判定口径见 logic/Models 的 [Notice]）。 */
    private fun refreshNoticeBanner() {
        headerAdapter.submitNotice(notices.any { it.id > store.noticeReadId })
    }

    /** [force] = true 跳过一天缓存强制走网络（下拉刷新用）。 */
    private fun load(force: Boolean = false) {
        val token = store.token
        if (token == null) {
            // 正常进不来（MainActivity 已判定登录态）；真发生就按统一流程回登录页，而不是空列表
            Session.expired(requireActivity())
            return
        }
        binding.swipe.isRefreshing = true
        binding.textEmpty.visibility = View.GONE

        // 挂在 viewLifecycleOwner 上：页面销毁时协程自动取消，
        // 所以回调里不用再判 binding 是否还在（见 ui/Coroutines 的说明）
        viewLifecycleOwner.lifecycleScope.runIo({ VisitRepo.myVisits(requireContext(), token, force) }) { result ->
            // 下拉刷新转圈必须停：失败时不停，用户会以为还在加载
            binding.swipe.isRefreshing = false

            val data = result.getOrNull()
            if (data == null) {
                // 401 在这条链路里统一处理（清 token 回登录页 + 只弹一次），其余弹后端文案
                activity?.handleApiFailure(result.exceptionOrNull() ?: RuntimeException())
                return@runIo
            }
            val visits = data.visits
            adapter.submit(visits)
            // 铺完内容重新调度一次「逐条淡入」（动画见 res/anim/anim_layout_items）：
            // layoutAnimation 只在容器下一次布局时播，而首帧布局时数据还没回来，那一次是空列表 ——
            // 不补这一下，动画就白挂了。下拉刷新同样走这里，于是刷新也会重播一遍
            binding.list.scheduleLayoutAnimation()
            binding.textEmpty.visibility = if (visits.isEmpty()) View.VISIBLE else View.GONE
            renderOverview(visits)
        }
    }

    /**
     * 概览：时间跨度 / 去过城市 / 足迹总数 / 覆盖省份 / 最常用出行。
     * 与网页端 docs/visits.html 的 updateVisitStats 同口径：
     *  - 城市去重（同一座城打卡多次只算一座）；
     *  - 省份按城市名在本机 assets/cities.json 里查，查不到的**不计入**（与网页端
     *    visits.js 的 filter(Boolean)、全站统计页同一口径 —— 别退回「未知」占名额那一版）；
     *  - 日期直接按字符串排序取首尾（数据形如 2024 或 2024-08，字典序即时间序）；
     *  - 最常用出行按「有多少条足迹用过它」投票，并列时取枚举里靠前的那个（见 logic/Transport）。
     */
    private fun renderOverview(visits: List<Visit>) {
        val cityNames = visits.map { it.city }.distinct()

        val allCities = CityStore.all(requireContext())
        val provinces = cityNames.mapNotNull { name ->
            allCities.firstOrNull { it.name == name }?.province?.takeIf { it.isNotBlank() }
        }.toSet()

        val dates = visits.mapNotNull { it.visitDate }.filter { it.isNotBlank() }.sorted()

        headerAdapter.submit(
            VisitsOverview(
                range = if (dates.isEmpty()) "—" else "${dates.first()} → ${dates.last()}",
                cities = cityNames.size.toString(),
                visits = visits.size.toString(),
                provinces = provinces.size.toString(),
                transport = topTransportLabel(visits) ?: "—",
            )
        )
    }

    /**
     * 「查看」弹窗：列表里备注只占一行、多的用省略号收住，完整内容在这里看。
     * 刻意做成**只读**：要改走右侧那颗铅笔进编辑页（删除也在编辑页里），
     * 免得一个弹窗既当详情又当表单，改起来还得判断「填了没」。
     */
    private fun showDetail(visit: Visit) {
        val d = DialogVisitDetailBinding.inflate(layoutInflater)
        d.textCity.text = visit.city
        d.textPrivate.visibility = if (visit.isPrivate) View.VISIBLE else View.GONE
        d.textDate.text = VisitDate.display(visit.visitDate)

        // 空的行整行隐藏（含它的小标签）：出行方式没填、备注没写都是常态，
        // 留一个空标题比少一行更难看
        val transports = transportLabels(visit.transport)
        d.rowTransport.visibility = if (transports.isEmpty()) View.GONE else View.VISIBLE
        // 多个方式拼成「甲 / 乙」，与列表里那排徽章的表达一致（弹窗里不再摆可点的 chip）
        d.textTransport.text = transports.joinToString(" / ")

        d.rowNote.visibility = if (visit.note.isBlank()) View.GONE else View.VISIBLE
        d.textNote.text = visit.note

        // MaterialAlertDialogBuilder：M3 那套圆角 28dp 的弹窗壳（appcompat 的 AlertDialog
        // 走的是系统弹窗外观 —— 方角、系统按钮，和全站这套卡片界面不是一回事）
        MaterialAlertDialogBuilder(requireContext())
            .setView(d.root)
            .setPositiveButton(R.string.visits_detail_close, null)
            .show()
    }
}