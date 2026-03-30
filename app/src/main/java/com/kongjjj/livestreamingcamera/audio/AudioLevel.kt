package com.kongjjj.livestreamingcamera.audio

import kotlin.math.log10

data class AudioLevel(
    val rms: Float = 0f,
    val peak: Float = 0f,
    val rmsRight: Float = 0f,
    val peakRight: Float = 0f,
    val isStereo: Boolean = false
) {
    val rmsDb: Float get() = if (rms > 0.0001f) 20 * log10(rms) else -100f
    val rmsDbRight: Float get() = if (rmsRight > 0.0001f) 20 * log10(rmsRight) else -100f
    val normalizedLevel: Float get() = (rmsDb.coerceIn(-60f, 0f) + 60f) / 60f
    val normalizedLevelRight: Float get() = (rmsDbRight.coerceIn(-60f, 0f) + 60f) / 60f

    companion object {
        val SILENT = AudioLevel(0f, 0f, 0f, 0f, false)
    }
}