package com.kongjjj.livestreamingcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.kongjjj.livestreamingcamera.databinding.ActivityMainBinding
import com.kongjjj.livestreamingcamera.models.AudioLevelFlow
import com.kongjjj.livestreamingcamera.ui.NetworkSignalOverlay
import com.kongjjj.livestreamingcamera.utils.PermissionsManager
import com.kongjjj.livestreamingcamera.utils.showDialog
import com.kongjjj.livestreamingcamera.utils.toast
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.defaultCameraId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.kongjjj.livestreamingcamera.getBadgeImageRes
import com.kongjjj.livestreamingcamera.tts.TTSManager

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(this.application)
    }

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private lateinit var ttsManager: TTSManager
    private val ttsEnabledKey = "tts_enabled"
    private val ttsIgnoreUrlsKey = "tts_ignore_urls"
    private val ttsIgnoreEmotesKey = "tts_ignore_emotes"
    private val ttsIgnoreSenderKey = "tts_ignore_sender"
    private val spokenMessageIds = mutableSetOf<String>()
    // 偏好設定鍵名
    private val endpointTypeKey by lazy { getString(R.string.endpoint_type_key) }
    private val showStatsKey = "show_stats_overlay"
    private val showBatteryKey = "show_battery_overlay"
    private val showNetworkSignalKey = "show_network_signal_overlay"

    private val showAudioLevelKey = "show_audio_level"
    private var isBlackOverlayVisible = false
    // 共用 OkHttpClient
    private val chatShadowEnabledKey = "chat_text_shadow_enabled"
    private val chatShadowRadiusKey = "chat_text_shadow_radius"
    private val chatShadowDistanceKey = "chat_text_shadow_distance"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Twitch 聊天相關
    private lateinit var chatAdapter: ChatAdapter
    private val chatMessages = mutableListOf<ChatMessage>()

    private var lastMessageId: String? = null
    private var lastReceivedTimestamp: Long? = null
    private val hideStatusBarKey = "hide_status_bar"
    private var webSocket: WebSocket? = null
    private var isWebSocketConnected = false
    private var channel: String = ""
    private var isChatRetrying = false
    private var chatRetryJob: Job? = null

    private val twitchChannelKey = "twitch_channel"
    private val chatEnabledKey = "chat_enabled"
    @Suppress("PrivatePropertyName")
    private val PREF_CHAT_HISTORY = "chat_history"


    // 聊天歷史載入設定
    private val loadMessageHistoryKey = "load_message_history"
    private val loadMessageHistoryOnReconnectKey = "load_message_history_on_reconnect"
    private var loadMessageHistory = true
    private var loadMessageHistoryOnReconnect = true
    private var isFirstConnection = true

    // 聊天開關狀態
    private var isChatEnabled = true
        set(value) {
            field = value
            updateChatButtonIcon()
            prefs.edit { putBoolean(chatEnabledKey, value) }
            binding.chatRecyclerView.visibility = if (value) View.VISIBLE else View.GONE
            if (value) {
                startChatRetryLoop()
            } else {
                stopChatRetry()
                webSocket?.cancel()
                webSocket = null
                isWebSocketConnected = false
            }
        }

    // 網路監聽相關
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // 在 MainActivity 類別中新增
    private val serviceCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MyPersistentService.ACTION_START_STREAM -> {
                    if (viewModel.isStreamingLiveData.value != true) {
                        lifecycleScope.launch { viewModel.startStream() }
                    }
                }
                MyPersistentService.ACTION_STOP_STREAM -> {
                    if (viewModel.isStreamingLiveData.value == true) {
                        lifecycleScope.launch { viewModel.stopStream() }
                    }
                }
                MyPersistentService.ACTION_EXIT_APP -> {
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }
    }
    private val streamerRequiredPermissions =
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

    private val permissionsManager = PermissionsManager(
        this,
        streamerRequiredPermissions,
        onAllGranted = { onPermissionsGranted() },
        onShowPermissionRationale = { permissions, onRequiredPermissionLastTime ->
            showDialog(
                title = "權限被拒絕",
                message = "需要授予 $permissions 權限才能推流",
                positiveButtonText = R.string.accept,
                onPositiveButtonClick = { onRequiredPermissionLastTime() },
                negativeButtonText = R.string.denied
            )
        },
        onDenied = {
            showDialog(
                "權限被拒絕",
                "您需要授予所有權限才能推流",
                positiveButtonText = 0,
                negativeButtonText = 0
            )
        })

    // 通知權限請求（Android 13+）
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) Log.d(TAG, "通知權限已授予")
        else Log.w(TAG, "通知權限被拒絕")
    }

    // 藍牙權限請求（Android 12+）
    private val requestBluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.enableBluetooth(true)
        } else {
            toast("需要藍牙權限才能使用藍牙麥克風")
            viewModel.updateBluetoothIcon(false)
        }
    }
    private val showShakeLevelKey = "show_shake_level"
    // 訊號強度權限請求（位置與電話狀態）
    private val requestSignalPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            toast("部分權限未授予，訊號強度顯示可能不完整")
        }
    }

    // 音量條相關
    private lateinit var audioLevelFlow: AudioLevelFlow

    // 變焦相關
    private val zoomLevels = floatArrayOf(1.0f, 2.0f, 5.0f, 10.0f)
    private var currentZoomIndex = 0

    // ---------- 後置鏡頭按鈕列 ----------
    private lateinit var cameraButtonsContainer: LinearLayout
    private val backCameras = mutableListOf<CameraInfo>()

    data class CameraInfo(val id: String, val fov: Float, val displayName: String)

    // ---------- 觀看人數與直播時長更新 ----------
    private var viewerUpdateJob: Job? = null
    private var uptimeUpdateJob: Job? = null
    private var streamStartTime: Long? = null   // 直播開始時間（毫秒時間戳）

    // ---------- 直播資訊資料類別 ----------
    private data class StreamInfo(val viewers: Int, val createdAt: Long?) // createdAt 為毫秒時間戳

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraButtonsContainer = binding.cameraButtonsContainer

        setupEdgeToEdgeInsets()
        loadChatHistory()
        initChatSettings()
        setupChat()
        updateChatShadow()
        updateChatShadowRadius()
        updateChatShadowDistance()
        bindProperties()
        setupNetworkMonitoring()
        setupCameraControls()
        setupBluetoothButton()
        setupZoomButton()

        viewModel.reconnectingMessage.observe(this) { message ->
            message?.let { toast(it) }
        }

        if (prefs.getBoolean("background_service_enabled", false)) {
            startPersistentService()
        }

        requestNotificationPermission()
        prefs.registerOnSharedPreferenceChangeListener(this)
