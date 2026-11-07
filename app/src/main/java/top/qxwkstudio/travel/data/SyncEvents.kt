package top.qxwkstudio.travel.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 同步落库后通知当前页面刷新，不依赖页面重新可见。 */
internal object SyncEvents {
    private val mutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events = mutable.asSharedFlow()
    fun notifyChanged() { mutable.tryEmit(Unit) }
}
