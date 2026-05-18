package com.kongjjj.livestreamingcamera

import android.media.AudioRecord
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioFrameSourceInternal
import io.github.thibaultbee.streampack.core.elements.interfaces.SuspendConfigurable
import io.github.thibaultbee.streampack.core.elements.interfaces.SuspendStreamable
import io.github.thibaultbee.streampack.core.elements.interfaces.Releasable
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 包裝一個 IAudioSourceInternal，允許動態靜音（輸出靜音數據）而不中斷音頻流。
 * @param delegate 實際的音頻源（麥克風或藍牙）
 * @param isMutedProvider 提供當前是否靜音的 lambda（由 ViewModel 管理）
 */
class MuteableAudioSource(
    private val delegate: IAudioSourceInternal,
    private val isMutedProvider: () -> Boolean
) : IAudioSourceInternal,
    IAudioFrameSourceInternal,
    SuspendConfigurable<AudioSourceConfig>,
    SuspendStreamable,
    Releasable {

    private var currentConfig: AudioSourceConfig? = null
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    override suspend fun configure(config: AudioSourceConfig) {
        currentConfig = config
        delegate.configure(config)
    }

    override suspend fun startStream() {
        delegate.startStream()
        _isStreamingFlow.value = true
    }

    override suspend fun stopStream() {
        delegate.stopStream()
        _isStreamingFlow.value = false
    }

    override fun release() {
        delegate.release()
        _isStreamingFlow.value = false
    }

    private var silentArray: ByteArray? = null

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        // 始終從 delegate 讀取，確保 AudioRecord 持續運作且時間戳正確
        val result = (delegate as IAudioFrameSourceInternal).fillAudioFrame(frame)
        
        if (isMutedProvider()) {
            val buffer = result.rawBuffer
            val currentPos = buffer.position()
            val limit = buffer.limit()
            
            // 決定要清零的範圍。
            // 如果 position > 0，表示剛寫入數據且尚未 flip。
            // 如果 position == 0 且 limit > 0，表示數據已經 flip 過了。
            val end = if (currentPos > 0) currentPos else limit
            
            if (end > 0) {
                if (silentArray == null || silentArray!!.size < end) {
                    silentArray = ByteArray(end)
                }
                
                val oldPos = buffer.position()
                val oldLimit = buffer.limit()
                
                buffer.position(0)
                buffer.put(silentArray!!, 0, end)
                
                // 還原原始狀態，避免破壞後續 Pipeline 的讀取邏輯
                buffer.position(oldPos)
                buffer.limit(oldLimit)
            }
        }
        return result
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val cfg = requireNotNull(currentConfig) { "Audio source not configured" }
        val bufferSize = AudioRecord.getMinBufferSize(cfg.sampleRate, cfg.channelConfig, cfg.byteFormat)
        return fillAudioFrame(frameFactory.create(bufferSize, 0))
    }
}