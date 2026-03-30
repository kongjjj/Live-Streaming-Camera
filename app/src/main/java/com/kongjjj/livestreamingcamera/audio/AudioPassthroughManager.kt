package com.kongjjj.livestreamingcamera.audio

import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import android.util.Log

class AudioPassthroughManager {
    var audioRecord: AudioRecord? = null
        private set

    fun start() {
        stop() // 確保之前的實例已釋放

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            audioRecord?.startRecording()
        } catch (e: SecurityException) {
            Log.e("AudioPassthrough", "RECORD_AUDIO permission not granted", e)
            audioRecord = null
        } catch (e: Exception) {
            Log.e("AudioPassthrough", "Failed to start audio record", e)
            audioRecord = null
        }
    }

    fun stop() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e("AudioPassthrough", "Error stopping audio record", e)
        } finally {
            audioRecord?.release()
            audioRecord = null
        }
    }
}