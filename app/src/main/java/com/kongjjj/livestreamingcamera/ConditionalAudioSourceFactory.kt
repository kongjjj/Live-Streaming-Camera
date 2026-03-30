package com.kongjjj.livestreamingcamera

import android.content.Context
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory

/**
 * 建立一般麥克風音訊來源的工廠（不包含任何音效處理）
 */
class ConditionalAudioSourceFactory : IAudioSourceInternal.Factory {

    companion object {
        private const val TAG = "ConditionalAudioSrcFact"
    }

    override suspend fun create(context: Context): IAudioSourceInternal {
        Log.i(TAG, "Creating microphone source (no effects)")
        // 使用無參數的 MicrophoneSourceFactory，預設使用 CAMCORDER 且無效果
        return MicrophoneSourceFactory().create(context)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // 強制每次重新建立，確保與舊的 AudioRecord 隔離
        return false
    }
}