// 註冊服務命令接收器
        val filter = IntentFilter().apply {
            addAction(MyPersistentService.ACTION_START_STREAM)
            addAction(MyPersistentService.ACTION_STOP_STREAM)
            addAction(MyPersistentService.ACTION_EXIT_APP)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(serviceCommandReceiver, filter)

        viewModel.isStreamingLiveData.observe(this) { updateStatusText() }
        viewModel.isTryingConnectionLiveData.observe(this) { updateStatusText() }
        viewModel.bluetoothEnabled.observe(this) { updateBluetoothIcon(it) }
        viewModel.bluetoothPermissionRequest.observe(this) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestBluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        viewModel.toastMessage.observe(this) { message ->
            message?.let { toast(it) }
        }
        viewModel.streamStats.observe(this) { stats ->
            binding.streamStatsOverlay.updateStats(stats)
        }
        ttsManager = TTSManager(this)
        updateStatsOverlayVisibility()
        updateChatFontSize()
        updateStatusText()
        updateOverlayVisibility()  // 根據偏好設定初始可見性
        applyStatusBarVisibility()
        // 請求訊號強度所需的權限
        requestSignalPermissions()
    }
    private fun applyStatusBarVisibility() {
        // 黑屏模式下狀態欄已被隱藏，不需再改變
        if (isBlackOverlayVisible) return

        val hideStatusBar = prefs.getBoolean(hideStatusBarKey, false)
        if (hideStatusBar) {
            enterImmersiveMode()
        } else {
            exitImmersiveMode()
        }
    }
    private fun requestSignalPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissionsToRequest = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
            }
            if (permissionsToRequest.isNotEmpty()) {
                requestSignalPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.isStreamingLiveData.value == true) {
            Log.d(TAG, "Streaming in progress, skipping audio/video reconfiguration")
            // 注意：不要直接 return，因為還是需要處理聊天歷史等
        }
        if (prefs.getBoolean("pending_clear_history", false)) {
            prefs.edit { remove("pending_clear_history") }
            clearChatHistory()
        }
        startViewerUpdates()
        updateOverlayVisibility()  // 從設定頁返回時更新可見性
        applyStatusBarVisibility()
    }

    override fun onPause() {
        super.onPause()
        stopViewerUpdates()
        stopUptimeUpdates()
    }

    // ---------- 後置鏡頭相關方法 ----------
    private fun calculateDiagonalFOV(focalLength: Float, sensorWidth: Float, sensorHeight: Float): Float {
        val diagonal = kotlin.math.sqrt(sensorWidth * sensorWidth + sensorHeight * sensorHeight)
        val angleRad = 2.0 * kotlin.math.atan(diagonal / (2.0 * focalLength))
        return (angleRad * 180.0 / kotlin.math.PI).toFloat()
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun loadBackCameras() {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraIdList = cameraManager.cameraIdList
        backCameras.clear()

        for (cameraId in cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

                var fov = 0f
                if (focalLengths != null && focalLengths.isNotEmpty() && physicalSize != null) {
                    val focalLength = focalLengths[0]
                    fov = calculateDiagonalFOV(focalLength, physicalSize.width, physicalSize.height)
                }

                val displayName = if (fov > 0) "${fov.toInt()}°" else "Camera"
                backCameras.add(CameraInfo(cameraId, fov, displayName))
            }
        }

        createCameraButtons()
    }

    private fun createCameraButtons() {
        cameraButtonsContainer.removeAllViews()

        // 當只有一個或沒有後置鏡頭時，隱藏按鈕列
        if (backCameras.size <= 1) {
            cameraButtonsContainer.visibility = View.GONE
            return
        }

        cameraButtonsContainer.visibility = View.VISIBLE

        for (camera in backCameras) {
            val button = android.widget.Button(this).apply {
                text = camera.displayName
                tag = camera.id
                layoutParams = LinearLayout.LayoutParams(
                    45.dpToPx(),
                    45.dpToPx()
                ).apply {
                    marginEnd = 8.dpToPx()
                }
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.control_button_circle)
                backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.button_normal)
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                textSize = 12f
                setOnClickListener {
                    lifecycleScope.launch {
                        viewModel.setCameraId(camera.id)
                        Log.d(TAG, "切換到後置鏡頭: ${camera.displayName} (ID: ${camera.id})")
                        updateCameraButtonSelection(camera.id)
                    }
                }
            }
            cameraButtonsContainer.addView(button)
        }

        val currentCameraId = viewModel.cameraSource?.cameraId
        if (currentCameraId != null) {
            updateCameraButtonSelection(currentCameraId)
        }
    }

    private fun updateCameraButtonSelection(selectedCameraId: String) {
        for (i in 0 until cameraButtonsContainer.childCount) {
            val button = cameraButtonsContainer.getChildAt(i) as? android.widget.Button ?: continue
            val isSelected = button.tag == selectedCameraId
            button.backgroundTintList = ContextCompat.getColorStateList(
                this,
                if (isSelected) R.color.button_active else R.color.button_normal
            )
        }
    }

    // ---------- 直播資訊獲取（包含觀看人數與開始時間） ----------
    private fun fetchStreamInfo(channelName: String): StreamInfo {
        if (channelName.isBlank() || channelName == "yourchannel") return StreamInfo(0, null)
        return try {
            val request = Request.Builder()
                .url("https://gql.twitch.tv/gql")
                .addHeader("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko")
                .addHeader("Content-Type", "application/json")
                .post(
                    """
                {
                    "query": "query { user(login: \"$channelName\") { stream { viewersCount createdAt } } }"
                }
                """.trimIndent().toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val data = json.optJSONObject("data")
                val user = data?.optJSONObject("user")
                val stream = user?.optJSONObject("stream")
                val viewers = stream?.optInt("viewersCount", 0) ?: 0
                val createdAtStr = stream?.optString("createdAt")
                val createdAt = if (createdAtStr != null) {
                    try {
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }
                        dateFormat.parse(createdAtStr)?.time
                    } catch (_: Exception) {
                        null
                    }
                } else null
                StreamInfo(viewers, createdAt)
            } else {
                StreamInfo(0, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "獲取直播資訊失敗", e)
            StreamInfo(0, null)
        }
    }

    // ---------- 觀看人數與直播時長更新循環 ----------
    private fun startViewerUpdates() {
        stopViewerUpdates()
        viewerUpdateJob = lifecycleScope.launch {
            // 如果已有開始時間，立即啟動時長更新（確保前景顯示）
            if (streamStartTime != null) {
                startUptimeUpdates(streamStartTime!!)
            }

            while (isActive) {
                val channelName = prefs.getString(twitchChannelKey, "yourchannel") ?: "yourchannel"
                if (channelName.isNotBlank() && channelName != "yourchannel") {
                    var streamInfo: StreamInfo? = null
                    try {
                        streamInfo = withTimeout(5000L) {
                            withContext(Dispatchers.IO) { fetchStreamInfo(channelName) }
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "直播資訊請求超時", e)
                    } catch (e: Exception) {
                        Log.e(TAG, "直播資訊請求異常", e)
                    }

                    withContext(Dispatchers.Main) {
                        if (streamInfo != null) {
                            val viewers = streamInfo.viewers
                            val createdAt = streamInfo.createdAt

                            // 處理直播時長：僅當開始時間變更時更新
                            if (createdAt != null && streamStartTime != createdAt) {
                                // 開始時間變更（新開播或切換頻道）
                                streamStartTime = createdAt
                                startUptimeUpdates(createdAt)
                            } else if (createdAt == null && streamStartTime != null) {
                                // 頻道離線，清除開始時間
                                streamStartTime = null
                                stopUptimeUpdates()
                                binding.uptimeText.visibility = View.GONE
                            }

                            // 處理觀看人數顯示
                            if (!isBlackOverlayVisible) {
                                if (viewers > 0) {
                                    binding.viewerCountText.text = viewers.toString()
                                    binding.viewerCountLayout.visibility = View.VISIBLE
                                } else {
                                    binding.viewerCountLayout.visibility = View.GONE
                                }
                            } else {
                                binding.viewerCountText.text = viewers.toString()
                                binding.viewerCountLayout.visibility = View.GONE
                            }
                        } else {
                            // 請求失敗，不清除 streamStartTime，僅隱藏觀看人數（保持時長繼續計時）
                            binding.viewerCountLayout.visibility = View.GONE
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.viewerCountLayout.visibility = View.GONE
                        // 頻道名稱無效時，清除開始時間
                        if (streamStartTime != null) {
                            streamStartTime = null
                            stopUptimeUpdates()
                            binding.uptimeText.visibility = View.GONE
                        }
                    }
                }
                delay(15000) // 15 秒更新一次
            }
        }
    }

    private fun stopViewerUpdates() {
        viewerUpdateJob?.cancel()
        viewerUpdateJob = null
    }

    // ---------- 直播時長每秒更新 ----------
    // 移除 suspend 修飾符，因為內部不包含掛起點
    private fun startUptimeUpdates(startTime: Long) {
        stopUptimeUpdates()
        uptimeUpdateJob = lifecycleScope.launch {
            while (isActive) {
                withContext(Dispatchers.Main) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed >= 0) {
                        val hours = elapsed / 3600000
                        val minutes = (elapsed % 3600000) / 60000
                        val seconds = (elapsed % 60000) / 1000
                        val uptimeString = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                        binding.uptimeText.text = uptimeString
                        // 關鍵修改：只有在非黑屏模式且直播進行中才顯示
                        if (!isBlackOverlayVisible && streamStartTime != null) {
                            binding.uptimeText.visibility = View.VISIBLE
                        } else {
                            binding.uptimeText.visibility = View.GONE
                        }
                    } else {
                        binding.uptimeText.visibility = View.GONE
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopUptimeUpdates() {
        uptimeUpdateJob?.cancel()
        uptimeUpdateJob = null
        // 使用資源字串，避免硬編碼
        binding.uptimeText.text = getString(R.string.uptime_default)
    }

    // ---------- 以下為原有方法（保留完整） ----------
    private fun updateChatShadow() {
        val enabled = prefs.getBoolean(chatShadowEnabledKey, true)
        chatAdapter.setShadowEnabled(enabled)
    }

    private fun updateChatShadowRadius() {
        val radius = prefs.getInt(chatShadowRadiusKey, 2).toFloat()
        chatAdapter.setShadowRadius(radius)
    }

    private fun updateChatShadowDistance() {
        val distance = prefs.getInt(chatShadowDistanceKey, 1).toFloat()
        chatAdapter.setShadowDistance(distance)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            ttsEnabledKey -> {
                if (!prefs.getBoolean(ttsEnabledKey, false)) {
                    ttsManager.stop()
                }
            }
            endpointTypeKey -> updateStatusText()
            showStatsKey -> updateStatsOverlayVisibility()
            showBatteryKey, showNetworkSignalKey -> updateOverlayVisibility()
            "chat_font_size" -> updateChatFontSize()
            chatShadowEnabledKey -> updateChatShadow()
            chatShadowRadiusKey -> updateChatShadowRadius()
            chatShadowDistanceKey -> updateChatShadowDistance()
            twitchChannelKey -> {
                val newChannel = prefs.getString(twitchChannelKey, "yourchannel") ?: "yourchannel"
                if (newChannel != channel) {
                    channel = newChannel
                    // 清空現有聊天訊息
                    synchronized(chatMessages) {
                        chatMessages.clear()
                        saveChatMessages()          // 清空持久化儲存（避免下次啟動殘留）
                        lastMessageId = null
                        lastReceivedTimestamp = null
                    }
                    runOnUiThread {
                        chatAdapter.submitList(emptyList())
                        binding.chatRecyclerView.scrollToPosition(0)
                    }
                    if (isChatEnabled) {
                        webSocket?.close(1000, "Channel changed")
                        webSocket = null
                        isWebSocketConnected = false
                        startChatRetryLoop()
                    }
                }
            }
            loadMessageHistoryKey -> loadMessageHistory = prefs.getBoolean(loadMessageHistoryKey, true)
            loadMessageHistoryOnReconnectKey -> loadMessageHistoryOnReconnect = prefs.getBoolean(loadMessageHistoryOnReconnectKey, true)
            hideStatusBarKey -> applyStatusBarVisibility()
            showShakeLevelKey -> updateOverlayVisibility()
            showAudioLevelKey -> updateOverlayVisibility()
        }
    }

    private fun updateChatFontSize() {
        val fontSize = prefs.getInt("chat_font_size", 12).toFloat()
        chatAdapter.setFontSize(fontSize)
    }

    private fun updateStatsOverlayVisibility() {
        val enabled = prefs.getBoolean(showStatsKey, false)
        binding.streamStatsOverlay.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    /**
     * 根據偏好設定更新電池與網路訊號 Overlay 的可見性（需考慮黑屏模式）
     */
    private fun updateOverlayVisibility() {
        if (isBlackOverlayVisible) return
        val showBattery = prefs.getBoolean(showBatteryKey, true)
        val showNetwork = prefs.getBoolean(showNetworkSignalKey, true)
        val showShake = prefs.getBoolean(showShakeLevelKey, false)
        val showAudio = prefs.getBoolean(showAudioLevelKey, true)
        binding.batteryOverlay.visibility = if (showBattery) View.VISIBLE else View.GONE
        binding.networkSignalOverlay.visibility = if (showNetwork) View.VISIBLE else View.GONE
        binding.shakeLevelOverlay.visibility = if (showShake) View.VISIBLE else View.GONE
        binding.audioLevelOverlay.visibility = if (showAudio) View.VISIBLE else View.GONE
    }

    private fun setupBluetoothButton() {
        binding.bluetoothButton.setOnClickListener {
            toast("正在切換藍牙麥克風...")

            val newState = !(viewModel.bluetoothEnabled.value ?: false)

            if (newState && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requestBluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    return@setOnClickListener
                }
            }

            viewModel.enableBluetooth(newState)
        }
    }

    private fun updateBluetoothIcon(enabled: Boolean) {
        val icon = if (enabled) R.drawable.ic_bluetooth_on else R.drawable.ic_bluetooth_off
        binding.bluetoothButton.setImageResource(icon)
    }

    private fun setupZoomButton() {
        binding.zoomButton.setOnClickListener {
            val cameraSource = viewModel.cameraSource
            if (cameraSource == null) {
                toast("相機尚未初始化")
                return@setOnClickListener
            }

            currentZoomIndex = (currentZoomIndex + 1) % zoomLevels.size
            val targetZoom = zoomLevels[currentZoomIndex]

            lifecycleScope.launch {
                viewModel.setZoomRatio(targetZoom)
                toast(String.format(Locale.US, "變焦: %.1fx", targetZoom))
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun updateStatusText() {
        val endpointType = prefs.getString(endpointTypeKey, "rtmp") ?: "rtmp"
        val typeText = if (endpointType == "srt") getString(R.string.srt) else getString(R.string.rtmp)
        val statusText = when {
            viewModel.isStreamingLiveData.value == true -> getString(R.string.streaming)
            viewModel.isTryingConnectionLiveData.value == true -> getString(R.string.connecting)
            else -> getString(R.string.offline)
        }
        binding.statusTextView.text = getString(R.string.status_format, typeText, statusText)
    }

    private fun startPersistentService() {
        val intent = Intent(this, MyPersistentService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun setupEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val topMargin = systemBars.top

            binding.topRightButtonContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                this.topMargin = topMargin
                marginEnd = systemBars.right + 16.dpToPx()
            }

            binding.audioLevelOverlay.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                this.topMargin = topMargin + 2.dpToPx()
            }

            binding.streamStatsOverlay.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                this.topMargin = topMargin
            }

            binding.cameraControls.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + 5.dpToPx()
                rightMargin = systemBars.right + 16.dpToPx()
            }

            binding.liveButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom
            }

            insets
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun updateChatButtonIcon() {
        val iconRes = if (isChatEnabled) R.drawable.ic_chat_on else R.drawable.ic_chat_off
        binding.chatButton.setImageResource(iconRes)
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val builder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (isChatEnabled && !isWebSocketConnected) {
                        Log.d(TAG, "網路可用，嘗試重連聊天室")
                        startChatRetryLoop()
                    }
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    toast("失去網路連線")
                }
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(builder.build(), networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "註冊網路回呼失敗", e)
            networkCallback = null
        }
    }

    private fun isWebSocketConnected(): Boolean = isWebSocketConnected

    private fun extractRmReceivedTs(rawIrc: String): Long? {
        if (!rawIrc.startsWith("@")) return null
        val spaceIndex = rawIrc.indexOf(' ')
        if (spaceIndex == -1) return null
        val tagsPart = rawIrc.substring(1, spaceIndex)
        val tags = tagsPart.split(';')
        for (tag in tags) {
            val kv = tag.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == "rm-received-ts") {
                return kv[1].toLongOrNull()
            }
        }
        return null
    }

    private fun extractTimestamp(rawIrc: String): Long? {
        if (!rawIrc.startsWith("@")) return null
        val spaceIndex = rawIrc.indexOf(' ')
        if (spaceIndex == -1) return null
        val tagsPart = rawIrc.substring(1, spaceIndex)
        val tags = tagsPart.split(';')
        for (tag in tags) {
            val kv = tag.split('=', limit = 2)
            if (kv.size == 2) {
                when (kv[0]) {
                    "tmi-sent-ts", "rm-received-ts" -> return kv[1].toLongOrNull()
                }
            }
        }
        return null
    }

    private suspend fun fetchRecentMessages(channel: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        var url = "https://recent-messages.robotty.de/api/v2/recent-messages/$channel?limit=200&data=json"
        lastReceivedTimestamp?.let { ts ->
            url += "&after=$ts"
        }
        val request = Request.Builder().url(url).build()
        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Recent messages API 請求失敗: ${response.code}")
                return@withContext emptyList()
            }
            val jsonStr = response.body?.string() ?: ""
            if (jsonStr.isBlank()) return@withContext emptyList()

            val json = JSONObject(jsonStr)
            val messagesArray = json.optJSONArray("messages") ?: return@withContext emptyList()

            val recentList = mutableListOf<ChatMessage>()
            var maxTimestamp: Long? = lastReceivedTimestamp
            for (i in 0 until messagesArray.length()) {
                val rawMessage = messagesArray.optString(i)
                if (rawMessage.isBlank()) continue

                val ts = extractRmReceivedTs(rawMessage)
                if (ts != null) {
                    if (maxTimestamp == null || ts > maxTimestamp) {
                        maxTimestamp = ts
                    }
                }

                val chatMessage = parseTwitchMessage(rawMessage)
                chatMessage?.let {
                    recentList.add(it)
                }
            }
            if (maxTimestamp != null && maxTimestamp != lastReceivedTimestamp) {
                lastReceivedTimestamp = maxTimestamp
            }
            recentList
        } catch (e: Exception) {
            Log.e(TAG, "獲取最近訊息失敗", e)
            emptyList()
        }
    }

    private fun parseTwitchMessage(raw: String): ChatMessage? {
        if (!raw.contains("PRIVMSG")) return null

        val timestamp = extractTimestamp(raw) ?: System.currentTimeMillis()

        // 解析 tags 部分
        var tagsMap = emptyMap<String, String>()
        var restOfMessage = raw

        if (raw.startsWith("@")) {
            val parts = raw.split(" ", limit = 2)
            if (parts.size == 2) {
                val tagsPart = parts[0].removePrefix("@")
                restOfMessage = parts[1]
                tagsMap = tagsPart.split(";").associate {
                    val kv = it.split("=", limit = 2)
                    if (kv.size == 2) kv[0] to kv[1] else "" to ""
                }.filterKeys { it.isNotEmpty() }
            }
        }

        var messageId = tagsMap["id"] ?: UUID.randomUUID().toString()
        val displayName = tagsMap["display-name"] ?: ""
        var color = tagsMap["color"] ?: "#FFFFFF"
        if (color.isBlank() || !color.startsWith("#")) color = "#$color"

        // 解析 badges
        val badges = mutableListOf<Badge>()
        tagsMap["badges"]?.split(",")?.forEach { badgeEntry ->
            val parts = badgeEntry.split("/")
            if (parts.size == 2) {
                val name = parts[0]
                val version = parts[1]
                val imageRes = getBadgeImageRes(name)
                if (imageRes != 0) {
                    badges.add(Badge(name, version, imageRes))
                }
            }
        }

        // 提取發送者名稱和訊息
        val prefix = restOfMessage.substringBefore(" PRIVMSG ")
        val sender = displayName.ifEmpty {
            prefix.substringAfter(":").substringBefore("!")
        }

        val messageText = restOfMessage.substringAfter(" :", "").trim()

        if (messageId.isEmpty()) messageId = UUID.randomUUID().toString()

        return if (sender.isNotBlank() && messageText.isNotBlank()) {
            ChatMessage(
                id = messageId,
                sender = sender,
                message = messageText,
                color = color,
                isSystem = false,
                timestamp = timestamp,
                badges = badges
            )
        } else null
    }

    private fun initChatSettings() {
        channel = prefs.getString(twitchChannelKey, "yourchannel") ?: "yourchannel"
        isChatEnabled = prefs.getBoolean(chatEnabledKey, true)
        loadMessageHistory = prefs.getBoolean(loadMessageHistoryKey, true)
        loadMessageHistoryOnReconnect = prefs.getBoolean(loadMessageHistoryOnReconnectKey, true)
        updateChatButtonIcon()
        binding.chatRecyclerView.visibility = if (isChatEnabled) View.VISIBLE else View.GONE
        binding.chatButton.setOnClickListener { isChatEnabled = !isChatEnabled }
    }

    private fun setupChat() {
        chatAdapter = ChatAdapter()
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = chatAdapter
        }
        chatAdapter.submitList(chatMessages.toList())
        binding.chatRecyclerView.scrollToPosition(chatMessages.size - 1)
        if (isChatEnabled) startChatRetryLoop()
    }

    private fun startChatRetryLoop() {
        stopChatRetry()
        isChatRetrying = true
        isFirstConnection = true
        chatRetryJob = lifecycleScope.launch {
            while (isChatRetrying && isChatEnabled) {
                if (isWebSocketConnected()) {
                    isChatRetrying = false
                    break
                }
                connectIrc()
                delay(5000)
            }
        }
    }

    private fun stopChatRetry() {
        isChatRetrying = false
        chatRetryJob?.cancel()
        chatRetryJob = null
    }

    private fun connectIrc() {
        webSocket?.cancel()
        webSocket = null
        isWebSocketConnected = false

        // 紀錄連線時的頻道名稱，用於後續驗證
        val connectingChannel = channel

        val request = Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val rand = (1000..9999).random()
                webSocket.send("PASS justrandompass")
                webSocket.send("NICK justinfan$rand")
                webSocket.send("CAP REQ :twitch.tv/tags")
                webSocket.send("JOIN #$connectingChannel")

                isWebSocketConnected = true
                stopChatRetry()
                showSystemMessage("已連線聊天室")

                // 再次確認目前頻道是否與連線時相同，避免舊連線覆蓋新頻道
                if (channel != connectingChannel) {
                    Log.d(TAG, "連線頻道已變更，忽略此連線的回應")
                    webSocket.close(1000, "Channel changed during connection")
                    return
                }

                val shouldLoad = if (isFirstConnection) {
                    isFirstConnection = false
                    loadMessageHistory
                } else {
                    loadMessageHistoryOnReconnect
                }

                if (shouldLoad) {
                    lifecycleScope.launch {
                        val recent = fetchRecentMessages(connectingChannel)
                        // 再次確認頻道未變更
                        if (channel != connectingChannel) return@launch

                        if (recent.isNotEmpty()) {
                            val missing = if (lastMessageId != null) {
                                val index = recent.indexOfFirst { it.id == lastMessageId }
                                if (index >= 0 && index + 1 < recent.size) {
                                    recent.subList(index + 1, recent.size)
                                } else {
                                    recent
                                }
                            } else {
                                recent
                            }

                            synchronized(chatMessages) {
                                missing.forEach { msg ->
                                    if (chatMessages.none { it.id == msg.id }) {
                                        chatMessages.add(msg)
                                        lastMessageId = msg.id
                                    }
                                }

                                if (chatMessages.size > 200) {
                                    chatMessages.subList(0, chatMessages.size - 200).clear()
                                }

                                saveChatMessages()
                            }

                            runOnUiThread {
                                chatAdapter.submitList(chatMessages.toList()) {
                                    binding.chatRecyclerView.scrollToPosition(chatMessages.size - 1)
                                }
                            }
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 收到訊息時再次確認頻道是否仍為目前頻道
                if (channel != connectingChannel) {
                    // 頻道已變更，忽略此連線的所有後續訊息
                    return
                }

                val rawLines = text.split("\r\n")
                for (line in rawLines) {
                    if (line.isBlank()) continue

                    when {
                        line.startsWith("PING") -> {
                            webSocket.send("PONG :tmi.twitch.tv")
                        }
                        line.contains("PRIVMSG") -> {
                            val chatMessage = parseTwitchMessage(line)
                            chatMessage?.let { msg ->
                                synchronized(chatMessages) {
                                    if (chatMessages.none { it.id == msg.id }) {
                                        lastMessageId = msg.id
                                        msg.timestamp?.let { lastReceivedTimestamp = it }
                                            ?: run { lastReceivedTimestamp = System.currentTimeMillis() }

                                        chatMessages.add(msg)
                                        if (chatMessages.size > 200) {
                                            chatMessages.removeAt(0)
                                        }
                                        saveChatMessages()
                                    }
                                }
                                runOnUiThread {
                                    val atBottom = !binding.chatRecyclerView.canScrollVertically(1)
                                    chatAdapter.submitList(chatMessages.toList()) {
                                        if (atBottom) {
                                            binding.chatRecyclerView.smoothScrollToPosition(chatMessages.size - 1)
                                        }
                                    }
                                }

                                // ✅ 將 TTS 程式碼移到這裡（在 msg 作用域內）
                                val ttsEnabled = prefs.getBoolean(ttsEnabledKey, false)
                                if (ttsEnabled && !spokenMessageIds.contains(msg.id)) {
                                    spokenMessageIds.add(msg.id)
                                    // 避免集合無限增長
                                    if (spokenMessageIds.size > 500) {
                                        spokenMessageIds.clear()
                                    }
                                    val ignoreSender = prefs.getBoolean(ttsIgnoreSenderKey, false)
                                    val ignoreUrls = prefs.getBoolean(ttsIgnoreUrlsKey, false)
                                    val ignoreEmotes = prefs.getBoolean(ttsIgnoreEmotesKey, false)
                                    val textToSpeak = if (ignoreSender) {
                                        msg.message
                                    } else {
                                        "${msg.sender} 說：${msg.message}"
                                    }
                                    ttsManager.speak(textToSpeak, ignoreUrls, ignoreEmotes)
                                }
                            }
                        }

                        line.contains("NOTICE") && line.contains("Invalid channel") -> {
                            showSystemMessage("頻道不存在，請檢查名稱")
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // 只有當目前頻道仍是連線時的頻道時才觸發重連
                if (channel == connectingChannel && isChatEnabled) {
                    this@MainActivity.webSocket = null
                    isWebSocketConnected = false
                    startChatRetryLoop()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (channel == connectingChannel && isChatEnabled) {
                    this@MainActivity.webSocket = null
                    isWebSocketConnected = false
                    startChatRetryLoop()
                }
            }
        })
    }

    private fun showSystemMessage(text: String) {
        runOnUiThread {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveChatMessages() {
        val jsonArray = JSONArray()
        synchronized(chatMessages) {
            chatMessages.forEach { msg ->
                val obj = JSONObject().apply {
                    put("id", msg.id)
                    put("sender", msg.sender)
                    put("message", msg.message)
                    put("color", msg.color)
                    put("isSystem", msg.isSystem)
                    msg.timestamp?.let { put("timestamp", it) }
                }
                jsonArray.put(obj)
            }
        }
        prefs.edit { putString(PREF_CHAT_HISTORY, jsonArray.toString()) }
    }

    private fun loadChatHistory() {
        val jsonStr = prefs.getString(PREF_CHAT_HISTORY, null) ?: return
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val sender = obj.getString("sender")
                val message = obj.getString("message")
                val color = obj.getString("color")
                val isSystem = obj.optBoolean("isSystem", false)
                val timestamp = if (obj.has("timestamp")) obj.getLong("timestamp") else null
                chatMessages.add(ChatMessage(id, sender, message, color, isSystem, timestamp))
            }
            if (chatMessages.isNotEmpty()) {
                lastMessageId = chatMessages.last().id
            }
        } catch (e: Exception) {
            Log.e(TAG, "讀取聊天歷史失敗", e)
        }
    }

    private fun showMenuPopup() {
        PopupMenu(this, binding.menuButton).apply {
            menu.add(0, 1, 0, "聊天室設定")
            menu.add(0, 2, 1, "設定")
            menu.add(0, 3, 2, "版本資訊")
            menu.add(0, 4, 3, "更新及問題")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> openTwitchChatSettings()
                    2 -> openSettings()
                    3 -> openVersionInfo()
                    4 -> showKnownIssues()
                }
                true
            }
            show()
        }
    }

    private fun openTwitchChatSettings() {
        startActivity(Intent(this, TwitchChatSettingsActivity::class.java))
    }
    private fun openVersionInfo() {
        startActivity(Intent(this, VersionInfoActivity::class.java))
    }
    fun clearChatHistory() {
        synchronized(chatMessages) {
            chatMessages.clear()
            saveChatMessages()
            lastMessageId = null
            lastReceivedTimestamp = null
        }
        runOnUiThread {
            chatAdapter.submitList(emptyList())
        }
        showSystemMessage("聊天歷史已清除")
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
    private fun showKnownIssues() {
        startActivity(Intent(this, KnownIssuesActivity::class.java))
    }

    // ---------- 沉浸模式控制 ----------
    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    private fun exitImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ---------- 相機控制 ----------
    private fun setupCameraControls() {
        binding.blackScreenButton.setOnClickListener { toggleBlackOverlay() }

        binding.flashButton.setOnClickListener {
            val newMode = viewModel.toggleFlash()
            updateFlashIcon()
            toast("閃光燈: ${if (newMode == MainViewModel.FlashMode.ON) "開啟" else "關閉"}")
        }
        viewModel.flashMode.observe(this) { updateFlashIcon() }

        binding.whiteBalanceButton.setOnClickListener {
            val newMode = viewModel.cycleWhiteBalance()
            updateWhiteBalanceButton()
            val modeName = when (newMode) {
                MainViewModel.WhiteBalanceMode.AUTO -> "自動"
                MainViewModel.WhiteBalanceMode.INCANDESCENT -> "白熾燈"
                MainViewModel.WhiteBalanceMode.FLUORESCENT -> "螢光燈"
                MainViewModel.WhiteBalanceMode.DAYLIGHT -> "日光"
                MainViewModel.WhiteBalanceMode.CLOUDY -> "多雲"
            }
            toast("白平衡: $modeName")
        }
        viewModel.whiteBalanceMode.observe(this) { updateWhiteBalanceButton() }

        binding.exposureButton.setOnClickListener {
            val newValue = viewModel.adjustExposure(true)
            updateExposureButton()
            toast("曝光補償: ${if (newValue > 0) "+$newValue" else newValue}")
        }
        binding.exposureButton.setOnLongClickListener {
            val newValue = viewModel.adjustExposure(false)
            updateExposureButton()
            toast("曝光補償: ${if (newValue > 0) "+$newValue" else newValue}")
            true
        }
        viewModel.exposureCompensation.observe(this) { updateExposureButton() }

        binding.focusButton.setOnClickListener {
            val newMode = viewModel.toggleFocusMode()
            updateFocusButton()
            val modeName = when (newMode) {
                MainViewModel.FocusMode.CONTINUOUS -> "連續"
                MainViewModel.FocusMode.AUTO -> "自動"
                MainViewModel.FocusMode.MACRO -> "微距"
            }
            toast("對焦: $modeName")
        }
        viewModel.focusMode.observe(this) { updateFocusButton() }

        binding.muteButton.setOnClickListener {
            val newMute = viewModel.toggleMute()
            updateMuteIcon()
            toast("麥克風: ${if (newMute) "靜音" else "通話"}")
        }
        viewModel.isMuted.observe(this) { updateMuteIcon() }
    }

    private fun toggleBlackOverlay() {
        if (binding.blackOverlay.isVisible) {
            binding.blackOverlay.isVisible = false
            restoreAllButtons()
            exitImmersiveMode()
            isBlackOverlayVisible = false
            // 退出黑屏後，重新套用偏好設定
            updateOverlayVisibility()
            applyStatusBarVisibility()
        } else {
            binding.blackOverlay.isVisible = true
            hideAllButtons()
            enterImmersiveMode()
            isBlackOverlayVisible = true
        }
    }

    private fun hideAllButtons() {
        // 隱藏右上角按鈕容器
        binding.topRightButtonContainer.visibility = View.GONE
        binding.batteryOverlay.visibility = View.GONE
        binding.shakeLevelOverlay.visibility = View.GONE
        // 隱藏右下角所有控制按鈕（包含直播按鈕、切換鏡頭等）
        binding.blackScreenButton.background = null
        binding.blackScreenButton.setImageDrawable(null)
        binding.blackScreenButton.alpha = 0f

        binding.flashButton.background = null
        binding.flashButton.setImageDrawable(null)
        binding.flashButton.alpha = 0f

        binding.switchCameraButton.background = null
        binding.switchCameraButton.setImageDrawable(null)
        binding.switchCameraButton.alpha = 0f

        binding.whiteBalanceButton.background = null
        binding.whiteBalanceButton.setImageDrawable(null)
        binding.whiteBalanceButton.alpha = 0f

        binding.exposureButton.background = null
        binding.exposureButton.setImageDrawable(null)
        binding.exposureButton.alpha = 0f

        binding.focusButton.background = null
        binding.focusButton.setImageDrawable(null)
        binding.focusButton.alpha = 0f

        binding.muteButton.background = null
        binding.muteButton.setImageDrawable(null)
        binding.muteButton.alpha = 0f

        binding.liveButton.background = null
        binding.liveButton.alpha = 0f

        // 隱藏觀看人數容器（完全隱藏，不佔空間）
        binding.viewerCountLayout.visibility = View.GONE
        binding.uptimeText.visibility = View.GONE

        // 隱藏訊號強度 Overlay
        binding.networkSignalOverlay.visibility = View.GONE

        binding.statusTextView.visibility = View.INVISIBLE
        binding.audioLevelOverlay.visibility = View.GONE
        binding.streamStatsOverlay.visibility = View.GONE
    }

    private fun restoreAllButtons() {
        // 顯示右上角按鈕容器
        binding.topRightButtonContainer.visibility = View.VISIBLE
        // 電池 Overlay 將由 updateOverlayVisibility 根據偏好設定決定，此處不直接設為 VISIBLE
        // 網路訊號 Overlay 同理
        // 恢復右下角所有控制按鈕
        binding.blackScreenButton.background = ContextCompat.getDrawable(this, R.drawable.control_button_bg)
        binding.blackScreenButton.setImageResource(R.drawable.ic_light)
        binding.blackScreenButton.alpha = 1.0f

        binding.flashButton.background = ContextCompat.getDrawable(this, R.drawable.control_button_bg)
        updateFlashIcon()
        binding.flashButton.alpha = 1.0f

        binding.switchCameraButton.background = ContextCompat.getDrawable(this, R.drawable.control_button_bg)
        binding.switchCameraButton.setImageResource(R.drawable.ic_switch_camera)
        binding.switchCameraButton.alpha = 1.0f

        binding.whiteBalanceButton.background = ContextCompat.getDrawable(this, R.drawable.control_button_bg)
        updateWhiteBalanceButton()
        binding.whiteBalanceButton.alpha = 1.0f

        binding.exposureButton.background = ContextCompat.getDrawable(this, R.drawable.control_button_bg)
        updateExposureButton()
        binding.exposureButton.alpha = 1.0f

        binding.focusButton.background = ContextCompat.getDrawable(this, R.drawable.control_button_bg)
        updateFocusButton()
        binding.focusButton.alpha = 1.0f

        binding.muteButton.background = ContextCompat.getDrawable(this, R.drawable.control_button_bg)
        updateMuteIcon()
        binding.muteButton.alpha = 1.0f

        binding.liveButton.background = ContextCompat.getDrawable(this, R.drawable.ic_live_button_selector)
        binding.liveButton.alpha = 1.0f

        // 恢復觀看人數容器：根據當前人數決定是否顯示
        val viewerCount = binding.viewerCountText.text.toString().toIntOrNull() ?: 0
        if (viewerCount > 0 && streamStartTime != null) {
            binding.viewerCountLayout.visibility = View.VISIBLE
        } else {
            binding.viewerCountLayout.visibility = View.GONE
        }
        // 恢復時長：若直播正在進行，則顯示
        if (streamStartTime != null) {
            binding.uptimeText.visibility = View.VISIBLE
        } else {
            binding.uptimeText.visibility = View.GONE
        }

        // 網路訊號與電池 Overlay 將由 updateOverlayVisibility 統一處理
        binding.statusTextView.visibility = View.VISIBLE
        binding.audioLevelOverlay.visibility = View.VISIBLE
        updateStatsOverlayVisibility()
        // 恢復 Overlay 可見性（根據偏好設定）
        updateOverlayVisibility()
        applyStatusBarVisibility()
    }

    private fun updateFlashIcon() {
        val icon = when (viewModel.flashMode.value) {
            MainViewModel.FlashMode.ON -> R.drawable.ic_flash_on
            else -> R.drawable.ic_flash_off
        }
        binding.flashButton.setImageResource(icon)
    }

    private fun updateWhiteBalanceButton() {
        val icon = when (viewModel.whiteBalanceMode.value) {
            MainViewModel.WhiteBalanceMode.AUTO -> R.drawable.ic_wb_auto
            MainViewModel.WhiteBalanceMode.INCANDESCENT -> R.drawable.ic_wb_incandescent
            MainViewModel.WhiteBalanceMode.FLUORESCENT -> R.drawable.ic_wb_fluorescent
            MainViewModel.WhiteBalanceMode.DAYLIGHT -> R.drawable.ic_wb_daylight
            MainViewModel.WhiteBalanceMode.CLOUDY -> R.drawable.ic_wb_cloudy
            else -> R.drawable.ic_wb_auto
        }
        binding.whiteBalanceButton.setImageResource(icon)
    }

    private fun updateExposureButton() {
        val value = viewModel.exposureCompensation.value ?: 0
        val icon = when (value) {
            -2 -> R.drawable.ic_exposure_minus_2
            -1 -> R.drawable.ic_exposure_minus_1
            0 -> R.drawable.ic_exposure_0
            1 -> R.drawable.ic_exposure_plus_1
            2 -> R.drawable.ic_exposure_plus_2
            else -> R.drawable.ic_exposure_0
        }
        binding.exposureButton.setImageResource(icon)
    }

    private fun updateFocusButton() {
        val icon = when (viewModel.focusMode.value) {
            MainViewModel.FocusMode.CONTINUOUS -> R.drawable.ic_focus_continuous
            MainViewModel.FocusMode.AUTO -> R.drawable.ic_focus_auto
            MainViewModel.FocusMode.MACRO -> R.drawable.ic_focus_macro
            else -> R.drawable.ic_focus_continuous
        }
        binding.focusButton.setImageResource(icon)
    }

    private fun updateMuteIcon() {
        val icon = if (viewModel.isMuted.value == true) R.drawable.ic_mute_on else R.drawable.ic_mute_off
        binding.muteButton.setImageResource(icon)
    }

    private fun bindProperties() {
        binding.menuButton.setOnClickListener { showMenuPopup() }
        binding.switchCameraButton.setOnClickListener {
            lifecycleScope.launch { viewModel.switchCamera() }
        }
        binding.liveButton.setOnCheckedChangeListener { view, isChecked ->
            if (view.isPressed) {
                if (isChecked) lifecycleScope.launch { viewModel.startStream() }
                else lifecycleScope.launch { viewModel.stopStream() }
            }
        }

        configureStreamer()

        viewModel.closedThrowableLiveData.observe(this) { throwable ->
            throwable?.let { toast("連線中斷: ${it.message}") }
        }
        viewModel.throwableLiveData.observe(this) { throwable ->
            throwable?.let { toast("錯誤: ${it.message}") }
        }
        viewModel.isStreamingLiveData.observe(this) { isStreaming ->
            binding.liveButton.isChecked = isStreaming == true || (viewModel.isTryingConnectionLiveData.value == true)
        }
        viewModel.isTryingConnectionLiveData.observe(this) { isWaiting ->
            binding.liveButton.isChecked = isWaiting == true || (viewModel.isStreamingLiveData.value == true)
        }
    }

    private fun configureStreamer() {
        lifecycleScope.launch {
            viewModel.setAudioConfig()
            viewModel.setVideoConfig()
        }
    }

    override fun onStart() {
        super.onStart()
        permissionsManager.requestPermissions()
    }

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    private fun onPermissionsGranted() {
        setAVSource()
        setStreamerView()
        startAudioLevelMeter()
        loadBackCameras()
    }

    private fun setAVSource() {
        lifecycleScope.launch {
            viewModel.setAudioSource()
            viewModel.setCameraId(defaultCameraId)
        }
    }

    private fun setStreamerView() {
        lifecycleScope.launch {
            binding.preview.setVideoSourceProvider(viewModel.streamer)
        }
    }

    private fun toast(message: String) {
        runOnUiThread { applicationContext.toast(message) }
    }

    private fun startAudioLevelMeter() {
        if (!::audioLevelFlow.isInitialized) {
            audioLevelFlow = AudioLevelFlow()
        }
        audioLevelFlow.start()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                audioLevelFlow.audioLevelFlow.collect { audioLevel ->
                    binding.audioLevelOverlay.updateAudioLevel(audioLevel)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceCommandReceiver)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.e(TAG, "NetworkCallback unregister error", e)
        }
        webSocket?.close(1000, "Activity destroyed")
        webSocket = null
        isWebSocketConnected = false
        stopChatRetry()
        stopViewerUpdates()
        stopUptimeUpdates()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::audioLevelFlow.isInitialized) {
            audioLevelFlow.stop()
        }
        ttsManager.shutdown()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}