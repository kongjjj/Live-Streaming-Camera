package com.kongjjj.livestreamingcamera.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager // 引入現代的 BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class BluetoothAudioHelper(
    private val context: Context,
    private val onPermissionRequired: () -> Unit
) {
    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    // 使用現代方式獲取 BluetoothAdapter 以避免過時警告
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private val tag = "BluetoothHelper"

    /**
     * 檢查 BLUETOOTH_CONNECT 權限（Android 12+）
     */
    private fun checkBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!granted) onPermissionRequired()
            return granted
        }
        return true
    }

    /**
     * 偵測可用的藍牙 SCO 輸入裝置
     */
    fun detectBluetoothScoDevice(): AudioDeviceInfo? {
        if (!checkBluetoothPermission()) {
            Log.w(tag, "detectBluetoothScoDevice: 權限不足")
            return null
        }

        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: emptyArray()
        val scoDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

        if (scoDevice != null) {
            Log.i(tag, "偵測到藍牙 SCO 裝置: ${scoDevice.productName}")
        } else {
            Log.w(tag, "未偵測到任何藍牙 SCO 裝置")
        }
        return scoDevice
    }

    /**
     * 檢查 HFP 耳機是否已連接
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isHeadsetConnected(): Boolean {
        if (!checkBluetoothPermission()) return false
        // 使用 bluetoothAdapter 替代 getDefaultAdapter()
        val state = bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.HEADSET)
        return state == BluetoothProfile.STATE_CONNECTED
    }

    /**
     * 啟動 SCO 鏈路並等待連線
     */
    suspend fun startScoAndWait(timeoutMs: Long = 4000): Boolean {
        val am = audioManager ?: return false
        Log.d(tag, "startScoAndWait 開始")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val modernResult = startScoModern(am, timeoutMs)
            if (modernResult) {
                Log.d(tag, "startScoAndWait 現代 API 成功")
                return true
            }
            Log.w(tag, "現代 API 失敗，嘗試舊版 API")
        }
        return startScoLegacy(am, timeoutMs)
    }

    /**
     * 停止 SCO 鏈路
     */
    fun stopSco() {
        Log.d(tag, "stopSco 呼叫")
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            stopScoModern(am)
        } else {
            @Suppress("DEPRECATION")
            try { am.stopBluetoothSco() } catch (e: Throwable) { Log.e(tag, "stopBluetoothSco 失敗", e) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun stopScoModern(am: AudioManager) {
        try { am.clearCommunicationDevice() } catch (e: Throwable) { Log.e(tag, "clearCommunicationDevice 失敗", e) }
    }

    /**
     * 檢查 SCO 是否已啟動
     */
    fun isScoActive(): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkScoActiveModern(am)
        } else {
            @Suppress("DEPRECATION")
            try { am.isBluetoothScoOn } catch (_: Throwable) { false }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkScoActiveModern(am: AudioManager): Boolean {
        return am.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    // ========== 私有輔助方法 ==========

    @Suppress("DEPRECATION")
    private suspend fun startScoLegacy(am: AudioManager, timeoutMs: Long): Boolean {
        am.startBluetoothSco()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (am.isBluetoothScoOn) {
                Log.i(tag, "SCO started (legacy)")
                return true
            }
            delay(200)
        }
        Log.w(tag, "SCO start timeout (legacy)")
        return false
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun startScoModern(am: AudioManager, timeoutMs: Long): Boolean {
        val device = detectBluetoothScoDevice() ?: return false
        try {
            // Android 12+ 通常需要切換模式才能讓 setCommunicationDevice 生效
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Throwable) {
            Log.w(tag, "設定 MODE_IN_COMMUNICATION 失敗", e)
        }

        val result = am.setCommunicationDevice(device)
        if (!result) return false

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val commDevice = am.communicationDevice
            if (commDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                Log.i(tag, "SCO started (modern)")
                return true
            }
            delay(200)
        }
        return false
    }
}