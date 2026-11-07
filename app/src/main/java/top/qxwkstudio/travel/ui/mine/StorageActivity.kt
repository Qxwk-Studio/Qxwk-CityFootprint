package top.qxwkstudio.travel.ui.mine

import top.qxwkstudio.travel.ui.common.*

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.PhotoJanitor
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.databinding.ActivityStorageBinding
import top.qxwkstudio.travel.ui.common.Palette
import java.io.File
import java.util.Locale

/** 存储空间：分项统计 + 清理无引用照片 / 缓存。 */
class StorageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        binding = ActivityStorageBinding.inflate(layoutInflater)
        applyEdgeToEdge(binding.topBar.root, binding.scroll)
        setContentView(binding.root)

        binding.topBar.title.setText(R.string.mine_storage)
        binding.topBar.btnBack.visibility = View.VISIBLE
        binding.topBar.btnBack.setOnClickListener { finish() }

        binding.btnCleanupPhotos.setOnClickListener { confirmCleanupPhotos() }
        binding.btnCleanupCache.setOnClickListener { confirmCleanupCache() }
        refreshSizes()
    }

    private fun refreshSizes() {
        lifecycleScope.runIo({
            val app = runCatching {
                File(packageManager.getPackageInfo(packageName, 0).applicationInfo.sourceDir).length()
            }.getOrDefault(0L)
            val basemap = dirSize(File(filesDir, "basemap"))
            val database = dirSize(File(dataDir, "databases"))
            val photos = dirSize(File(filesDir, "photos"))
            val cache = dirSize(cacheDir)
            longArrayOf(app, basemap, database, photos, cache)
        }) { result ->
            val sizes = result.getOrNull() ?: return@runIo
            val colors = intArrayOf(
                Palette.accent(this),
                0xFF2563EB.toInt(),
                0xFF16A34A.toInt(),
                0xFFD97706.toInt(),
                0xFF7C3AED.toInt(),
            )
            binding.textApp.text = format(sizes[0])
            binding.textBasemap.text = format(sizes[1])
            binding.textDatabase.text = format(sizes[2])
            binding.textPhotos.text = format(sizes[3])
            binding.textCache.text = format(sizes[4])
            binding.usageRing.setData(sizes, colors, format(sizes.sum()))
            val dots = intArrayOf(R.id.dotApp, R.id.dotBasemap, R.id.dotDatabase, R.id.dotPhotos, R.id.dotCache)
            dots.forEachIndexed { i, id ->
                findViewById<View>(id).background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(colors[i])
                }
            }
        }
    }

    private fun confirmCleanupPhotos() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cleanup_confirm_title)
            .setMessage(R.string.cleanup_confirm_message)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                lifecycleScope.runIo({
                    PhotoJanitor.sweep(this, AppDatabase.get(this))
                }) { result ->
                    val (removed, bytes) = result.getOrNull() ?: (0 to 0L)
                    if (removed == 0) {
                        notify(R.string.cleanup_none)
                    } else {
                        notify(getString(R.string.cleanup_done, removed, format(bytes)), long = true)
                    }
                    refreshSizes()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmCleanupCache() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.storage_cleanup_cache)
            .setMessage(R.string.storage_cache_confirm)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
                notify(R.string.storage_cache_cleared)
                refreshSizes()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun format(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        fun intent(context: android.content.Context): Intent =
            Intent(context, StorageActivity::class.java)
    }
}
