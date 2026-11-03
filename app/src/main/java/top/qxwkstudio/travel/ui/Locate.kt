package top.qxwkstudio.travel.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * 「定位」这层壳（对齐网页 docs/index.js 的 locateCity）。**只管取一次坐标**，
 * 「最近的哪座城」在 logic/CitySearch.nearest（纯函数、有单测），提示文案在调用方。
 *
 * 三点取舍：
 *  1. 用系统 LocationManager，**不引 Google Play 服务的 fused provider** —— 与 README 的
 *     「依赖压到最小」一致，找最近的城市也用不到那点精度；
 *  2. 只申请 / 使用**粗略位置**（ACCESS_COARSE_LOCATION）：网页那侧就是 enableHighAccuracy:false
 *     的低精度请求，同一档；精确位置不在清单里；
 *  3. 取位置走系统 `LocationManager.requestSingleUpdate` + 10 秒超时，**不用**
 *     `androidx.core.location.LocationManagerCompat.getCurrentLocation`：后者在 core 1.13
 *     （被 MapLibre 的传递依赖顶上来）里出现了重载歧义，编译期就过不去；它内部本来也就是这一套。
 *
 * 本对象**不做权限检查**（那是调用方的事）：调用方必须先过 [granted] 再调 [current]。
 */
object Locate {

    /** 只申请粗略位置。见类注释第 2 点。 */
    const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

    /** 单次定位的超时：10 秒拿不到就按失败处理。 */
    private const val LOCATE_TIMEOUT_MS = 10_000L

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * 定位总开关是否开着。用来把「用户压根没开定位」与「开了但一时取不到」分开提示
     * （对应网页 index.js geoErrorMsg 里 POSITION_UNAVAILABLE 那两类）。
     */
    fun enabled(context: Context): Boolean = providerOf(context) != null

    /**
     * 取一次当前位置。[onResult] 回调在主线程；拿不到（没开定位、provider 都不可用、超时）
     * 一律是 null，由调用方按 [enabled] 决定提示哪一句。
     *
     * 只取一次、不订阅：编辑页进页面时定一次、地图页点按钮时定一次，没有持续跟随的需求。
     */
    @Suppress("MissingPermission") // 权限由调用方先过 granted()，见类注释
    fun current(context: Context, onResult: (Location?) -> Unit) {
        val provider = providerOf(context) ?: return onResult(null)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val main = android.os.Looper.getMainLooper()
        // 只回一次：成功、被禁用、超时三条路都收敛到 deliver（否则页面会收到两次结果）
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)

        lateinit var listener: LocationListener
        fun deliver(location: Location?) {
            if (!delivered.compareAndSet(false, true)) return
            runCatching { lm.removeUpdates(listener) }
            onResult(location)
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = deliver(location)

            @Deprecated("API 29 起废弃，但 minSdk 24 仍会回调")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = deliver(null)
        }

        // 手动走 requestSingleUpdate 而不是 androidx 的 LocationManagerCompat.getCurrentLocation：
        // 后者在 core 1.13（被 MapLibre 的传递依赖顶上来）里出现了重载歧义，编译期就过不去；
        // 而且它内部也就是这一套「先给上次位置、再等一次回调、带超时」。
        lm.getLastKnownLocation(provider)?.let { deliver(it) }
        if (delivered.get()) return
        lm.requestSingleUpdate(provider, listener, main)
        // 超时兜底：10 秒还没结果就当拿不到，别让按钮一直没反应
        android.os.Handler(main).postDelayed({ deliver(null) }, LOCATE_TIMEOUT_MS)
    }

    /** 优先网络定位：粗略档用不上 GPS，室内也能出结果；两个都不开就是没开定位。 */
    private fun providerOf(context: Context): String? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
    }
}