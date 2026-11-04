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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.TripRepo
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.databinding.ActivityRecordDetailBinding
import top.qxwkstudio.travel.logic.Dates
import top.qxwkstudio.travel.logic.Timeline

/**
 * 足迹详情（只读）：右上角「编辑」进编辑器。
 *
 * 列表点击先进详情、再决定要不要改 —— 直接进编辑器既容易误触，也让「看」这件事变成了一次
 * 可编辑的表单。详情页把外层城市段与内层事件都按层级摆开。
 */
class TripDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordDetailBinding
    private var tripId = 0L

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) load() else load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme.applyStyle(Palette.selected(this).overlay, true)
        binding = ActivityRecordDetailBinding.inflate(layoutInflater)
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
        load()
    }

    private fun load() {
        lifecycleScope.runIo({ TripRepo.byId(AppDatabase.get(this), tripId) }) { result ->
            val (entity, timeline) = result.getOrNull() ?: run {
                notify(R.string.common_error, long = true)
                finish()
                return@runIo
            }
            render(entity.title, entity.seqNo, entity.note, timeline)
        }
    }

    private fun render(title: String, seq: Int, note: String?, timeline: Timeline) {
        val content = binding.content
        content.removeAllViews()

        val header = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(seqBadge(seq, R.color.stripe_trip))
        row.addView(TextView(this).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.fg))
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(row)
        header.addView(metaRow(R.drawable.ic_span, Dates.ymd(timeline.startTime) + " — " + Dates.ymd(timeline.endTime)))
        if (!note.isNullOrBlank()) header.addView(metaRow(R.drawable.ic_note, note))
        content.addView(header)

        content.addView(sectionTitle(getString(R.string.detail_city_section)))
        timeline.segments.forEach { segment ->
            val segmentCard = card()
            val segmentRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            segmentRow.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_city)
                imageTintList = android.content.res.ColorStateList.valueOf(Palette.accent(this@TripDetailActivity))
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            })
            segmentRow.addView(TextView(this).apply {
                text = segment.city
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.fg))
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            segmentCard.addView(segmentRow)

            if (!segment.transport.isNullOrBlank()) {
                segmentCard.addView(metaRow(R.drawable.ic_trips, "到达：" + segment.transport))
            }
            if (!segment.note.isNullOrBlank()) segmentCard.addView(metaRow(R.drawable.ic_note, segment.note))
            if (!segment.companions.isNullOrBlank()) {
                segmentCard.addView(metaRow(R.drawable.ic_person, segment.companions))
            }
            if (segment.rating != null) segmentCard.addView(stars(segment.rating))
            segment.events.forEach { event ->
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(6), 0, 0)
                }
                line.addView(TextView(this).apply {
                    text = if (event.type == Timeline.Type.RIDE) "交通" else "活动"
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setBackgroundResource(R.drawable.bg_first_visit)
                    setPadding(dp(6), dp(1), dp(6), dp(1))
                })
                line.addView(TextView(this).apply {
                    text = event.text
                    textSize = 14f
                    setTextColor(getColor(R.color.fg))
                    setPadding(dp(8), 0, 0, 0)
                })
                line.addView(TextView(this).apply {
                    text = Dates.ymd(event.time)
                    textSize = 12f
                    setTextColor(getColor(R.color.text_tertiary))
                    setPadding(dp(8), 0, 0, 0)
                })
                segmentCard.addView(line)
                if (!event.note.isNullOrBlank()) {
                    segmentCard.addView(metaRow(R.drawable.ic_note, event.note))
                }
            }
            if (segment.events.isEmpty()) {
                segmentCard.addView(metaRow(R.drawable.ic_note, getString(R.string.detail_no_note)))
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

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(getColor(R.color.text_secondary))
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun metaRow(icon: Int, text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(6), 0, 0)
        addView(ImageView(this@TripDetailActivity).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@TripDetailActivity, R.color.text_tertiary),
            )
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
        })
        addView(TextView(this@TripDetailActivity).apply {
            this.text = text
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@TripDetailActivity, R.color.text_secondary))
            setPadding(dp(6), 0, 0, 0)
        })
    }

    private fun seqBadge(seq: Int, colorRes: Int): TextView = TextView(this).apply {
        text = getString(R.string.trips_trip_seq, seq)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ColorWhite)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(ContextCompat.getColor(context, colorRes))
        }
    }

    private fun stars(rating: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), 0, 0)
        }
        for (star in 1..5) {
            row.addView(ImageView(this).apply {
                setImageResource(if (rating >= star) R.drawable.ic_star else R.drawable.ic_star_outline)
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.first_visit))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            })
        }
        return row
    }

    private val ColorWhite get() = ContextCompat.getColor(this, R.color.on_accent)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ID = "trip_id"
        fun intent(context: Context, tripId: Long): Intent =
            Intent(context, TripDetailActivity::class.java).putExtra(EXTRA_ID, tripId)
    }
}
