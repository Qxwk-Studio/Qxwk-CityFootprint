package top.qxwkstudio.travel.ui.mine

import top.qxwkstudio.travel.ui.common.*

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import top.qxwkstudio.travel.ui.common.LocalizedActivity
import androidx.core.content.ContextCompat
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Prefs
import top.qxwkstudio.travel.databinding.ActivityPermissionsBinding
import top.qxwkstudio.travel.ui.common.Palette

/** 权限管理：只列这个 App 真正会用到/会申请的权限。 */
class PermissionsActivity : LocalizedActivity() {

    private lateinit var binding: ActivityPermissionsBinding

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        binding = ActivityPermissionsBinding.inflate(layoutInflater)
        applyEdgeToEdge(binding.topBar.root, binding.scroll)
        setContentView(binding.root)

        binding.topBar.title.setText(R.string.mine_permissions)
        binding.topBar.btnBack.visibility = View.VISIBLE
        binding.topBar.btnBack.setOnClickListener { finish() }

        binding.rowLocation.setOnClickListener { binding.switchLocation.toggle() }
        binding.switchLocation.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            } else if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                // 权限不能在应用内主动撤销，只能引导去系统设置
                binding.switchLocation.isChecked = true
                openAppSettings()
            }
        }
        binding.rowStoragePerm.setOnClickListener {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                notify(R.string.perm_storage_not_needed)
            } else if (granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                notify(R.string.perm_granted_toast)
            } else {
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        binding.switchNetwork.isChecked = Prefs.networkAllowed
        binding.switchNetwork.setOnCheckedChangeListener { _, checked ->
            Prefs.saveNetworkAllowed(this, checked)
        }
        binding.rowNetwork.setOnClickListener { binding.switchNetwork.toggle() }

        binding.switchNotification.setOnCheckedChangeListener { _, checked ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                binding.switchNotification.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (checked) {
                if (!granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else if (granted(Manifest.permission.POST_NOTIFICATIONS)) {
                binding.switchNotification.isChecked = true
                openAppSettings()
            }
        }
        binding.rowNotification.setOnClickListener { binding.switchNotification.toggle() }
        binding.btnSystemSettings.setOnClickListener { openAppSettings() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val locationGranted = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        binding.switchLocation.setOnCheckedChangeListener(null)
        binding.switchLocation.isChecked = locationGranted
        binding.switchLocation.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            } else if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                binding.switchLocation.isChecked = true
                openAppSettings()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationGranted = granted(Manifest.permission.POST_NOTIFICATIONS)
            binding.switchNotification.setOnCheckedChangeListener(null)
            binding.switchNotification.isChecked = notificationGranted
            binding.switchNotification.isEnabled = true
            binding.switchNotification.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (!granted(Manifest.permission.POST_NOTIFICATIONS)) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else if (granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    binding.switchNotification.isChecked = true
                    openAppSettings()
                }
            }
        } else {
            binding.switchNotification.setOnCheckedChangeListener(null)
            binding.switchNotification.isChecked = false
            binding.switchNotification.isEnabled = false
            binding.textNotificationDesc.setText(R.string.perm_notification_not_needed)
        }

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            binding.textStorageDesc.setText(R.string.perm_storage_scoped_desc)
            binding.textStorageStatus.setText(R.string.perm_not_required)
        } else {
            binding.textStorageDesc.setText(R.string.perm_storage_desc)
            binding.textStorageStatus.setText(
                if (granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) R.string.perm_granted else R.string.perm_denied,
            )
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    companion object {
        fun intent(context: android.content.Context): Intent =
            Intent(context, PermissionsActivity::class.java)
    }
}
