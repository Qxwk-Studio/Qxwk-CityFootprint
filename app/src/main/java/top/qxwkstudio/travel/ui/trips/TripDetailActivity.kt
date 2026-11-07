package top.qxwkstudio.travel.ui.trips

import top.qxwkstudio.travel.ui.common.*

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import top.qxwkstudio.travel.ui.common.LocalizedActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.CheckinRepo
import top.qxwkstudio.travel.data.CityStore
import top.qxwkstudio.travel.data.TripRepo
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.databinding.ActivityDetailBinding
import top.qxwkstudio.travel.logic.Dates
import top.qxwkstudio.travel.logic.Money
import top.qxwkstudio.travel.logic.Provinces
import top.qxwkstudio.travel.logic.Timeline

/**
 * 足迹详情（只读）：右上角「编辑」进编辑器。
 *
 * 布局与打卡详情一致：主卡片（编号 / 标题 / UUID）+ 逐项卡片 + 每个城市段一张卡。
 * 没填的字段显示「-」，不隐藏，方便一眼看出还缺什么。
 */
class TripDetailActivity : LocalizedActivity() {

    private lateinit var binding: ActivityDetailBinding
    private var tripId = 0L

    /** 删除确认弹窗里显示的标题。 */
    private var currentTitle: String? = null

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        load()
    }

    override fun onResume() {
        super.onResume()
        if (tripId != 0L) load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        applyEdgeToEdge(binding.topBar.root, null)
        setContentView(binding.root)

        tripId = intent.getLongExtra(EXTRA_ID, 0L)
        binding.topBar.title.setText(R.string.trip_detail_title)
        binding.topBar.btnBack.visibility = View.VISIBLE
        binding.topBar.btnBack.setOnClickListener { finish() }
        binding.topBar.btnMenu.visibility = View.VISIBLE
        binding.topBar.btnMenu.setImageResource(R.drawable.ic_edit)
        binding.topBar.btnMenu.contentDescription = getString(R.string.detail_edit)
        binding.topBar.btnMenu.setOnClickListener {
            editor.launch(TripEditActivity.intent(this, tripId))
        }
        binding.topBar.btnDelete.visibility = View.VISIBLE
        binding.topBar.btnDelete.setOnClickListener { confirmDelete() }
        load()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, currentTitle ?: getString(R.string.trip_detail_title)))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                lifecycleScope.runIo({ TripRepo.delete(AppDatabase.get(this), tripId) }) { result ->
                    if (result.isFailure) {
                        notify(result.exceptionOrNull()?.message ?: getString(R.string.common_error), long = true)
                        return@runIo
                    }
                    setResult(Activity.RESULT_OK)
                    notify(R.string.trips_deleted)
                    finish()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun load() {
        lifecycleScope.runIo({
            val db = AppDatabase.get(this)
            val trip = TripRepo.byId(db, tripId)
            val checkins = CheckinRepo.list(db)
            Triple(trip, checkins, db)
        }) { result ->
            val (trip, checkins, _) = result.getOrNull() ?: run {
                notify(R.string.common_error, long = true)
                finish()
                return@runIo
            }
            val (entity, timeline) = trip ?: run {
                notify(R.string.common_error, long = true)
                finish()
                return@runIo
            }
            // 每座城市最早的一条打卡若属于本行程，城市卡显示「首访」
            val firstVisitCities = checkins
                .groupBy { it.cityName }
                .mapNotNull { (city, list) ->
                    val earliest = list.minWithOrNull(compareBy<top.qxwkstudio.travel.data.db.CheckinEntity> { it.checkinAt }.thenBy { it.id })
                    if (earliest?.tripUuid == entity.uuid) city else null
                }
                .toSet()
            currentTitle = entity.title
            render(entity.title, entity.seqNo, entity.uuid, entity.note, timeline, firstVisitCities)
        }
    }

    private fun render(title: String, seq: Int, uuid: String, note: String?, timeline: Timeline, firstVisitCities: Set<String>) {
        val content = binding.content
        content.removeAllViews()

        // 主卡片：编号 + 标题 / 编号数据 / UUID
        val header = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(seqBadge(seq, R.color.stripe_trip))
        row.addView(TextView(this).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.fg))
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(row)
        header.addView(TextView(this).apply {
            text = getString(R.string.detail_own_id, seq)
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(12), dp(6), 0, 0)
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.detail_uuid, uuid)
            textSize = 11f
            setTextColor(getColor(R.color.text_tertiary))
            setPadding(dp(12), dp(4), 0, 0)
        })
        content.addView(header)

        // 基本信息逐项卡片
        content.addView(
            fieldCard(
                R.drawable.ic_calendar,
                getString(R.string.trip_range_label),
                Dates.ymd(timeline.startTime) + " — " + Dates.ymd(timeline.endTime),
            ),
        )
        content.addView(
            fieldCard(
                R.drawable.ic_luggage,
                getString(R.string.trip_stats_label),
                getString(R.string.trip_stats_value, timeline.cityCount, timeline.eventCount),
            ),
        )
        val totalCost = timeline.totalCostCents
        content.addView(
            fieldCard(
                R.drawable.ic_wallet,
                getString(R.string.checkin_amount_label),
                if (totalCost > 0) getString(R.string.home_cost_value, Money.yuan(totalCost)) else "-",
            ),
        )
        content.addView(
            fieldCard(
                R.drawable.ic_note,
                getString(R.string.checkin_note),
                note?.takeIf { it.isNotBlank() } ?: "-",
            ),
        )

        // 打卡城市：按城市段生成与打卡列表一致的紧凑卡片
        content.addView(sectionTitle(R.drawable.ic_city, getString(R.string.detail_checkin_cities)))
        val cityItems = timeline.items.filter { it.type == Timeline.Type.VISIT }
        val cityColors = CityColors.assign(cityItems.map { it.id })
        val segmentCities = cityItems
        timeline.segments.forEachIndexed { index, segment ->
            val color = segmentCities.getOrNull(index)?.let { cityColors[it.id] }
                ?: getColor(R.color.stripe_derived)
            content.addView(cityCheckinCard(seq, index + 1, segment, segment.city in firstVisitCities, color))
        }

        // 时间线：剔除城市项，只显示活动/交通
        content.addView(sectionTitle(R.drawable.ic_luggage, getString(R.string.trip_timeline_label)))
        var currentColor = getColor(R.color.text_tertiary)
        timeline.items.forEach { item ->
            if (item.type == Timeline.Type.VISIT) {
                currentColor = cityColors[item.id] ?: currentColor
            } else {
                content.addView(eventTimelineRow(item, currentColor))
            }
        }
    }

    /** 打卡城市卡：竖条 + 编号 + 城市/省份 + 金额；第二行同行人 / 评分·天数·日期。 */
    private fun cityCheckinCard(seq: Int, segmentOrdinal: Int, segment: Timeline.Segment, firstVisit: Boolean, color: Int): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_card)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        root.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_card_stripe)
            backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            layoutParams = LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT)
        })
        val frame = WatermarkLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            watermarkText = Provinces.abbr(segment.province)
            watermarkSizeSp = 90f
            setWatermarkColor(color)
        }
        val cardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        frame.addView(cardContent)
        root.addView(frame)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(seqBadgeText(getString(R.string.trips_derived_seq, seq, segmentOrdinal), color))
        header.addView(TextView(this).apply {
            text = CityStore.displayName(this@TripDetailActivity, segment.city)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.fg))
            setPadding(dp(8), 0, 0, 0)
        })
        if (!segment.province.isNullOrBlank()) {
            header.addView(TextView(this).apply {
                text = "·"
                textSize = 12f
                setTextColor(getColor(R.color.text_tertiary))
                setPadding(dp(4), 0, dp(4), 0)
            })
            header.addView(TextView(this).apply {
                text = segment.province
                textSize = 11f
                setTextColor(getColor(R.color.text_tertiary))
            })
        }
        val cost = segment.events.sumOf { it.costCents ?: 0 }
        if (cost > 0) header.addView(costBadge(cost))
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        // 40dp 尾部：保持与打卡列表卡片同高，首访徽章靠右
        header.addView(android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
            if (firstVisit) addView(firstVisitChip(), android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ))
        })
        cardContent.addView(header)

        val second = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        second.addView(iconText(R.drawable.ic_person, segment.companions?.takeIf { it.isNotBlank() } ?: "-").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val rightItems = mutableListOf<View>()
        rightItems.add(iconText(R.drawable.ic_star, segment.rating?.let { getString(R.string.rating_value, it) } ?: "-"))
        val days = segmentDays(segment.events)
        rightItems.add(iconText(R.drawable.ic_calendar, if (days > 0) getString(R.string.home_days_value, days.toString()) else "-"))
        rightItems.add(iconText(R.drawable.ic_calendar, segment.events.minByOrNull { it.time }?.let { Dates.ymd(it.time) } ?: "-"))
        rightItems.forEachIndexed { index, item ->
            if (index > 0) right.addView(dotView())
            right.addView(item)
        }
        second.addView(right)
        cardContent.addView(second)
        return root
    }

    /** 平板时间线的一行：日期/时间/类型 + 内容、花费、备注、照片。 */
    private fun eventTimelineRow(item: Timeline.Item, dotColor: Int): View {
        val rail = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT)
            addView(View(this@TripDetailActivity).apply {
                setBackgroundColor(getColor(R.color.border))
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    dp(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                )
            })
            addView(View(this@TripDetailActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(dotColor)
                }
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    dp(12), dp(12), Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply { topMargin = dp(18) }
            })
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { bottomMargin = dp(10); marginStart = dp(8) }
        }

        // 第一行：日期 · 时间，右侧项目类型（图标 + 四字）
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_calendar)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_tertiary))
            layoutParams = LinearLayout.LayoutParams(dp(13), dp(13))
        })
        header.addView(TextView(this).apply {
            text = Dates.ymd(item.time)
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(4), 0, 0, 0)
        })
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_clock)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_tertiary))
            layoutParams = LinearLayout.LayoutParams(dp(13), dp(13)).apply { marginStart = dp(8) }
        })
        header.addView(TextView(this).apply {
            text = hm(item.time)
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(4), 0, 0, 0)
        })
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        header.addView(ImageView(this).apply {
            setImageResource(if (item.type == Timeline.Type.RIDE) R.drawable.ic_car else R.drawable.ic_flag)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_tertiary))
            layoutParams = LinearLayout.LayoutParams(dp(13), dp(13))
        })
        header.addView(TextView(this).apply {
            text = typeTitle(item.type)
            textSize = 12f
            setTextColor(getColor(R.color.text_tertiary))
            setPadding(dp(4), 0, 0, 0)
        })
        card.addView(header)

        // 第二行：标题（标题字体）+ 金额徽章
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        titleRow.addView(TextView(this).apply {
            text = item.text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.fg))
        })
        if ((item.costCents ?: 0) > 0) titleRow.addView(costBadge(item.costCents ?: 0))
        card.addView(titleRow)

        // 第三行：照片（没有则整行隐藏）
        val photos = item.photoPath?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        if (photos.isNotEmpty()) {
            val photoRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            photos.take(3).forEach { path ->
                val tile = android.widget.FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(84), 1f).apply { marginEnd = dp(6) }
                    setBackgroundResource(R.drawable.bg_photo_tile)
                    clipToOutline = true
                }
                tile.addView(ImageView(this).apply {
                    setImageBitmap(PhotoStore.load(path, maxPx = 512))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setOnClickListener { showPhotoDialog(this@TripDetailActivity, path) }
                })
                photoRow.addView(tile)
            }
            card.addView(photoRow)
        }

        // 第四行：图标 + 备注（空则 -）
        val noteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        noteRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_note)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_tertiary))
            layoutParams = LinearLayout.LayoutParams(dp(13), dp(13))
        })
        noteRow.addView(TextView(this).apply {
            text = item.note?.takeIf { it.isNotBlank() } ?: "-"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(4), 0, 0, 0)
        })
        card.addView(noteRow)

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(rail)
            addView(card)
        }
    }

    /** 小号花费徽章。 */
    /** 首访角标：金色浅底 + 金边，和打卡列表一致。 */
    private fun firstVisitChip(): TextView = TextView(this).apply {
        setText(R.string.first_visit)
        textSize = 11f
        setTextColor(getColor(R.color.first_visit))
        setBackgroundResource(R.drawable.bg_first_visit)
        setPadding(dp(7), dp(2), dp(7), dp(2))
    }

    private fun costBadge(cents: Int): TextView = TextView(this).apply {
        text = getString(R.string.home_cost_value, Money.yuan(cents))
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(getColor(R.color.on_accent))
        setPadding(dp(7), dp(2), dp(7), dp(2))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(getColor(R.color.badge_cost))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(12) }
    }

    /** 图标 + 文字的小信息。 */
    private fun iconText(icon: Int, text: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@TripDetailActivity).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_tertiary))
            layoutParams = LinearLayout.LayoutParams(dp(13), dp(13))
        })
        addView(TextView(this@TripDetailActivity).apply {
            this.text = text
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(4), 0, 0, 0)
        })
    }

    private fun dotView(): TextView = TextView(this).apply {
        text = "·"
        textSize = 12f
        setTextColor(getColor(R.color.text_tertiary))
        setPadding(dp(3), 0, dp(3), 0)
    }

    private fun typeTitle(type: Timeline.Type): String = getString(
        when (type) {
            Timeline.Type.RIDE -> R.string.trip_type_ride_label
            Timeline.Type.ACTIVITY -> R.string.trip_type_activity_label
            else -> R.string.trip_type_visit_label
        },
    )
    // ── 小部件 ──

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    private fun labeledValue(
        icon: Int,
        label: String,
        value: String,
        topMargin: Int = 0,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(topMargin), 0, 0)
        addView(ImageView(this@TripDetailActivity).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@TripDetailActivity, R.color.text_tertiary),
            )
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
        })
        addView(TextView(this@TripDetailActivity).apply {
            this.text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@TripDetailActivity, R.color.text_secondary))
            setPadding(dp(6), 0, 0, 0)
        })
        if (value.isNotEmpty()) {
            addView(TextView(this@TripDetailActivity).apply {
                this.text = value
                textSize = 14f
                setTextColor(if (value == "-") getColor(R.color.text_tertiary) else getColor(R.color.fg))
                setPadding(dp(10), 0, 0, 0)
            })
        }
    }

    private fun fieldCard(icon: Int, label: String, value: String): LinearLayout {
        val card = card()
        card.addView(fieldLabel(icon, label))
        card.addView(TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(if (value == "-") getColor(R.color.text_tertiary) else getColor(R.color.fg))
            setPadding(dp(12), dp(6), 0, 0)
        })
        return card
    }

    private fun fieldLabel(icon: Int, label: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@TripDetailActivity).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@TripDetailActivity, R.color.text_tertiary),
            )
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
        })
        addView(TextView(this@TripDetailActivity).apply {
            text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@TripDetailActivity, R.color.text_secondary))
            setPadding(dp(6), 0, 0, 0)
        })
    }

    private fun labeledRow(icon: Int, label: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), 0, 0)
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_tertiary))
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
        })
        row.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(6), 0, 0, 0)
        })
        row.addView(TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(if (value == "-") getColor(R.color.text_tertiary) else getColor(R.color.fg))
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.border))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply { topMargin = dp(10) }
    }

    private fun sectionTitle(icon: Int, text: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(16), 0, dp(8))
        addView(ImageView(this@TripDetailActivity).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(Palette.accent(this@TripDetailActivity))
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        addView(TextView(this@TripDetailActivity).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.fg))
            setPadding(dp(6), 0, 0, 0)
        })
    }

    private fun seqBadgeText(badge: String, color: Int): TextView = TextView(this).apply {
        text = badge
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(getColor(R.color.on_accent))
        setPadding(dp(7), dp(2), dp(7), dp(2))
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(color)
        }
    }

    private fun seqBadge(seq: Int, colorRes: Int): TextView = TextView(this).apply {
        text = getString(R.string.trips_trip_seq, seq)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ContextCompat.getColor(context, R.color.on_accent))
        setPadding(dp(7), dp(2), dp(7), dp(2))
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(ContextCompat.getColor(context, colorRes))
        }
    }

    private fun hm(millis: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(java.util.Locale.US, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    private fun typeLabel(type: Timeline.Type): String = when (type) {
        Timeline.Type.RIDE -> "交通"
        Timeline.Type.VISIT -> "到访"
        else -> "活动"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ID = "trip_id"
        fun intent(context: Context, tripId: Long): Intent =
            Intent(context, TripDetailActivity::class.java).putExtra(EXTRA_ID, tripId)
    }
}
