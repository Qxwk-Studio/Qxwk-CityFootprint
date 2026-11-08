package top.qxwkstudio.travel.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

internal fun Context.copyPlainText(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("UUID", text))
}
