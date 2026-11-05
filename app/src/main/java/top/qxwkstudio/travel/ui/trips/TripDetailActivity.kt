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
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.TripRepo
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.databinding.ActivityDetailBinding
import top.qxwkstudio.travel.logic.Dates
import top.qxwkstudio.travel.logic.Timeline

/**
 * 足迹详情（只读）：右上角「编辑」进编辑器。
 *
 * 布局与打卡详情一致：主卡片（编号 / 标题 / UUID）+ 逐项卡片 + 每个城市段一张卡。
 * 没填的字段显示「-」，不隐藏，方便一眼看出还缺什么。
 */
class TripDetailActivity : AppCompatActivity() {

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
        lifecycleScope.runIo({ TripRepo.byId(AppDatabase.get(this), tripId) }) { result ->
            val (entity, timeline) = result.getOrNull() ?: run {
                notify(R.string.common_error, long = true)
                finish()
                return@runIo
            }
            currentTitle = entity.title
            render(entity.title, entity.seqNo, entity.uuid, entity.note, timeline)
        }
    }

    private fun render(title: String, seq: Int, uuid: String, note: String?, timeline: Timeline) {
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
                R.drawable.ic_span,
                getString(R.string.trip_range_label),
                Dates.ymd(timeline.startTime) + " — " + Dates.ymd(timeline.endTime),
            ),
        )
        content.addView(
            fieldCard(
                R.drawable.ic_trips,
                getString(R.string.trip_stats_label),
                getString(R.string.trip_stats_value, timeline.cityCount, timeline.eventCount),
            ),
        )
        val totalCost = timeline.segments.sumOf { segment -> segment.events.sumOf { it.costCents ?: 0 } }
        content.addView(
            fieldCard(
                R.drawable.ic_wallet,
                getString(R.string.checkin_amount_label),
                if (totalCost > 0) getString(R.string.home_cost_value, (totalCost / 100).toString()) else "-",
            ),
        )
        content.addView(
            fieldCard(
                R.drawable.ic_note,
                getString(R.string.checkin_note),
                note?.takeIf { it.isNotBlank() } ?: "-",
            ),
        )

        // 到访城市：每个城市段一张卡
        content.addView(sectionTitle(R.drawable.ic_city, getString(R.string.detail_city_section)))
        timeline.segments.forEach { segment ->
            val segmentCard = card()
            // 1 城市
            val segmentRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            segmentRow.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_city)
                imageTintList = android.content.res.ColorStateList.valueOf(Palette.accent(this@TripDetailActivity))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            })
            segmentRow.addView(TextView(this).apply {
                text = segment.city
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.fg))
                setPadding(dp(8), 0, 0, 0)
            })
            segmentCard.addView(segmentRow)

            // 2 同行人（左） / 城市评分（右）
            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            row2.addView(labeledValue(R.drawable.ic_person, getString(R.string.companions_label), segment.companions ?: "-"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row2.addView(labeledValue(R.drawable.ic_star, getString(R.string.rating_label), segment.rating?.let { getString(R.string.rating_value, it) } ?: "-"))
            segmentCard.addView(row2)

            // 3 备注
            segmentCard.addView(labeledValue(R.drawable.ic_note, getString(R.string.checkin_note), segment.note ?: "-", topMargin = 8))

            // 4 照片标题
            segmentCard.addView(labeledValue(R.drawable.ic_camera, getString(R.string.photo_label), "", topMargin = 12))

            // 5 照片：最多 3 张，固定三槽
            val paths = segment.photoPath?.split(",")?.filter { it.isNotBlank() }.orEmpty()
            val photoRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            if (paths.isEmpty()) {
                photoRow.addView(TextView(this).apply {
                    text = "-"
                    textSize = 14f
                    setTextColor(getColor(R.color.text_tertiary))
                    setPadding(dp(12), 0, 0, 0)
                })
            } else {
                for (slot in 0 until 3) {
                    val path = paths.getOrNull(slot)
                    val lp = LinearLayout.LayoutParams(0, dp(96), 1f).apply {
                        if (slot < 2) marginEnd = dp(8)
                    }
                    if (path == null) {
                        photoRow.addView(View(this).apply { layoutParams = lp })
                        continue
                    }
                    val tile = android.widget.FrameLayout(this).apply {
                        layoutParams = lp
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
            }
            segmentCard.addView(photoRow)

            // 6 时间线
            segmentCard.addView(labeledValue(R.drawable.ic_span, getString(R.string.trip_segment_timeline_label), "", topMargin = 12))
            if (segment.events.isEmpty()) {
                segmentCard.addView(labeledValue(R.drawable.ic_span, getString(R.string.trip_event_label), "-", topMargin = 6))
            } else {
                segment.events.forEach { event ->
                    val bullet = android.widget.FrameLayout(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT)
                        addView(View(this@TripDetailActivity).apply {
                            setBackgroundColor(getColor(R.color.border))
                            layoutParams = android.widget.FrameLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                        })
                        addView(View(this@TripDetailActivity).apply {
                            background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(Palette.accent(this@TripDetailActivity))
                            }
                            layoutParams = android.widget.FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                                .apply { topMargin = dp(18) }
                        })
                    }
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp(10), 0, dp(10))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val top = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    top.addView(Chips.tag(this, Dates.ymd(event.time)))
                    top.addView(Chips.tag(this, hm(event.time)).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = dp(6) }
                    })
                    top.addView(Chips.tag(this, typeLabel(event.type)).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = dp(6) }
                    })
                    box.addView(top)
                    val titleRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(6), 0, 0)
                    }
                    titleRow.addView(TextView(this).apply {
                        text = event.text
                        textSize = 14f
                        setTextColor(getColor(R.color.fg))
                    })
                    if ((event.costCents ?: 0) > 0) {
                        titleRow.addView(TextView(this).apply {
                            text = getString(R.string.home_cost_value, ((event.costCents ?: 0) / 100).toString())
                            textSize = 11f
                            setTextColor(getColor(R.color.on_accent))
                            setPadding(dp(7), dp(2), dp(7), dp(2))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius = dp(6).toFloat()
                                setColor(getColor(R.color.badge_cost))
                            }
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { marginStart = dp(6) }
                        })
                    }
                    box.addView(titleRow)
                    if (!event.note.isNullOrBlank()) {
                        box.addView(TextView(this).apply {
                            text = event.note
                            textSize = 12f
                            setTextColor(getColor(R.color.text_tertiary))
                            setPadding(0, dp(4), 0, 0)
                        })
                    }
                    segmentCard.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(bullet)
                        addView(box)
                    })
                }
            }
            content.addView(segmentCard)
        }
    }
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
