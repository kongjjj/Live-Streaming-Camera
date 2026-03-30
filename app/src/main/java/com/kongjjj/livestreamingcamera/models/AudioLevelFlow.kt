package com.kongjjj.livestreamingcamera.models

import android.media.AudioRecord
import com.kongjjj.livestreamingcamera.audio.AudioLevel
import com.kongjjj.livestreamingcamera.audio.AudioPassthroughManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.sqrt

class AudioLevelFlow {

    private val audioPassthroughManager = AudioPassthroughManager()
    private val _audioLevelFlow = MutableStateFlow(AudioLevel.SILENT)
    val audioLevelFlow: StateFlow<AudioLevel> get() = _audioLevelFlow

    private val buffer = ByteArray(4096)
    private var isRunning = false
    private var audioRecord: AudioRecord? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    fun start() {
        if (isRunning) return
        isRunning = true
        audioPassthroughManager.start()
        audioRecord = audioPassthroughManager.audioRecord
        audioRecord?.startRecording()

        coroutineScope.launch {
            while (isRunning) {
                val record = audioRecord
                if (record != null) {
                    val level = calculateAudioLevel(record, buffer)
                    _audioLevelFlow.emit(level)
                }
                delay(30)
            }
        }
    }

    fun stop() {
        isRunning = false
        audioRecord?.stop()
        audioPassthroughManager.stop()
    }

    private fun calculateAudioLevel(
        audioRecord: AudioRecord,
        buffer: ByteArray
    ): AudioLevel {
        val bytesRead = audioRecord.read(buffer, 0, buffer.size)
        if (bytesRead <= 0) return AudioLevel.SILENT

        val sampleCount = bytesRead / 2
        var sumSquaresLeft = 0.0
        var peakLeft = 0.0
        var sumSquaresRight = 0.0
        var peakRight = 0.0

        var i = 0
        while (i < bytesRead) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            val normalized = sample / 32768.0

            // 立體聲處理
            if ((i / 2) % 2 == 0) {
                sumSquaresLeft += normalized * normalized
                peakLeft = maxOf(peakLeft, kotlin.math.abs(normalized))
            } else {
                sumSquaresRight += normalized * normalized
                peakRight = maxOf(peakRight, kotlin.math.abs(normalized))
            }

            i += 2
        }

        val rmsLeft = sqrt(sumSquaresLeft / (sampleCount / 2)).toFloat()
        val rmsRight = sqrt(sumSquaresRight / (sampleCount / 2)).toFloat()

        return AudioLevel(
            rms = rmsLeft.coerceIn(0f, 1f),
            peak = peakLeft.toFloat().coerceIn(0f, 1f),
            rmsRight = rmsRight.coerceIn(0f, 1f),
            peakRight = peakRight.toFloat().coerceIn(0f, 1f),
            isStereo = true
        )
    }
}