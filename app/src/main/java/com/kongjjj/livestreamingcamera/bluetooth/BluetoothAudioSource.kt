package com.kongjjj.livestreamingcamera.bluetooth

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
    private val preferredDevice: AudioDeviceInfo?,
) : IAudioSourceInternal,
    IAudioFrameSourceInternal,
    SuspendConfigurable<AudioSourceConfig>,
    SuspendStreamable,
    Releasable {

    private var audioRecord: AudioRecord? = null
    private var bufferSize = 0
    private var silentBuffer: ByteArray? = null
    private var currentConfig: AudioSourceConfig? = null
    private val _isStreamingFlow = MutableStateFlow(value = false)
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
            val actualDevice = record.preferredDevice
            Log.i(tag, "Actual preferred device after set: ${actualDevice?.productName ?: "null"}")
        }
        audioRecord = record

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            release()
            throw IllegalStateException("Failed to initialize AudioRecord")
        }
        silentBuffer = ByteArray(bufferSize)
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
        silentBuffer = null
        currentConfig = null
        Log.i(tag, "released")
    }

    private fun fillWithSilence(buffer: java.nio.ByteBuffer) {
        buffer.clear()
        val silence = silentBuffer
        if (silence != null && (silence.size >= buffer.remaining())) {
            buffer.put(silence, 0, buffer.remaining())
        } else {
            // Fallback if silentBuffer is not initialized or too small
            val size = buffer.remaining()
            val fallbackSilence = ByteArray(size)
            buffer.put(fallbackSilence)
        }
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        val ar = audioRecord ?: return frame
        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(tag, "fillAudioFrame: AudioRecord is not recording, state=${ar.recordingState}")
            // Fill with silence instead of throwing
            fillWithSilence(frame.rawBuffer)
            return frame
        }

        val buffer = frame.rawBuffer
        val bytesRead = ar.read(buffer, buffer.remaining())
        if (bytesRead > 0) {
            frame.timestampInUs = System.nanoTime() / 1000L
        } else {
            Log.e(tag, "fillAudioFrame read error: $bytesRead")
            // Fill with silence to avoid noise/crash
            fillWithSilence(buffer)
        }
        return frame
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        requireNotNull(currentConfig) { "Audio source not configured" }
        return fillAudioFrame(frameFactory.create(bufferSize, 0))
    }
}