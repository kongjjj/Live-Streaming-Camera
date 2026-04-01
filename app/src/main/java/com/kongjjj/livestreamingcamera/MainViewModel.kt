package com.kongjjj.livestreamingcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.media.AudioFormat
import android.media.MediaFormat
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.kongjjj.livestreamingcamera.bitrate.AdaptiveSrtBitrateRegulatorController
import com.kongjjj.livestreamingcamera.bitrate.MoblinSrtFightConfig
import com.kongjjj.livestreamingcamera.bitrate.RegulatorMode
import com.kongjjj.livestreamingcamera.bluetooth.BluetoothAudioHelper
import com.kongjjj.livestreamingcamera.bluetooth.BluetoothAudioSourceFactory
import com.kongjjj.livestreamingcamera.data.rotation.RotationRepository
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.core.streamers.single.IAudioSingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer
import io.github.thibaultbee.streampack.core.utils.extensions.isClosedException
import io.github.thibaultbee.srtdroid.core.models.Stats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.localbroadcastmanager.content.LocalBroadcastManager


data class StreamStats(
    val bitrateKbps: Int = 0,
    val rttMs: Int? = null,
    val lossPercent: Float? = null,
    val sendRateMbps: Float? = null,
    val endpointType: String = "rtmp"
)

@SuppressLint("MissingPermission")
class MainViewModel(
    application: Application,
    private val rotationRepository: RotationRepository,
    val streamer: SingleStreamer
) : AndroidViewModel(application) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)
    private val defaultDispatcher = Dispatchers.Default

    private val endpointTypeKey by lazy { getApplication<Application>().getString(R.string.endpoint_type_key) }
    private val rtmpUrlKey by lazy { getApplication<Application>().getString(R.string.rtmp_server_url_key) }

    @Suppress("unused") private val audioEncoderKey = "audio_encoder_key"
    @Suppress("unused") private val audioSampleRateKey = "audio_sample_rate_key"
    @Suppress("unused") private val audioBitrateKey = "audio_bitrate_key"
    private val videoResolutionKey by lazy { getApplication<Application>().getString(R.string.video_resolution_key) }
    private val videoFpsKey by lazy { getApplication<Application>().getString(R.string.video_fps_key) }
    private val videoBitrateKey by lazy { getApplication<Application>().getString(R.string.video_bitrate_key) }
    private val srtLatencyKey = "srt_latency"
    private val srtRegulatorEnabledKey = "srt_regulator_enabled"
    private val srtVideoTargetBitrateKey = "srt_video_target_bitrate"
    private val srtVideoMinBitrateKey = "srt_video_min_bitrate"
    private val srtRegulatorModeKey = "srt_regulator_mode"
    private val isStreaming: Boolean
        get() = isStreamingLiveData.value == true
    private var currentCameraId: String? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wasStreamingBeforeNetworkLost = false

    private var isStreamRetrying = false
    private var streamRetryJob: Job? = null
    private var userStoppedManually = false
    private val _reconnectingMessage = MutableLiveData<String?>()
    val reconnectingMessage: LiveData<String?> = _reconnectingMessage
    private var isConfigApplied = false

    private val bluetoothHelper by lazy { BluetoothAudioHelper(getApplication()) { _bluetoothPermissionRequest.postValue(Unit) } }
    private val _bluetoothEnabled = MutableLiveData(false)
    val bluetoothEnabled: LiveData<Boolean> = _bluetoothEnabled
    private val _bluetoothPermissionRequest = MutableLiveData<Unit>()
    val bluetoothPermissionRequest: LiveData<Unit> = _bluetoothPermissionRequest
    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    private val _streamStats = MutableLiveData<StreamStats?>()
    val streamStats: LiveData<StreamStats?> = _streamStats
    private var statsJob: Job? = null

    enum class FlashMode { ON, OFF }
    enum class WhiteBalanceMode { AUTO, INCANDESCENT, FLUORESCENT, DAYLIGHT, CLOUDY }
    enum class FocusMode { AUTO, CONTINUOUS, MACRO }

    private val _flashMode = MutableLiveData(FlashMode.OFF)
    val flashMode: LiveData<FlashMode> = _flashMode
    private val _whiteBalanceMode = MutableLiveData(WhiteBalanceMode.AUTO)
    val whiteBalanceMode: LiveData<WhiteBalanceMode> = _whiteBalanceMode
    private val _exposureCompensation = MutableLiveData(0)
    val exposureCompensation: LiveData<Int> = _exposureCompensation
    private val _focusMode = MutableLiveData(FocusMode.CONTINUOUS)
    val focusMode: LiveData<FocusMode> = _focusMode
    private val _isMuted = MutableLiveData(false)
    val isMuted: LiveData<Boolean> = _isMuted

    @Suppress("unused") private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val cameraSource: ICameraSource?
        get() = streamer.videoInput?.sourceFlow?.value as? ICameraSource

    init {
        logAllPreferences()
        viewModelScope.launch(defaultDispatcher) {
            rotationRepository.rotationFlow.collect { rotation -> streamer.setTargetRotation(rotation) }
        }
        if (!prefs.contains("video_encoder_key")) {
            prefs.edit().putString("video_encoder_key", "h264").apply()
        }
        setupNetworkMonitoring()
        viewModelScope.launch {
            streamer.throwableFlow.filterNotNull().filter { it.isClosedException }.collect { _ ->
                if (!userStoppedManually) startStreamRetry()
            }
        }
    }

    fun enableBluetooth(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) { switchToBuiltInMic(); _bluetoothEnabled.postValue(false); return@launch }
            val device = bluetoothHelper.detectBluetoothScoDevice() ?: run { _bluetoothEnabled.postValue(false); _toastMessage.postValue("未偵測到藍牙耳機"); return@launch }
            if (withContext(Dispatchers.IO) { bluetoothHelper.startScoAndWait(4000) }) {
                try {
                    streamer.setAudioSource(BluetoothAudioSourceFactory(device))
                    _bluetoothEnabled.postValue(true)
                    _toastMessage.postValue("藍牙麥克風已啟用")
                } catch (_: Exception) {
                    bluetoothHelper.stopSco(); _bluetoothEnabled.postValue(false); _toastMessage.postValue("藍牙啟用失敗")
                }
            }
        }
    }
    private fun updateStreamingState(isStreaming: Boolean) {
        // 儲存狀態到 SharedPreferences
        prefs.edit().putBoolean("is_streaming", isStreaming).apply()
        // 發送廣播通知服務
        val intent = Intent("com.kongjjj.livestreamingcamera.STREAM_STATE_CHANGED")
        intent.putExtra("is_streaming", isStreaming)
        LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(intent)
    }
    private suspend fun switchToBuiltInMic() {
        bluetoothHelper.stopSco()
        streamer.setAudioSource(ConditionalAudioSourceFactory())
        _toastMessage.postValue("藍牙麥克風已關閉")
    }

    fun updateBluetoothIcon(enabled: Boolean) { _bluetoothEnabled.postValue(enabled) }

    private fun startStatsCollection() {
        stopStatsCollection()
        statsJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val endpointType = prefs.getString(endpointTypeKey, "rtmp") ?: "rtmp"
                    val bitrate = (streamer.videoEncoder?.bitrate ?: 0) / 1000

                    val stats = if (endpointType == "srt") {
                        // endpoint 是非空型別，直接使用，不需要 ?. 安全呼叫
                        val metrics = streamer.endpoint.metrics
                        if (metrics is Stats) {
                            val loss = if (metrics.pktSent > 0) {
                                (metrics.pktSndLoss.toFloat() / metrics.pktSent) * 100
                            } else 0f
                            val rttMs = metrics.msRTT.toInt()
                            val sendRate = metrics.mbpsSendRate.toFloat()
                            StreamStats(
                                bitrateKbps = bitrate,
                                rttMs = rttMs,
                                lossPercent = loss,
                                sendRateMbps = sendRate,
                                endpointType = "srt"
                            )
                        } else {
                            StreamStats(bitrateKbps = bitrate, endpointType = "srt")
                        }
                    } else {
                        StreamStats(bitrateKbps = bitrate, endpointType = endpointType)
                    }

                    _streamStats.postValue(stats)
                } catch (e: Exception) {
                    Log.e(TAG, "統計收集錯誤", e)
                }
                delay(1000)
            }
        }
    }

    private fun stopStatsCollection() { statsJob?.cancel(); statsJob = null; _streamStats.postValue(null) }

    suspend fun setZoomRatio(ratio: Float) { cameraSource?.settings?.zoom?.setZoomRatio(ratio) }

    @Suppress("unused")
    fun getVideoEncoderInputSurface(): Surface? {
        return try {
            val encoder = streamer.videoEncoder
            val getSurfaceMethod = encoder?.javaClass?.methods?.find { it.name == "getInputSurface" || it.name == "inputSurface" }
            getSurfaceMethod?.invoke(encoder) as? Surface
        } catch (e: Exception) {
            Log.e(TAG, "獲取編碼器 Surface 失敗", e)
            null
        }
    }

    private fun logAllPreferences() {
        val allPrefs = prefs.all
        Log.d(TAG, "===== 目前偏好設定 =====")
        allPrefs.forEach { (key, value) -> Log.d(TAG, "$key = $value") }
    }

    private fun setupNetworkMonitoring() {
        val context = getApplication<Application>()
        connectivityManager = context.getSystemService(ConnectivityManager::class.java)

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                wasStreamingBeforeNetworkLost = isStreamingLiveData.value == true
                Log.d(TAG, "網路中斷")
            }

            override fun onAvailable(network: Network) {
                Log.d(TAG, "網路可用: ${if (connectivityManager?.activeNetwork?.let { connectivityManager?.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } == true) "WiFi" else "行動數據"}")
                if (wasStreamingBeforeNetworkLost) {
                    startStreamRetry()
                }
                wasStreamingBeforeNetworkLost = false
            }
        }

        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "註冊網路回呼失敗", e)
        }
    }

    private fun startStreamRetry() {
        if (isStreamRetrying) return
        isStreamRetrying = true
        streamRetryJob = viewModelScope.launch {
            _isTryingConnectionLiveData.postValue(true)
            while (isStreamRetrying && !userStoppedManually) {
                _reconnectingMessage.postValue("網路切換，重新連線中...")
                try {
                    if (streamer.isStreamingFlow.value) streamer.stopStream()
                    streamer.close()
                    delay(500)
                    applyCurrentConfig()
                    val success = startStreamInternal(shouldSuppressErrors = true)
                    if (success) {
                        _reconnectingMessage.postValue(null)
                        break
                    } else {
                        _reconnectingMessage.postValue("連線失敗，5秒後重試...")
                        delay(5000)
                    }
                } catch (_: Exception) {
                    delay(5000)
                }
            }
            _isTryingConnectionLiveData.postValue(false)
            isStreamRetrying = false
        }
    }

    private suspend fun startStreamInternal(shouldSuppressErrors: Boolean = false): Boolean {
        if (!isConfigApplied) {
            applyCurrentConfig()
            isConfigApplied = true
        }
        val endpointType = prefs.getString(endpointTypeKey, "rtmp") ?: "rtmp"
        val url = if (endpointType == "srt") {
            // 讀取選擇的 SRT URL 索引
            val selectedSrtUrl = prefs.getString("selected_srt_url", "1") ?: "1"
            val srtKey = if (selectedSrtUrl == "1") "srt_full_url_key" else "srt_full_url_2_key"
            val baseUrl = prefs.getString(srtKey, "") ?: ""
            val latency = (prefs.getString(srtLatencyKey, "1000") ?: "1000").toIntOrNull() ?: 1000
            if (latency > 0) "$baseUrl${if (baseUrl.contains("?")) "&" else "?"}latency=$latency" else baseUrl
        } else {
            // 讀取選擇的 RTMP URL 索引
            val selectedRtmpUrl = prefs.getString("selected_rtmp_url", "1") ?: "1"
            val rtmpKey = if (selectedRtmpUrl == "1") rtmpUrlKey else "rtmp_server_url_2_key"
            prefs.getString(rtmpKey, "") ?: ""
        }

        streamer.removeBitrateRegulatorController()
        if (endpointType == "srt") createSrtRegulatorFactory()?.let { streamer.addBitrateRegulatorController(it) }

        return try {
            streamer.startStream(url)

            // 等待編碼器初始化（使用介面）
            val videoStreamer = streamer as? IVideoSingleStreamer
            val audioStreamer = streamer as? IAudioSingleStreamer

            val encoderReady = withTimeoutOrNull(5000L) {
                while (videoStreamer?.videoEncoder == null || audioStreamer?.audioEncoder == null) {
                    delay(100)
                }
                true
            } ?: false

            if (!encoderReady) {
                Log.e(TAG, "編碼器未能在 5 秒內初始化")
                if (!shouldSuppressErrors) {
                    _throwableLiveData.postValue(Exception("編碼器初始化超時"))
                }
                return false
            }

            val regulatorEnabled = prefs.getBoolean(srtRegulatorEnabledKey, false)
            if (endpointType != "srt" || !regulatorEnabled) {
                delay(200)
                videoStreamer?.videoEncoder?.bitrate = prefs.getInt(videoBitrateKey, 2000) * 1000
                Log.d(TAG, "設置固定位元率: ${prefs.getInt(videoBitrateKey, 2000)}kbps")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "推流啟動失敗", e)
            if (!shouldSuppressErrors) {
                _throwableLiveData.postValue(e)
            }
            false
        }
    }

    private fun createSrtRegulatorFactory(): AdaptiveSrtBitrateRegulatorController.Factory? {
        if (!prefs.getBoolean(srtRegulatorEnabledKey, false)) return null
        val targetBps = prefs.getInt(srtVideoTargetBitrateKey, 5000) * 1000
        val minBps = prefs.getInt(srtVideoMinBitrateKey, 500) * 1000
        return AdaptiveSrtBitrateRegulatorController.Factory(
            bitrateRegulatorConfig = BitrateRegulatorConfig(videoBitrateRange = Range(minBps, targetBps)),
            mode = try { RegulatorMode.valueOf(prefs.getString(srtRegulatorModeKey, "MOBLIN_FAST")!!) } catch (_: Exception) { RegulatorMode.MOBLIN_FAST },
            moblinConfig = MoblinSrtFightConfig(),
            delayTimeInMs = 200
        )
    }

    suspend fun startStream() {
        if (isStreamingLiveData.value == true || _isTryingConnectionLiveData.value == true) return
        userStoppedManually = false
        _isTryingConnectionLiveData.postValue(true)
        try {
            val success = startStreamInternal(shouldSuppressErrors = false)
            if (success) {
                _isTryingConnectionLiveData.postValue(false)
                startStatsCollection()
            } else {
                startStreamRetry()
            }
        } catch (_: Exception) {
            startStreamRetry()
        }
    }

    suspend fun stopStream() {
        userStoppedManually = true
        isStreamRetrying = false
        streamRetryJob?.cancel()
        stopStatsCollection()
        try {
            if (streamer.isStreamingFlow.value) streamer.stopStream()
            streamer.close()
        } catch (_: Exception) { }
        _isTryingConnectionLiveData.postValue(false)
        isConfigApplied = false
    }

    val isStreamingLiveData: LiveData<Boolean> = streamer.isStreamingFlow.asLiveData()
    private val _isTryingConnectionLiveData = MutableLiveData<Boolean>()
    val isTryingConnectionLiveData: LiveData<Boolean> = _isTryingConnectionLiveData

    // 一般錯誤（非關閉性）
    private val _throwableLiveData = MutableLiveData<Throwable>()
    val throwableLiveData: LiveData<Throwable> = _throwableLiveData

    // 關閉性錯誤（會觸發重連）
    val closedThrowableLiveData: LiveData<Throwable> = streamer.throwableFlow.filterNotNull().filter { it.isClosedException }.asLiveData()

    private suspend fun applyCurrentConfig() {
        if (isStreaming) {
            Log.w(TAG, "Cannot apply config while streaming")
            return
        }
        setAudioConfig()
        setVideoConfig()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun setAudioConfig() {
        if (isStreaming) {
            Log.w(TAG, "Cannot change audio config while streaming")
            return
        }
        val audioConfig = AudioConfig(
            mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate = (prefs.getString("audio_sample_rate_key", "44100") ?: "44100").toInt(),
            channelConfig = AudioFormat.CHANNEL_IN_STEREO
        )
        streamer.setAudioConfig(audioConfig)
    }

    suspend fun setVideoConfig() {
        if (isStreaming) {
            Log.w(TAG, "Cannot change video config while streaming")
            return
        }
        val res = prefs.getString(videoResolutionKey, "1280x720") ?: "1280x720"
        val parts = res.split("x")
        val width = parts[0].toIntOrNull() ?: 1280
        val height = parts[1].toIntOrNull() ?: 720

        // 讀取用戶選擇的編碼器
        val encoderType = prefs.getString("video_encoder_key", "h264") ?: "h264"
        val mimeType = when (encoderType) {
            "hevc" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> MediaFormat.MIMETYPE_VIDEO_AVC
        }

        val videoConfig = VideoConfig(
            mimeType = mimeType,
            resolution = Size(width, height),
            fps = (prefs.getString(videoFpsKey, "25") ?: "25").toInt()
        )
        Log.d(TAG, "設定影片編碼: $mimeType, 解析度: ${width}x${height}")
        streamer.setVideoConfig(videoConfig)
    }

    suspend fun setAudioSource() { streamer.setAudioSource(ConditionalAudioSourceFactory()) }
    suspend fun setCameraId(cameraId: String) { streamer.setCameraId(cameraId); currentCameraId = cameraId }

    suspend fun switchCamera() {
        val cameraManager = getApplication<Application>().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = cameraManager.cameraIdList
        if (ids.size < 2) return
        val nextId = if (currentCameraId == null) ids.first() else ids[(ids.indexOf(currentCameraId) + 1) % ids.size]
        setCameraId(nextId)
    }

    fun toggleFlash(): FlashMode {
        val newMode = if (_flashMode.value == FlashMode.OFF) FlashMode.ON else FlashMode.OFF
        _flashMode.value = newMode
        viewModelScope.launch { try { cameraSource?.settings?.flash?.setIsEnable(newMode == FlashMode.ON) } catch (_: Exception) { } }
        return newMode
    }

    fun cycleWhiteBalance(): WhiteBalanceMode {
        val nextMode = when (_whiteBalanceMode.value) {
            WhiteBalanceMode.AUTO -> WhiteBalanceMode.INCANDESCENT
            WhiteBalanceMode.INCANDESCENT -> WhiteBalanceMode.FLUORESCENT
            WhiteBalanceMode.FLUORESCENT -> WhiteBalanceMode.DAYLIGHT
            WhiteBalanceMode.DAYLIGHT -> WhiteBalanceMode.CLOUDY
            else -> WhiteBalanceMode.AUTO
        }
        _whiteBalanceMode.value = nextMode
        viewModelScope.launch { try { cameraSource?.settings?.whiteBalance?.setAutoMode(when (nextMode) {
            WhiteBalanceMode.AUTO -> CaptureResult.CONTROL_AWB_MODE_AUTO
            WhiteBalanceMode.INCANDESCENT -> CaptureResult.CONTROL_AWB_MODE_INCANDESCENT
            WhiteBalanceMode.FLUORESCENT -> CaptureResult.CONTROL_AWB_MODE_FLUORESCENT
            WhiteBalanceMode.DAYLIGHT -> CaptureResult.CONTROL_AWB_MODE_DAYLIGHT
            WhiteBalanceMode.CLOUDY -> CaptureResult.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        }) } catch (_: Exception) { } }
        return nextMode
    }

    fun adjustExposure(increase: Boolean): Int {
        val values = listOf(-2, -1, 0, 1, 2)
        val nextValue = values[(values.indexOf(_exposureCompensation.value ?: 0) + (if (increase) 1 else -1) + values.size) % values.size]
        _exposureCompensation.value = nextValue
        viewModelScope.launch { try { cameraSource?.settings?.exposure?.setCompensation(nextValue) } catch (_: Exception) { } }
        return nextValue
    }

    fun toggleFocusMode(): FocusMode {
        val nextMode = when (_focusMode.value) {
            FocusMode.CONTINUOUS -> FocusMode.AUTO
            FocusMode.AUTO -> FocusMode.MACRO
            else -> FocusMode.CONTINUOUS
        }
        _focusMode.value = nextMode
        viewModelScope.launch { try { cameraSource?.settings?.focus?.setAutoMode(when (nextMode) {
            FocusMode.CONTINUOUS -> CaptureResult.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            FocusMode.AUTO -> CaptureResult.CONTROL_AF_MODE_AUTO
            FocusMode.MACRO -> CaptureResult.CONTROL_AF_MODE_MACRO
        }) } catch (_: Exception) { } }
        return nextMode
    }

    fun toggleMute(): Boolean {
        val newMute = !(_isMuted.value ?: false)
        _isMuted.value = newMute
        try { streamer.audioInput?.isMuted = newMute } catch (_: Exception) { }
        return newMute
    }

    override fun onCleared() {
        super.onCleared()
        networkCallback?.let { try { connectivityManager?.unregisterNetworkCallback(it) } catch (_: Exception) { } }
        viewModelScope.launch { streamer.release() }
        bluetoothHelper.stopSco()
    }

    companion object { private const val TAG = "MainViewModel" }
}
