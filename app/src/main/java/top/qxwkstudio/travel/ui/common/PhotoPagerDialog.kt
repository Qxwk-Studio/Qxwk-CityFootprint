package top.qxwkstudio.travel.ui.common

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import top.qxwkstudio.travel.R

/** 多图查看：左右切换、缩放、保存当前图。 */
internal fun showPhotoDialog(context: Context, paths: List<String>, startIndex: Int = 0) {
    val usable = paths.filter { it.isNotBlank() }
    if (usable.isEmpty()) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && context is android.app.Activity) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            androidx.core.app.ActivityCompat.requestPermissions(
                context,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                9001,
            )
        }
    }
    val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val pageLabel = TextView(context).apply {
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(photoDp(context, 12), photoDp(context, 6), photoDp(context, 12), photoDp(context, 6))
        background = GradientDrawable().apply {
            cornerRadius = photoDp(context, 16).toFloat()
            setColor(0xAA1F2937.toInt())
        }
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = photoDp(context, 28) }
    }
    val pager = ZoomablePhotoPager(
        context = context,
        paths = usable,
        startIndex = startIndex,
        onClose = { dialog.dismiss() },
        onPageChanged = { index, count -> pageLabel.text = "${index + 1} / $count" },
    )
    val save = TextView(context).apply {
        text = context.getString(R.string.photo_save)
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(photoDp(context, 22), photoDp(context, 11), photoDp(context, 22), photoDp(context, 11))
        background = GradientDrawable().apply {
            cornerRadius = photoDp(context, 22).toFloat()
            setColor(0xCC1F2937.toInt())
        }
        setOnClickListener {
            val bitmap = pager.currentBitmap()
            val ok = bitmap != null && savePagerBitmap(context, bitmap)
            Toast.makeText(
                context,
                if (ok) R.string.photo_saved else R.string.photo_save_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = photoDp(context, 40) }
    }

    val close = TextView(context).apply {
        text = "×"
        textSize = 26f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(photoDp(context, 8), 0, photoDp(context, 8), photoDp(context, 2))
        setOnClickListener { dialog.dismiss() }
        layoutParams = FrameLayout.LayoutParams(
            photoDp(context, 48),
            photoDp(context, 48),
            Gravity.TOP or Gravity.END,
        ).apply { topMargin = photoDp(context, 18); marginEnd = photoDp(context, 14) }
    }

    val root = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(pager, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(pageLabel)
        addView(save)
        addView(close)
    }

    dialog.setContentView(
        root,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    dialog.setOnShowListener {
        root.alpha = 0f
        root.scaleX = 0.88f
        root.scaleY = 0.88f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
    dialog.show()
}

private fun savePagerBitmap(context: Context, bitmap: Bitmap): Boolean = runCatching {
    val name = "CityFootprint_${System.currentTimeMillis()}.jpg"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CityFootprint")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.insertImage(context.contentResolver, bitmap, name, null) ?: return false
    }
    true
}.getOrDefault(false)

private fun photoDp(context: Context, v: Int): Int =
    (v * context.resources.displayMetrics.density).toInt()
