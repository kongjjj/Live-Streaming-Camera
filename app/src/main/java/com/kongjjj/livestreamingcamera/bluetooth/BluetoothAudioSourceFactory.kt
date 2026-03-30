package com.kongjjj.livestreamingcamera.bluetooth

import android.content.Context
import android.media.AudioDeviceInfo
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal

/**
 * 建立 [BluetoothAudioSource] 的工廠
 * @param device 藍牙 SCO 輸入裝置（若為 null，則無法建立）
 */
class BluetoothAudioSourceFactory(private val device: AudioDeviceInfo?) :
    IAudioSourceInternal.Factory {

    override suspend fun create(context: Context): IAudioSourceInternal {
        requireNotNull(device) { "No Bluetooth device provided" }
        return BluetoothAudioSource(context, device)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        return source is BluetoothAudioSource
    }
}