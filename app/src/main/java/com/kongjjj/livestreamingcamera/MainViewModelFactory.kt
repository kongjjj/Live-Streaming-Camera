package com.kongjjj.livestreamingcamera

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.kongjjj.livestreamingcamera.data.rotation.RotationRepository  // ✅ 修正這裡
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer

/**
 * 用於從 [android.app.Application] 建構 [MainViewModel] 的工廠
 */
class MainViewModelFactory(private val application: Application) :
    ViewModelProvider.Factory {
    private val rotationRepository = RotationRepository.getInstance(application)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            val streamer = createStreamer(application)
            return MainViewModel(application, rotationRepository, streamer) as T
        }
        throw IllegalArgumentException("未知的 ViewModel 類別")
    }

    companion object {
        /**
         * 建立推流器實例
         */
        private fun createStreamer(application: Application): SingleStreamer {
            return SingleStreamer(application, withAudio = true, withVideo = true)
        }
    }
}