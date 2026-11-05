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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import top.qxwkstudio.travel.R

/**
 * 点照片看大图：全屏黑底、居中缩放，点图片关闭；底部居中有「保存到相册」。
 *
 * 不引第三方大图库：本地照片本身已经缩到 2560px，FIT_CENTER 足够看。
 * 打开/关闭带缩放 + 淡入淡出动画（「放大 / 缩小」），关闭动画播完再 dismiss。
 */
internal fun showPhotoDialog(context: Context, path: String?) {
    val bitmap = PhotoStore.load(path, maxPx = 1600) ?: return
    // Android 9 及以下保存到相册要写权限：打开大图时就申请，避免点保存才失败
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

    val image = ImageView(context).apply {
        setImageBitmap(bitmap)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
        setOnClickListener {
            animateOut(this) { dialog.dismiss() }
        }
    }

    val save = TextView(context).apply {
        text = context.getString(R.string.photo_save)
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(context, 22), dp(context, 11), dp(context, 22), dp(context, 11))
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 22).toFloat()
            setColor(0xCC1F2937.toInt())
        }
        setOnClickListener {
            val ok = saveToGallery(context, bitmap)
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
        ).apply { bottomMargin = dp(context, 40) }
    }

    val root = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(image, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(save)
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

private fun animateOut(view: android.view.View, end: () -> Unit) {
    view.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
        .setDuration(140)
        .withEndAction(end)
        .start()
}

/** 保存到系统相册：API 29+ 走 MediaStore 分区存储，低版本走 insertImage（需要写权限）。 */
private fun saveToGallery(context: Context, bitmap: Bitmap): Boolean = runCatching {
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

private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
