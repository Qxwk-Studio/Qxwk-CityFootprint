package top.qxwkstudio.travel.ui.common

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.DataExport

/**
 * 导出前的格式选择：.zip 可直接解压查看；.bak 是加密备份，任何客户端都能导入。
 * 不再询问密码——密钥随客户端走，导出即可导入。
 */
fun Activity.pickExportFormat(onPick: (DataExport.Format) -> Unit) {
    val labels = arrayOf(
        getString(R.string.export_format_zip),
        getString(R.string.export_format_bak),
    )
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.export_format_title)
        .setItems(labels) { _, which ->
            onPick(if (which == 0) DataExport.Format.ZIP else DataExport.Format.BAK)
        }
        .setNegativeButton(R.string.common_cancel, null)
        .show()
}
