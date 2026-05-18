// MuteableAudioSourceFactory.kt
package com.kongjjj.livestreamingcamera

import android.content.Context
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal

class MuteableAudioSourceFactory(
    private val delegateFactory: IAudioSourceInternal.Factory,
    private val isMutedProvider: () -> Boolean
) : IAudioSourceInternal.Factory {
    override suspend fun create(context: Context): IAudioSourceInternal {
        val delegate = delegateFactory.create(context)
        return MuteableAudioSource(delegate, isMutedProvider)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // 簡單返回 false，強制每次重新建立，避免快取問題
        return false
    }
}