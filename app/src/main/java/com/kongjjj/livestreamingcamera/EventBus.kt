package com.kongjjj.livestreamingcamera

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 取代已過時的 LocalBroadcastManager，用於 App 內部的事件通知
 */
object EventBus {
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun post(event: AppEvent) {
        _events.tryEmit(event)
    }
}

sealed class AppEvent {
    // 串流狀態變更 (ViewModel -> Service)
    data class StreamStateChanged(val isStreaming: Boolean) : AppEvent()
    
    // 服務指令 (Service -> MainActivity)
    object StartStream : AppEvent()
    object StopStream : AppEvent()
    object ExitApp : AppEvent()
}
