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
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
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
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.thibaultbee.streampack.core.utils.extensions.isClosedException
import io.github.thibaultbee.srtdroid.core.models.Stats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.localbroadcastmanager.content.LocalBroadcastManager


data class StreamStats(
    val bitrateKbps: Int = 0,
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
) : AndroidViewModel(application) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)
    private val defaultDispatcher = Dispatchers.Default

    private val endpointTypeKey by lazy { getApplication<Application>().getString(R.string.endpoint_type_key) }
    private val rtmpUrlKey by lazy { getApplication<Application>().getString(R.string.rtmp_server_url_key) }

    private val audioEncoderKey by lazy { getApplication<Application>().getString(R.string.audio_encoder_key) }
    private val audioSampleRateKey by lazy { getApplication<Application>().getString(R.string.audio_sample_rate_key) }
    private val audioBitrateKey by lazy { getApplication<Application>().getString(R.string.audio_bitrate_key) }
    private val videoResolutionKey by lazy { getApplication<Application>().getString(R.string.video_resolution_key) }
    private val videoFpsKey by lazy { getApplication<Application>().getString(R.string.video_fps_key) }
    private val videoBitrateKey by lazy { getApplication<Application>().getString(R.string.video_bitrate_key) }
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
            if (!enabled) { switchToBuiltInMic(); _bluetoothEnabled.postValue(false); return@launch }
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
                    streamer.setAudioSource(BluetoothAudioSourceFactory(device))
                    _bluetoothEnabled.postValue(true)
                    _toastMessage.postValue("藍牙麥克風已啟用")
                } catch (_: Exception) {
                    bluetoothHelper.stopSco(); _bluetoothEnabled.postValue(false); _toastMessage.postValue("藍牙啟用失敗")
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
                    val videoEncoder = (streamer.first as? IConfigurableVideoEncodingPipelineOutput)?.videoEncoder
                    val currentBitrate = (videoEncoder?.bitrate ?: 0) / 1000
                    val maxBitrate = if (endpointType == "srt") {
                        val regulatorEnabled = prefs.getBoolean(srtRegulatorEnabledKey, false)
                        if (regulatorEnabled) {
                            (prefs.getString(srtVideoTargetBitrateKey, "5000") ?: "5000").toInt()
                        } else {
                            prefs.getInt(videoBitrateKey, 2000)
                        }
                    } else {
                        prefs.getInt(videoBitrateKey, 2000)
                    }

                    val stats = if (endpointType == "srt") {
                        // 從第一個管道的 endpoint 直接拿 metrics
                        val metrics = streamer.first.endpoint.metrics
                        // 嘗試偵測是否包含 Stats 相關方法
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
                                maxBitrateKbps = maxBitrate,
                                rttMs = msRTT.toInt(),
                                lossPercent = loss,
                                sendRateMbps = mbpsSendRate.toFloat(),
                                endpointType = "srt"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "解析 SRT 統計失敗: ${e.message}")
                            StreamStats(bitrateKbps = currentBitrate, maxBitrateKbps = maxBitrate, endpointType = "srt")
                        }
                    } else {
                        StreamStats(bitrateKbps = currentBitrate, maxBitrateKbps = maxBitrate, endpointType = endpointType)
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
                    if (streamer.first.isStreamingFlow.value) streamer.first.stopStream()
                    streamer.first.close()
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
        // 啟動前強制重新掛載音訊來源並套用配置，解決 RTMP 間中無聲問題
        try {
            streamer.setAudioSource(ConditionalAudioSourceFactory())
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

        val encoderType = prefs.getString(audioEncoderKey, "aac") ?: "aac"
        val liveMimeType = when (encoderType) {
            "opus" -> MediaFormat.MIMETYPE_AUDIO_OPUS
            else -> MediaFormat.MIMETYPE_AUDIO_AAC
        }

        // Opus 必須使用 48000Hz，如果直播用 Opus，採樣率強制為 48k (AAC 也支援 48k)
        val sampleRate = if (liveMimeType == MediaFormat.MIMETYPE_AUDIO_OPUS) {
            48000
        } else {
            (prefs.getString(audioSampleRateKey, "44100") ?: "44100").toInt()
        }

        val bitrate = (prefs.getString(audioBitrateKey, "128000") ?: "128000").toInt()

        if (streamer.second.isStreamingFlow.value) {
            // 錄影中，僅更新直播管道
            val config = AudioCodecConfig(
                mimeType = liveMimeType,
                sampleRate = sampleRate,
                channelConfig = AudioFormat.CHANNEL_IN_STEREO,
                startBitrate = bitrate
            )
            (streamer.first as? IConfigurableAudioEncodingPipelineOutput)?.setAudioCodecConfig(config)
        } else {
            // 未錄影，設定雙管道獨立編碼器
            // 第一管道 (直播): 使用用戶選擇的編碼器 (AAC 或 OPUS)
            val liveCodec = DualStreamerAudioCodecConfig(
                mimeType = liveMimeType,
                startBitrate = bitrate
            )
            // 第二管道 (錄影): 強制使用 AAC 以確保 MP4 相容性
            val recordCodec = DualStreamerAudioCodecConfig(
                mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
                startBitrate = 128_000
            )

            val dualConfig = DualStreamerAudioConfig(
                firstAudioCodecConfig = liveCodec,
                secondAudioCodecConfig = recordCodec,
                sampleRate = sampleRate,
                channelConfig = AudioFormat.CHANNEL_IN_STEREO
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

        val config = VideoCodecConfig(
            mimeType = mimeType,
            resolution = Size(width, height),
            fps = (prefs.getString(videoFpsKey, "25") ?: "25").toInt()
        )
        Log.d(TAG, "設定影片編碼: $mimeType, 解析度: ${width}x${height}")
        if (streamer.second.isStreamingFlow.value) {
            (streamer.first as? IConfigurableVideoEncodingPipelineOutput)?.setVideoCodecConfig(config)
        } else {
            streamer.setVideoConfig(DualStreamerVideoConfig(config))
        }
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

    fun toggleRecording() {
        if (isRecordingActionPending) return
        isRecordingActionPending = true
        
        viewModelScope.launch {
            try {
                if (streamer.second.isStreamingFlow.value) {
                    try {
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
                        isRecordingActionPending = false
                        return@launch
                    }

                    try {
                        val storageUri = Uri.parse(storageUriString)
                        val directory = DocumentFile.fromTreeUri(getApplication(), storageUri)
                        if (directory == null || !directory.canWrite()) {
                            _toastMessage.postValue("儲存位置無法寫入")
                            isRecordingActionPending = false
                            return@launch
                        }

                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val fileName = "REC_$timestamp.mp4"
                        val file = directory.createFile("video/mp4", fileName)
                        if (file == null) {
                            _toastMessage.postValue("無法建立錄影檔案")
                            isRecordingActionPending = false
                            return@launch
                        }

                        // 確保錄影管道被徹底重置並套用最新配置
                        try {
                            streamer.second.stopStream()
                            streamer.second.close()
                        } catch (_: Exception) {}

                        applyCurrentConfig()

                        streamer.second.startStream(UriMediaDescriptor(getApplication(), file.uri))
                        _toastMessage.postValue("錄影開始: $fileName")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start recording", e)
                        _toastMessage.postValue("開始錄影失敗: ${e.message}")
                    }
                }
            } finally {
                // 延遲一段時間再允許下一次操作，避免快速重複點擊導致狀態混亂
                delay(1000)
                isRecordingActionPending = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        networkCallback?.let { try { connectivityManager?.unregisterNetworkCallback(it) } catch (_: Exception) { } }
        viewModelScope.launch { streamer.release() }
        bluetoothHelper.stopSco()
    }

    companion object { private const val TAG = "MainViewModel" }
}
