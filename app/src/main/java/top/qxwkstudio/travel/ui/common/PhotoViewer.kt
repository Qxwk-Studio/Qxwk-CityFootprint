package top.qxwkstudio.travel.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.ImageView

/**
 * 点照片看大图：全屏黑底、居中缩放，点一下关闭。
 *
 * 不引第三方大图库：本地照片已经缩到 2560px，`FIT_CENTER` 足够看；手势缩放不在这一版范围里。
 */
internal fun showPhotoDialog(context: Context, path: String?) {
    val bitmap = PhotoStore.load(path, maxPx = 1600) ?: return
    val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val image = ImageView(context).apply {
        setImageBitmap(bitmap)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
        setOnClickListener { dialog.dismiss() }
    }
    dialog.setContentView(
        image,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    dialog.show()
}
