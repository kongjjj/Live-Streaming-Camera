package com.kongjjj.livestreamingcamera.bluetooth

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
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

class BluetoothAudioSource(
    private val context: Context,
    private val preferredDevice: AudioDeviceInfo?
) : IAudioSourceInternal,
    IAudioFrameSourceInternal,
    SuspendConfigurable<AudioSourceConfig>,
    SuspendStreamable,
    Releasable {

    private var audioRecord: AudioRecord? = null
    private var bufferSize = 0
    private var currentConfig: AudioSourceConfig? = null
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()
    private val tag = "BluetoothAudioSrc"

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun configure(config: AudioSourceConfig) {
        audioRecord?.let {
            if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Audio source is already recording")
            } else {
                release()
            }
        }

        bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channelConfig,
            config.byteFormat
        ).also { if (it <= 0) throw IllegalArgumentException("Invalid buffer size: $it") }

        currentConfig = config

        audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioFormat = AudioFormat.Builder()
                .setEncoding(config.byteFormat)
                .setSampleRate(config.sampleRate)
                .setChannelMask(config.channelConfig)
                .build()

            val record = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .build()

            preferredDevice?.let { device ->
                val success = record.setPreferredDevice(device)
                Log.i(tag, "setPreferredDevice(${device.productName}) success=$success")
                // 驗證 preferred device 是否真的設定成功
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val actualDevice = record.preferredDevice
                    Log.i(tag, "Actual preferred device after set: ${actualDevice?.productName ?: "null"}")
                }
            }
            record
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                config.sampleRate,
                config.channelConfig,
                config.byteFormat,
                bufferSize
            )
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            release()
            throw IllegalStateException("Failed to initialize AudioRecord")
        }
        Log.i(tag, "configure completed, bufferSize=$bufferSize")
    }

    override suspend fun startStream() {
        val ar = requireNotNull(audioRecord) { "Audio source not configured" }
        ar.startRecording()
        _isStreamingFlow.tryEmit(true)
        Log.i(tag, "startStream called, recording state = ${ar.recordingState}")
    }

    override suspend fun stopStream() {
        val ar = audioRecord ?: return
        ar.stop()
        _isStreamingFlow.tryEmit(false)
        Log.i(tag, "stopStream called")
    }

    override fun release() {
        _isStreamingFlow.tryEmit(false)
        audioRecord?.release()
        audioRecord = null
        currentConfig = null
        Log.i(tag, "released")
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        val ar = requireNotNull(audioRecord) { "AudioRecord not initialized" }
        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("AudioRecord is not recording")
        }

        val buffer = frame.rawBuffer
        val bytesRead = ar.read(buffer, buffer.remaining())
        Log.d(tag, "fillAudioFrame read $bytesRead bytes") // 關鍵日誌
        if (bytesRead > 0) {
            frame.timestampInUs = System.nanoTime() / 1000L
            return frame
        } else {
            frame.close()
            throw IllegalStateException("AudioRecord read error: $bytesRead")
        }
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val cfg = requireNotNull(currentConfig) { "Audio source not configured" }
        return fillAudioFrame(frameFactory.create(bufferSize, 0))
    }
}