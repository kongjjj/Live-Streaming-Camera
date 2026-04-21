package com.kongjjj.livestreamingcamera.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TTSManager"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var currentLocale: Locale = Locale.getDefault()

    private val messageQueue = ArrayDeque<String>()
    private var isSpeaking = false

    // 去重用：記錄最後朗讀的文字及時間
    private var lastSpokenText: String? = null
    private var lastSpokenTime = 0L

    init {
        tts = TextToSpeech(context, this)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(currentLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Language not supported, fallback to US English")
                tts?.setLanguage(Locale.US)
            }
            isReady = true
            Log.d(TAG, "TTS initialized")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    processQueue()
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    processQueue()
                }
            })
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    fun speak(text: String, ignoreUrls: Boolean = false, ignoreEmotes: Boolean = false) {
        if (!isReady) {
            Log.w(TAG, "TTS not ready")
            return
        }

        var processedText = text
        if (ignoreUrls) {
            processedText = processedText.replace(Regex("https?://\\S+"), "")
        }
        if (ignoreEmotes) {
            // 匹配 :name: 格式的表情符號
            processedText = processedText.replace(Regex(":\\w+:"), "")
        }
        processedText = processedText.trim()
        if (processedText.isEmpty()) return

        // 去重：若與上一句相同且間隔小於 2 秒，則跳過
        val now = System.currentTimeMillis()
        if (processedText == lastSpokenText && now - lastSpokenTime < 2000) {
            Log.d(TAG, "Skip duplicate TTS: $processedText")
            return
        }
        lastSpokenText = processedText
        lastSpokenTime = now

        synchronized(messageQueue) {
            messageQueue.addLast(processedText)
        }
        processQueue()
    }

    private fun processQueue() {
        if (isSpeaking) return
        synchronized(messageQueue) {
            if (messageQueue.isEmpty()) return
            val next = messageQueue.removeFirst()
            isSpeaking = true
            val utteranceId = UUID.randomUUID().toString()
            tts?.speak(next, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun stop() {
        tts?.stop()
        synchronized(messageQueue) {
            messageQueue.clear()
        }
        isSpeaking = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        isReady = false
    }
}