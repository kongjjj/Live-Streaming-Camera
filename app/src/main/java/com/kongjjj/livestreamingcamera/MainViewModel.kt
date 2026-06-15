package com.kongjjj.livestreamingcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.media.AudioFormat
import android.media.MediaFormat
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
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
import com.kongjjj.livestreamingcamera.utils.EffectSurfaceProcessor
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.pipelines.DispatcherProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.elements.encoders.AudioCodecConfig
import io.github.thibaultbee.streampack.core.elements.encoders.VideoCodecConfig
import io.github.thibaultbee.streampack.core.streamers.dual.DualStreamer
import io.github.thibaultbee.streampack.core.streamers.dual.DualStreamerAudioCodecConfig
import io.github.thibaultbee.streampack.core.streamers.dual.DualStreamerAudioConfig
import io.github.thibaultbee.streampack.core.streamers.dual.DualStreamerVideoConfig
import io.github.thibaultbee.streampack.core.streamers.dual.IAudioDualStreamer
import io.github.thibaultbee.streampack.core.streamers.dual.IVideoDualStreamer
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableAudioEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableVideoEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.pipelines.inputs.takeJpegSnapshot
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.thibaultbee.streampack.core.utils.extensions.isClosedException
import android.net.TrafficStats
import android.os.Process
import io.github.thibaultbee.srtdroid.core.models.Stats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.localbroadcastmanager.content.LocalBroadcastManager


data class StreamStats(
    val bitrateKbps: Int = 0,
    val uploadSpeedKbps: Int = 0,
    val maxBitrateKbps: Int = 0,
    val rttMs: Int? = null,
    val lossPercent: Float? = null,
    val sendRateMbps: Float? = null,
    val endpointType: String = "rtmp"
)

