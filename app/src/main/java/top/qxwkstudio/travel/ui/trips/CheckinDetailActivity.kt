package top.qxwkstudio.travel.ui.trips

import top.qxwkstudio.travel.ui.common.*
import top.qxwkstudio.travel.ui.common.Palette
import top.qxwkstudio.travel.logic.Provinces
import android.widget.FrameLayout

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
import top.qxwkstudio.travel.data.CheckinRepo
import top.qxwkstudio.travel.data.TripRepo
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.CheckinEntity
import top.qxwkstudio.travel.data.db.CheckinSource
import top.qxwkstudio.travel.databinding.ActivityRecordDetailBinding
import top.qxwkstudio.travel.logic.DatePrecision
import top.qxwkstudio.travel.logic.Dates

/**
 * 打卡详情（只读）：右上角「编辑」只对手动打卡开放 —— 派生打卡是行程的产物，改它等于改行程。
 */
class CheckinDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordDetailBinding
    private var checkinId = 0L

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme.applyStyle(Palette.selected(this).overlay, true)
        binding = ActivityRecordDetailBinding.inflate(layoutInflater)
        applyEdgeToEdge(binding.topBar.root, null)
        setContentView(binding.root)

        checkinId = intent.getLongExtra(EXTRA_ID, 0L)
        binding.topBar.title.setText(R.string.checkin_detail_title)
        binding.topBar.btnBack.visibility = View.VISIBLE
        binding.topBar.btnBack.setOnClickListener { finish() }
        load()
    }

    private fun load() {
        lifecycleScope.runIo({
            val db = AppDatabase.get(this)
            val entity = db.checkins().byId(checkinId)
            val all = CheckinRepo.listChronological(db)
            val sameCity = all.filter { it.cityName == entity?.cityName }
            val first = sameCity.minByOrNull { it.checkinAt }
            val tripSeq = entity?.tripUuid?.let { uuid ->
                TripRepo.list(db).firstOrNull { it.uuid == uuid }?.seqNo
            }
            Triple(entity, first?.id == entity?.id, tripSeq)
        }) { result ->
            val (entity, first, tripSeq) = result.getOrNull() ?: return@runIo
            if (entity == null) { finish(); return@runIo }
            render(entity, first, tripSeq)
        }
    }

    private fun render(entity: CheckinEntity, firstVisit: Boolean, tripSeq: Int?) {
        val derived = entity.source == CheckinSource.DERIVED
        binding.topBar.btnMenu.visibility = if (derived) View.GONE else View.VISIBLE
        binding.topBar.btnMenu.setImageResource(R.drawable.ic_edit)
        binding.topBar.btnMenu.contentDescription = getString(R.string.detail_edit)
        binding.topBar.btnMenu.setOnClickListener {
            editor.launch(CheckinEditActivity.intent(this, checkinId))
        }

        val content = binding.content
        content.removeAllViews()

        // 主卡片：编号 + 城市 + 首访 / 编号数据 / UUID；右侧半透明省份简称水印
        val headerFrame = WatermarkLayout(this).apply {
            setBackgroundResource(R.drawable.bg_card)
            // 水印撑满卡片、超出圆角的部分按卡片轮廓裁掉
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        headerFrame.watermarkText = Provinces.abbr(entity.province)
        headerFrame.watermarkSizeSp = 120f
        headerFrame.setWatermarkColor(
            getColor(if (derived) R.color.stripe_derived else R.color.stripe_manual),
        )
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badgeSeq = if (derived) (tripSeq ?: 0) else entity.seqNo
        row.addView(seqBadge(badgeSeq, if (derived) R.color.stripe_derived else R.color.stripe_manual))
        row.addView(TextView(this).apply {
            text = entity.cityName
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.fg))
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (firstVisit) {
            row.addView(TextView(this).apply {
                text = getString(R.string.first_visit)
                textSize = 11f
                setTextColor(getColor(R.color.first_visit))
                setBackgroundResource(R.drawable.bg_first_visit)
                setPadding(dp(7), dp(2), dp(7), dp(2))
            })
        }
        header.addView(row)
        header.addView(TextView(this).apply {
            text = if (derived) getString(R.string.detail_from_trip, badgeSeq)
            else getString(R.string.detail_own_id, entity.seqNo)
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(12), dp(6), 0, 0)
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.detail_uuid, entity.uuid)
            textSize = 11f
            setTextColor(getColor(R.color.text_tertiary))
            setPadding(dp(12), dp(4), 0, 0)
        })
        headerFrame.addView(header)
        content.addView(headerFrame)
        // 以下与编辑页顺序一致：时间 → 停留天数 → 同行人 → 总花费 → 照片 → 评分 → 备注
        content.addView(fieldCard(R.drawable.ic_span, getString(R.string.checkin_date), dateText(entity)))
        content.addView(
            fieldCard(
                R.drawable.ic_trips,
                getString(R.string.checkin_days_label),
                entity.stayDays?.let { getString(R.string.home_days_value, it.toString()) } ?: "-",
            ),
        )
        content.addView(
            fieldCard(
                R.drawable.ic_person,
                getString(R.string.companions_label),
                entity.companions?.takeIf { it.isNotBlank() } ?: "-",
            ),
        )
        content.addView(
            fieldCard(
                R.drawable.ic_wallet,
                getString(R.string.checkin_amount_label),
                entity.amountCents?.let { getString(R.string.home_cost_value, (it / 100.0).toString()) } ?: "-",
            ),
        )
        // 照片卡：没照片也显示，值用 -
        val photoCard = newCard()
        photoCard.addView(fieldLabel(R.drawable.ic_camera, getString(R.string.photo_label)))
        val bitmap = PhotoStore.load(entity.photoPath, maxPx = 800)
        if (bitmap != null) {
            photoCard.addView(ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(200),
                ).apply { topMargin = dp(10); marginStart = dp(12) }
                setOnClickListener { showPhotoDialog(this@CheckinDetailActivity, entity.photoPath) }
            })
        } else {
            photoCard.addView(TextView(this).apply {
                text = "-"
                textSize = 15f
                setTextColor(getColor(R.color.text_tertiary))
                setPadding(dp(12), dp(6), 0, 0)
            })
        }
        content.addView(photoCard)

        // 评分卡：未评分也显示 -
        val ratingCard = newCard()
        ratingCard.addView(fieldLabel(R.drawable.ic_star, getString(R.string.rating_label)))
        if (entity.rating != null) {
            val starsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(6), 0, 0)
            }
            starsRow.addView(stars(entity.rating))
            starsRow.addView(TextView(this).apply {
                text = getString(R.string.rating_value, entity.rating)
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(10), 0, 0, 0)
            })
            ratingCard.addView(starsRow)
        } else {
            ratingCard.addView(TextView(this).apply {
                text = "-"
                textSize = 15f
                setTextColor(getColor(R.color.text_tertiary))
                setPadding(dp(12), dp(6), 0, 0)
            })
        }
        content.addView(ratingCard)

        content.addView(
            fieldCard(
                R.drawable.ic_note,
                getString(R.string.checkin_note),
                entity.note?.takeIf { it.isNotBlank() } ?: "-",
            ),
        )
    }
    /** 一个项目一张卡：图标 + 标签 + 值。 */
    private fun fieldCard(icon: Int, label: String, value: String): LinearLayout {
        val card = newCard()
        card.addView(fieldLabel(icon, label))
        if (value.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = value
                textSize = 15f
                setTextColor(getColor(R.color.fg))
                setPadding(dp(12), dp(6), 0, 0)
            })
        }
        return card
    }

    private fun fieldLabel(icon: Int, label: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@CheckinDetailActivity).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@CheckinDetailActivity, R.color.text_tertiary),
            )
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
        })
        addView(TextView(this@CheckinDetailActivity).apply {
            text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@CheckinDetailActivity, R.color.text_secondary))
            setPadding(dp(6), 0, 0, 0)
        })
    }

    private fun newCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    private fun dateText(entity: CheckinEntity): String = when (entity.datePrecision) {
        DatePrecision.YEAR -> Dates.year(entity.checkinAt)
        DatePrecision.MONTH -> Dates.dotMonth(entity.checkinAt)
        DatePrecision.UNKNOWN -> Dates.year(entity.checkinAt)
        else -> Dates.ymd(entity.checkinAt)
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

    private fun stars(rating: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ID = "checkin_id"
        fun intent(context: Context, checkinId: Long): Intent =
            Intent(context, CheckinDetailActivity::class.java).putExtra(EXTRA_ID, checkinId)
    }
}