@SuppressLint("MissingPermission")
class MainViewModel(
    application: Application,
    private val rotationRepository: RotationRepository,
    val streamer: DualStreamer
) : AndroidViewModel(application), android.content.SharedPreferences.OnSharedPreferenceChangeListener {
    
    // 取得自定義處理器以更新特效
    private val effectProcessor: EffectSurfaceProcessor?
        get() = streamer.videoInput?.processor as? EffectSurfaceProcessor
    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)
    private val defaultDispatcher = Dispatchers.Default

    private val isMutedProvider = { _isMutedFlow.value }
    private val _isMutedFlow = MutableStateFlow(false)
    val isMuted: LiveData<Boolean> = _isMutedFlow.asLiveData()
    internal val endpointTypeKey by lazy { getApplication<Application>().getString(R.string.endpoint_type_key) }
    internal val rtmpUrlKey by lazy { getApplication<Application>().getString(R.string.rtmp_server_url_key) }

    internal val audioEncoderKey by lazy { getApplication<Application>().getString(R.string.audio_encoder_key) }
    internal val audioSampleRateKey by lazy { getApplication<Application>().getString(R.string.audio_sample_rate_key) }
    internal val audioBitrateKey by lazy { getApplication<Application>().getString(R.string.audio_bitrate_key) }
    internal val videoResolutionKey by lazy { getApplication<Application>().getString(R.string.video_resolution_key) }
    internal val videoFpsKey by lazy { getApplication<Application>().getString(R.string.video_fps_key) }
    internal val videoBitrateKey by lazy { getApplication<Application>().getString(R.string.video_bitrate_key) }
    private val srtLatencyKey = "srt_latency"
    private val srtRegulatorEnabledKey = "srt_regulator_enabled"
    private val srtVideoTargetBitrateKey = "srt_video_target_bitrate"
    private val srtVideoMinBitrateKey = "srt_video_min_bitrate"
    private val srtRegulatorModeKey = "srt_regulator_mode"
    private val isStreaming: Boolean
        get() = streamer.first.isStreamingFlow.value
    private var currentCameraId: String? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wasStreamingBeforeNetworkLost = false

    private var isStreamRetrying = false
    private var isRecordingActionPending = false
    private var currentRotation: Int = 0
    private var streamRetryJob: Job? = null
    private var userStoppedManually = false
    private val _reconnectingMessage = MutableLiveData<String?>()
    val reconnectingMessage: LiveData<String?> = _reconnectingMessage
    private var isConfigApplied = false

    private var lastAppliedResolution: Size? = null
    private var lastAppliedFps: Int? = null

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
    private var lastTxBytes: Long = 0
    private var lastStatsTime: Long = 0

    enum class FlashMode { ON, OFF }
    enum class WhiteBalanceMode { AUTO, INCANDESCENT, FLUORESCENT, DAYLIGHT, CLOUDY }
    enum class FocusMode { AUTO, CONTINUOUS, MACRO }

    private val _flashMode = MutableLiveData(FlashMode.OFF)
    val flashMode: LiveData<FlashMode> = _flashMode
    private val _whiteBalanceMode = MutableLiveData(WhiteBalanceMode.AUTO)
    val whiteBalanceMode: LiveData<WhiteBalanceMode> = _whiteBalanceMode
    private val _exposureCompensation = MutableLiveData(0)
    val exposureCompensation: LiveData<Int> = _exposureCompensation
    private val _focusMode = MutableLiveData(FocusMode.AUTO)
    val focusMode: LiveData<FocusMode> = _focusMode


    private val _isGrayscale = MutableLiveData(false)
    val isGrayscale: LiveData<Boolean> = _isGrayscale
    private val _isBeauty = MutableLiveData(false)
    val isBeauty: LiveData<Boolean> = _isBeauty
    private val _isBlur = MutableLiveData(false)
    val isBlur: LiveData<Boolean> = _isBlur
    private val _isMosaic = MutableLiveData(false)
    val isMosaic: LiveData<Boolean> = _isMosaic
    private val _isSepia = MutableLiveData(false)
    val isSepia: LiveData<Boolean> = _isSepia
    private val _isSplitThree = MutableLiveData(false)
    val isSplitThree: LiveData<Boolean> = _isSplitThree

    // PiP 相關
    private val _isPipEnabled = MutableLiveData(false)
    val isPipEnabled: LiveData<Boolean> = _isPipEnabled
    private val _pipPosition = MutableLiveData(8) // 預設右下角
    val pipPosition: LiveData<Int> = _pipPosition
    private val _pipSize = MutableLiveData(0.25f) // 預設 1/4
    val pipSize: LiveData<Float> = _pipSize
    private val _pipPadding = MutableLiveData(0) // 預設 0
    val pipPadding: LiveData<Int> = _pipPadding
    private val _pipRounded = MutableLiveData(false) // 預設無圓角
    val pipRounded: LiveData<Boolean> = _pipRounded

    private var pipCameraSource: ICameraSource? = null

    @Suppress("unused") private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var currentBaseAudioFactory: IAudioSourceInternal.Factory = ConditionalAudioSourceFactory()

    val cameraSource: ICameraSource?
        get() = streamer.videoInput?.sourceFlow?.value as? ICameraSource

    override fun onSharedPreferenceChanged(sharedPreferences: android.content.SharedPreferences?, key: String?) {
        if (key == endpointTypeKey || key == "video_encoder_key") {
            if (isStreaming) {
                viewModelScope.launch {
                    _toastMessage.postValue("設定已更改，重啟推流以套用...")
                    stopStream()
                    delay(1000)
                    startStream()
                }
            }
        }
    }

    init {
        logAllPreferences()
        prefs.registerOnSharedPreferenceChangeListener(this)
        viewModelScope.launch(defaultDispatcher) {
            rotationRepository.rotationFlow.collect { rotation -> 
                currentRotation = rotation
                streamer.setTargetRotation(rotation) 
            }
        }
        if (!prefs.contains("video_encoder_key")) {
            prefs.edit().putString("video_encoder_key", "h264").apply()
        }
        setupNetworkMonitoring()
        viewModelScope.launch {
            streamer.first.isStreamingFlow.collect { 
                updateStreamingState(it)
                if (it) startStatsCollection() else stopStatsCollection()
            }
        }
        viewModelScope.launch {
            streamer.throwableFlow.filterNotNull().filter { it.isClosedException }.collect { _ ->
                if (!userStoppedManually) startStreamRetry()
            }
        }
    }

    fun enableBluetooth(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                switchToBuiltInMic()
                _bluetoothEnabled.postValue(false)
                return@launch
            }
            val (device, permissionDenied) = bluetoothHelper.detectBluetoothScoDeviceWithStatus()
            if (permissionDenied) {
                _bluetoothEnabled.postValue(false)
                // Permission request is already triggered inside bluetoothHelper
                return@launch
            }
            if (device == null) {
                _bluetoothEnabled.postValue(false)
                _toastMessage.postValue("未偵測到藍牙耳機")
                return@launch
            }
            if (withContext(Dispatchers.IO) { bluetoothHelper.startScoAndWait(4000) }) {
                try {
                    applyAudioSource(BluetoothAudioSourceFactory(device))
                    _bluetoothEnabled.postValue(true)
                    _toastMessage.postValue("藍牙麥克風已啟用")
                } catch (_: Exception) {
                    bluetoothHelper.stopSco()
                    _bluetoothEnabled.postValue(false)
                    _toastMessage.postValue("藍牙啟用失敗")
                }
            } else {
                _bluetoothEnabled.postValue(false)
                _toastMessage.postValue("藍牙 SCO 連線超時")
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
        applyAudioSource(ConditionalAudioSourceFactory())
        _toastMessage.postValue("藍牙麥克風已關閉")
    }

    fun updateBluetoothIcon(enabled: Boolean) { _bluetoothEnabled.postValue(enabled) }

    private fun startStatsCollection() {
        stopStatsCollection()
        lastTxBytes = TrafficStats.getUidTxBytes(Process.myUid())
        lastStatsTime = System.currentTimeMillis()
        statsJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val currentTime = System.currentTimeMillis()
                    val currentTxBytes = TrafficStats.getUidTxBytes(Process.myUid())
                    val timeDiffMs = currentTime - lastStatsTime
                    
                    val uploadSpeedKbps = if (timeDiffMs > 0 && currentTxBytes >= lastTxBytes) {
                        val bytesDiff = currentTxBytes - lastTxBytes
                        // bytes * 8 (bits) / 1000 (kb) / (ms / 1000) (s) => bits / ms * 0.008
                        ((bytesDiff * 8.0) / timeDiffMs).toInt()
                    } else {
                        0
                    }
                    
                    lastTxBytes = currentTxBytes
                    lastStatsTime = currentTime

                    val endpointType = prefs.getString(endpointTypeKey, "rtmp") ?: "rtmp"
                    val videoEncoder = (streamer.first as? IConfigurableVideoEncodingPipelineOutput)?.videoEncoder
                    val currentBitrate = (videoEncoder?.bitrate ?: 0) / 1000
                    
                    // 注意：RtmpEndpoint 可能未實作 getMetrics() 並拋出 NotImplementedError
                    val metrics = if (endpointType == "srt") {
                        try { streamer.first.endpoint.metrics } catch (_: Exception) { null } catch (_: NotImplementedError) { null }
                    } else {
                        null
                    }
                    
                    val maxBitrate = if (endpointType == "srt") {
                        val regulatorEnabled = prefs.getBoolean(srtRegulatorEnabledKey, false)
                        if (regulatorEnabled) {
                            // 若開啟調節器，嘗試從 metrics 獲取當前實際目標碼率 (若有的話)，
                            // 否則顯示當前編碼器設定的碼率
                            if (currentBitrate > 0) currentBitrate else (prefs.getString(srtVideoTargetBitrateKey, "5000") ?: "5000").toInt()
                        } else {
                            prefs.getInt(videoBitrateKey, 2000)
                        }
                    } else {
                        prefs.getInt(videoBitrateKey, 2000)
                    }

                    val stats = if (endpointType == "srt" && metrics != null) {
                        try {
                            // 優先嘗試直接讀取欄位或 getter
                            val cls = metrics.javaClass
                            val msRTT = try { cls.getMethod("getMsRTT").invoke(metrics) as Long } catch (_: Exception) { cls.getField("msRTT").get(metrics) as Long }
                            val pktSndLoss = try { cls.getMethod("getPktSndLoss").invoke(metrics) as Long } catch (_: Exception) { cls.getField("pktSndLoss").get(metrics) as Long }
                            val pktSent = try { cls.getMethod("getPktSent").invoke(metrics) as Long } catch (_: Exception) { cls.getField("pktSent").get(metrics) as Long }
                            val mbpsSendRate = try { cls.getMethod("getMbpsSendRate").invoke(metrics) as Double } catch (_: Exception) { cls.getField("mbpsSendRate").get(metrics) as Double }

                            val loss = if (pktSent > 0) (pktSndLoss.toFloat() / pktSent) * 100 else 0f
                            StreamStats(
                                bitrateKbps = currentBitrate,
                                uploadSpeedKbps = (mbpsSendRate * 1000).toInt(), // 使用 SRT 內部速度
                                maxBitrateKbps = maxBitrate,
                                rttMs = msRTT.toInt(),
                                lossPercent = loss,
                                sendRateMbps = mbpsSendRate.toFloat(),
                                endpointType = "srt"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "解析 SRT 統計失敗: ${e.message}")
                            StreamStats(
                                bitrateKbps = currentBitrate,
                                uploadSpeedKbps = uploadSpeedKbps,
                                maxBitrateKbps = maxBitrate,
                                endpointType = "srt"
                            )
                        }
                    } else {
                        StreamStats(
                            bitrateKbps = currentBitrate,
                            uploadSpeedKbps = uploadSpeedKbps,
                            maxBitrateKbps = maxBitrate,
                            endpointType = endpointType
                        )
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
            val encoder = (streamer.first as? IConfigurableVideoEncodingPipelineOutput)?.videoEncoder
            val getSurfaceMethod = encoder?.let { it::class.java }?.methods?.find { it.name == "getInputSurface" || it.name == "inputSurface" }
            getSurfaceMethod?.invoke(encoder) as? Surface
        } catch (e: Exception) {
            Log.e(TAG, "獲取編碼器 Surface 失敗", e)
            null
        }
    }

    private fun getRecordEncoderInputSurface(): Surface? {
        return try {
            val encoder = (streamer.second as? IConfigurableVideoEncodingPipelineOutput)?.videoEncoder
            val getSurfaceMethod = encoder?.let { it::class.java }?.methods?.find { it.name == "getInputSurface" || it.name == "inputSurface" }
            getSurfaceMethod?.invoke(encoder) as? Surface
        } catch (e: Exception) {
            Log.e(TAG, "獲取錄影編碼器 Surface 失敗", e)
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
                if (wasStreamingBeforeNetworkLost) {
                    _reconnectingMessage.postValue("失去連線，等待網路恢復...")
                }
            }

            override fun onAvailable(network: Network) {
                val capabilities = connectivityManager?.getNetworkCapabilities(network)
                val type = if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) "WiFi" else "行動數據"
                Log.d(TAG, "網路可用: $type")
                
                if (wasStreamingBeforeNetworkLost || isStreamRetrying) {
                    Log.d(TAG, "偵測到網路切換，立即觸發重連")
                    // 網路恢復後立即重連，取消舊的 retry job 以避免等待
                    streamRetryJob?.cancel()
                    isStreamRetrying = false 
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
            var retryCount = 0
            val maxDelay = 30000L

            while (isStreamRetrying && !userStoppedManually) {
                _reconnectingMessage.postValue("網路切換，嘗試連線中...")
                try {
                    Log.d(TAG, "正在重連 (嘗試 $retryCount)...")
                    if (streamer.first.isStreamingFlow.value) {
                        try { streamer.first.stopStream() } catch (_: Exception) {}
                    }
                    streamer.first.close()
                    
                    delay(1000) // 給予 OS 更多時間釋放資源並穩定網路路徑
                    
                    applyCurrentConfig()
                    
                    val success = withTimeoutOrNull(10000L) {
                        startStreamInternal(shouldSuppressErrors = true)
                    } ?: false

                    if (success) {
                        Log.i(TAG, "重連成功")
                        _reconnectingMessage.postValue(null)
                        break
                    } else {
                        retryCount++
                        val delayTime = minOf(2000L * (1 shl (minOf(retryCount - 1, 5))), maxDelay)
                        _reconnectingMessage.postValue("連線失敗，${delayTime / 1000}秒後重試...")
                        Log.w(TAG, "重連失敗，等待 $delayTime ms")
                        delay(delayTime)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Retry cycle error", e)
                    delay(5000)
                }
            }
            _isTryingConnectionLiveData.postValue(false)
            isStreamRetrying = false
        }
    }

    private suspend fun startStreamInternal(shouldSuppressErrors: Boolean = false): Boolean {
        // 啟動前強制重新掛載音訊來源並套用配置，解決 RTMP 間中無聲問題
        try {
            applyAudioSource()
            delay(100) 
            applyCurrentConfig()
        } catch (e: Exception) {
            Log.e(TAG, "初始化音訊或配置失敗", e)
        }

        isConfigApplied = true
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

        streamer.first.removeBitrateRegulatorController()
        if (endpointType == "srt") createSrtRegulatorFactory()?.let { streamer.first.addBitrateRegulatorController(it) }

        return try {
            streamer.first.startStream(url)

            // 等待編碼器初始化（使用介面）
            val videoStreamer = streamer.first as? IConfigurableVideoEncodingPipelineOutput
            val audioStreamer = streamer.first as? IConfigurableAudioEncodingPipelineOutput

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
            if (streamer.first.isStreamingFlow.value) streamer.first.stopStream()
            streamer.first.close()
        } catch (_: Exception) { }
        _isTryingConnectionLiveData.postValue(false)
        isConfigApplied = false
    }

    val isStreamingLiveData: LiveData<Boolean> = streamer.first.isStreamingFlow.asLiveData()
    private val _isRecordingLiveData = MutableLiveData<Boolean>(false)
    val isRecordingLiveData: LiveData<Boolean> = streamer.second.isStreamingFlow.asLiveData()
    private val _isTryingConnectionLiveData = MutableLiveData<Boolean>()
    val isTryingConnectionLiveData: LiveData<Boolean> = _isTryingConnectionLiveData

    // 一般錯誤（非關閉性）
    private val _throwableLiveData = MutableLiveData<Throwable>()
    val throwableLiveData: LiveData<Throwable> = _throwableLiveData

    // 關閉性錯誤（會觸發重連）
    val closedThrowableLiveData: LiveData<Throwable> = streamer.throwableFlow.filterNotNull().filter { it.isClosedException }.asLiveData()

    fun invalidateConfig() {
        isConfigApplied = false
    }

    suspend fun applyCurrentConfig() {
        if (isStreaming) {
            Log.w(TAG, "Cannot apply config while streaming")
            return
        }
        setAudioConfig()
        setVideoConfig()
        isConfigApplied = true
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun setAudioConfig() {
        if (isStreaming) {
            Log.w(TAG, "Cannot change audio config while streaming")
            return
        }

        val encoderType = prefs.getString(audioEncoderKey, "aac") ?: "aac"
        val liveMimeType = when (encoderType) {
            "opus" -> MediaFormat.MIMETYPE_AUDIO_OPUS
            else -> MediaFormat.MIMETYPE_AUDIO_AAC
        }

        // 讀取偏好設定
        var sampleRate = (prefs.getString(audioSampleRateKey, "48000") ?: "48000").toInt()
        
        // OPUS 安全檢查：如果採樣率不在支援列表中，回退到 48000
        val supportedOpusRates = listOf(8000, 12000, 16000, 24000, 48000)
        if (liveMimeType == MediaFormat.MIMETYPE_AUDIO_OPUS && sampleRate !in supportedOpusRates) {
            Log.w(TAG, "不支援的 Opus 採樣率: $sampleRate, 已回退到 48000 Hz")
            sampleRate = 48000
        }

        val bitrate = (prefs.getString(audioBitrateKey, "128000") ?: "128000").toInt()
        val byteFormat = AudioFormat.ENCODING_PCM_16BIT // 移出設定，使用標準 16-bit
        val profile = 2 // 移出設定，使用 AACObjectLC 作為預設

        Log.d(TAG, "套用音訊設定: $encoderType, $sampleRate Hz, $bitrate bps, Format: $byteFormat, Profile: $profile")

        if (streamer.second.isStreamingFlow.value) {
            // 錄影中，僅更新直播管道
            val config = AudioCodecConfig(
                mimeType = liveMimeType,
                sampleRate = sampleRate,
                channelConfig = AudioFormat.CHANNEL_IN_STEREO,
                byteFormat = byteFormat,
                startBitrate = bitrate,
                profile = profile
            )
            (streamer.first as? IConfigurableAudioEncodingPipelineOutput)?.setAudioCodecConfig(config)
        } else {
            // 未錄影，設定雙管道獨立編碼器
            // 第一管道 (直播): 使用用戶選擇的編碼器 (AAC 或 OPUS)
            val liveCodec = DualStreamerAudioCodecConfig(
                mimeType = liveMimeType,
                startBitrate = bitrate,
                profile = profile
            )
            // 第二管道 (錄影): 強制使用 AAC 以確保 MP4 相容性
            val recordCodec = DualStreamerAudioCodecConfig(
                mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
                startBitrate = 128_000,
                profile = 2 // 錄影固定用 LC 確保相容性
            )

            val dualConfig = DualStreamerAudioConfig(
                firstAudioCodecConfig = liveCodec,
                secondAudioCodecConfig = recordCodec,
                sampleRate = sampleRate,
                channelConfig = AudioFormat.CHANNEL_IN_STEREO,
                byteFormat = byteFormat
            )
            streamer.setAudioConfig(dualConfig)
        }
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

        val fps = (prefs.getString(videoFpsKey, "25") ?: "25").toInt()

        val config = VideoCodecConfig(
            mimeType = mimeType,
            resolution = Size(width, height),
            fps = fps
        )
        Log.d(TAG, "設定影片編碼: $mimeType, 解析度: ${width}x${height}")

        val resolutionChanged = lastAppliedResolution != Size(width, height) || lastAppliedFps != fps

        if (streamer.second.isStreamingFlow.value) {
            (streamer.first as? IConfigurableVideoEncodingPipelineOutput)?.setVideoCodecConfig(config)
        } else {
            streamer.setVideoConfig(DualStreamerVideoConfig(config))
            // 如果解析度或 FPS 改變，且當前未推流/錄影，則重新設定相機以確保預覽和拍照使用新解析度
            if (resolutionChanged && !isStreaming) {
                currentCameraId?.let { streamer.setCameraId(it) }
            }
        }

        lastAppliedResolution = Size(width, height)
        lastAppliedFps = fps
    }

    suspend fun applyAudioSource(factory: IAudioSourceInternal.Factory = currentBaseAudioFactory) {
        currentBaseAudioFactory = factory
        val muteableFactory = MuteableAudioSourceFactory(factory, isMutedProvider)
        streamer.setAudioSource(muteableFactory)
    }

    suspend fun setAudioSource() {
        applyAudioSource()
    }
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

    fun setExposureCompensation(value: Int) {
        _exposureCompensation.value = value
        viewModelScope.launch { try { cameraSource?.settings?.exposure?.setCompensation(value) } catch (_: Exception) { } }
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
        val newMute = !_isMutedFlow.value
        _isMutedFlow.value = newMute
        return newMute
    }

    fun toggleGrayscale(): Boolean {
        val current = _isGrayscale.value ?: false
        val newValue = !current
        _isGrayscale.value = newValue
        updateProcessorEffects()
        return newValue
    }

    fun toggleBeauty(): Boolean {
        val current = _isBeauty.value ?: false
        val newValue = !current
        _isBeauty.value = newValue
        updateProcessorEffects()
        return newValue
    }

    fun toggleBlur(): Boolean {
        val current = _isBlur.value ?: false
        val newValue = !current
        _isBlur.value = newValue
        if (newValue) {
            _isMosaic.value = false
        }
        updateProcessorEffects()
        return newValue
    }

    fun toggleMosaic(): Boolean {
        val current = _isMosaic.value ?: false
        val newValue = !current
        _isMosaic.value = newValue
        if (newValue) {
            _isBlur.value = false
        }
        updateProcessorEffects()
        return newValue
    }

    fun toggleSepia(): Boolean {
        val current = _isSepia.value ?: false
        val newValue = !current
        _isSepia.value = newValue
        updateProcessorEffects()
        return newValue
    }

    fun toggleSplitThree(): Boolean {
        val current = _isSplitThree.value ?: false
        val newValue = !current
        _isSplitThree.value = newValue
        updateProcessorEffects()
        return newValue
    }

    fun setPipEnabled(enabled: Boolean) {
        _isPipEnabled.value = enabled
        updateProcessorEffects()
        
        viewModelScope.launch {
            if (enabled) {
                startPipCamera()
            } else {
                stopPipCamera()
            }
        }
    }

    private suspend fun startPipCamera() {
        if (pipCameraSource != null) return
        
        val cameraManager = getApplication<Application>().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val frontCameraId = cameraManager.cameraIdList.find { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return

        val surface = effectProcessor?.pipSurface ?: return
        
        try {
            val factory = CameraSourceFactory(frontCameraId)
            val dispatcherProvider = DispatcherProvider()
            val source = factory.create(getApplication(), dispatcherProvider) as ICameraSource
            
            // 設定 PiP 相機解析度為 480x360 以節省 CPU
            (source as IVideoSourceInternal).configure(VideoSourceConfig(resolution = Size(480, 360)))
            
            (source as ISurfaceSourceInternal).setOutput(surface)
            (source as IVideoSourceInternal).startStream()
            source.startPreview()
            pipCameraSource = source
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PiP camera", e)
        }
    }

    private suspend fun stopPipCamera() {
        pipCameraSource?.let {
            try {
                (it as IVideoSourceInternal).stopStream()
                it.stopPreview()
                (it as IVideoSourceInternal).release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop PiP camera", e)
            }
            pipCameraSource = null
        }
    }

    fun setPipPosition(pos: Int) {
        _pipPosition.value = pos
        updateProcessorEffects()
    }

    fun setPipSize(size: Float) {
        _pipSize.value = size
        updateProcessorEffects()
    }

    fun setPipPadding(padding: Int) {
        _pipPadding.value = padding
        updateProcessorEffects()
    }

    fun setPipRounded(rounded: Boolean) {
        _pipRounded.value = rounded
        updateProcessorEffects()
    }

    private fun updateProcessorEffects() {
        val posIndex = _pipPosition.value ?: 8
        val colCount = 4
        val rowCount = 3
        
        val col = posIndex % colCount
        val row = posIndex / colCount // 0 = 頂行 (1-4), 1 = 中行 (5-8), 2 = 底行 (9-12)

        // 將網格索引轉換為 GL 座標 [0, 1]
        // 寬度每一格佔 1/4, 高度每一格佔 1/3
        val cellWidth = 1.0f / colCount.toFloat()
        val cellHeight = 1.0f / rowCount.toFloat()
        
        // PiP 畫面大小：直接填滿整個格子
        var pipW = cellWidth
        var pipH = cellHeight
        
        // 計算座標 (左下角)
        // X 座標：從左到右 (0 -> 1)
        var px = col * cellWidth
        
        // Y 座標修正：
        // 為了讓使用者看到的 row 0 (1,2,3,4) 出現在直播畫面的最頂端，
        // 我們直接將 py 設定為 row * cellHeight。
        // row 0 -> py = 0.0
        // row 1 -> py = 0.33
        // row 2 -> py = 0.66
        var py = row * cellHeight

        // 處理 Padding
        val paddingPx = (_pipPadding.value ?: 0).toFloat()
        if (paddingPx > 0) {
            val res = prefs.getString(videoResolutionKey, "1280x720") ?: "1280x720"
            val parts = res.split("x")
            val screenW = parts[0].toFloatOrNull() ?: 1280f
            val screenH = parts[1].toFloatOrNull() ?: 720f
            
            val normPaddingX = paddingPx / screenW
            val normPaddingY = paddingPx / screenH
            
            // 縮小 PiP 大小以騰出空間
            pipW -= normPaddingX * 2.0f
            pipH -= normPaddingY * 2.0f
            
            // 調整起始座標以保持在格子中央
            px += normPaddingX
            py += normPaddingY
        }
        
        effectProcessor?.updateEffects(
            grayscale = _isGrayscale.value ?: false,
            beauty = _isBeauty.value ?: false,
            blur = _isBlur.value ?: false,
            mosaic = _isMosaic.value ?: false,
            sepia = _isSepia.value ?: false,
            splitThree = _isSplitThree.value ?: false,
            pipEnabled = _isPipEnabled.value ?: false,
            pipPos = floatArrayOf(px, py),
            pipSz = floatArrayOf(pipW, pipH),
            pipRounded = _pipRounded.value ?: false
        )
    }

    fun toggleRecording() {
        Log.d(TAG, "toggleRecording: isRecording=${streamer.second.isStreamingFlow.value}, isPending=$isRecordingActionPending")
        if (isRecordingActionPending) return
        isRecordingActionPending = true
        
        viewModelScope.launch {
            try {
                if (streamer.second.isStreamingFlow.value) {
                    try {
                        Log.d(TAG, "正在停止錄影...")
                        streamer.second.stopStream()
                        streamer.second.close()
                        _toastMessage.postValue("錄影已停止")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to stop recording", e)
                        _toastMessage.postValue("停止錄影失敗: ${e.message}")
                    }
                } else {
                    val storageUriString = prefs.getString(getApplication<Application>().getString(R.string.file_name_key), null)
                    if (storageUriString == null) {
                        _toastMessage.postValue("請先在設定中選擇儲存位置")
                        return@launch
                    }

                    try {
                        Log.d(TAG, "正在開始錄影...")
                        val storageUri = Uri.parse(storageUriString)
                        val directory = DocumentFile.fromTreeUri(getApplication(), storageUri)
                        if (directory == null || !directory.canWrite()) {
                            _toastMessage.postValue("儲存位置無法寫入")
                            return@launch
                        }

                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val fileName = "REC_$timestamp.mp4"
                        val file = directory.createFile("video/mp4", fileName)
                        if (file == null) {
                            _toastMessage.postValue("無法建立錄影檔案")
                            return@launch
                        }

                        // 確保錄影管道被徹底重置並套用最新配置
                        try {
                            streamer.second.stopStream()
                            streamer.second.close()
                        } catch (_: Exception) {}

                        applyCurrentConfig()

                        streamer.second.startStream(UriMediaDescriptor(getApplication(), file.uri))
                        Log.d(TAG, "錄影管道已啟動: $fileName")
                        
                        // 啟動後輪詢並禁用錄影 Pipe 的特效 (影相錄影不使用特效)
                        viewModelScope.launch {
                            repeat(10) { i ->
                                delay(500)
                                getRecordEncoderInputSurface()?.let { surface ->
                                    effectProcessor?.setSurfaceEffectEnabled(surface, false)
                                    Log.d(TAG, "已為錄影 Surface 禁用特效 (嘗試 $i): $surface")
                                    return@launch
                                }
                            }
                            Log.w(TAG, "未能獲取錄影 Surface，無法禁用特效")
                        }

                        _toastMessage.postValue("錄影開始: $fileName")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start recording", e)
                        _toastMessage.postValue("開始錄影失敗: ${e.message}")
                    }
                }
            } finally {
                // 減少延遲，讓按鈕更靈敏，但仍防止連擊
                delay(500)
                isRecordingActionPending = false
                Log.d(TAG, "toggleRecording: action pending cleared")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        networkCallback?.let { try { connectivityManager?.unregisterNetworkCallback(it) } catch (_: Exception) { } }
        viewModelScope.launch { streamer.release() }
        bluetoothHelper.stopSco()
    }

    companion object { private const val TAG = "MainViewModel" }
}
