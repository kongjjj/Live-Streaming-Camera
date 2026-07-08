package com.kongjjj.livestreamingcamera

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class YouTubeChatManager(
    private val httpClient: OkHttpClient,
    private val onNewMessages: (List<ChatMessage>) -> Unit,
    private val onSystemMessage: (String) -> Unit
) {
    private var channelId: String? = null
    private var videoId: String? = null
    private var pollingJob: Job? = null
    private var isRunning = false
    private var continuation: String? = null
    private var apiKey: String? = null

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    fun start(channelId: String) {
        if (channelId.isBlank()) {
            Log.w(TAG, "Attempted to start with blank Channel ID")
            return
        }
        stop()
        this.channelId = channelId
        Log.i(TAG, "Starting YouTube Chat Manager for Channel ID: $channelId")
        isRunning = true
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            pollLoop(this)
        }
    }

    fun stop() {
        isRunning = false
        pollingJob?.cancel()
        pollingJob = null
        channelId = null
        videoId = null
        continuation = null
        apiKey = null
    }

    private suspend fun pollLoop(scope: CoroutineScope) {
        onSystemMessage("正在搜尋 YouTube 直播中...")

        // 1. Resolve Channel ID to Video ID if needed
        val cid = channelId ?: return
        videoId = resolveVideoIdFromChannel(cid)
        
        if (videoId == null) {
            Log.e(TAG, "Failed to find an active live stream for Channel ID: $cid")
            onSystemMessage("找不到該頻道的直播，請確認是否正在直播中")
            return
        }

        onSystemMessage("正在連線到 YouTube 直播...")
        
        // 2. Fetch initial page to get API key and continuation token
        if (!fetchInitialData()) {
            Log.e(TAG, "Failed to initialize YouTube chat for Video ID: $videoId")
            onSystemMessage("無法初始化 YouTube 聊天室")
            return
        }

        onSystemMessage("成功連線到 YouTube 聊天室")

        while (isRunning && scope.isActive) {
            try {
                if (continuation == null || apiKey == null) break

                val url = "https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?key=$apiKey"
                val jsonBody = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "WEB")
                            put("clientVersion", "2.20210622.10.00")
                        })
                    })
                    put("continuation", continuation)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .header("User-Agent", userAgent)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "YouTube get_live_chat failed: ${response.code}")
                    delay(5000)
                    continue
                }

                val body = response.body?.string() ?: ""
                val jsonObj = JSONObject(body)
                
                val continuationData = jsonObj.optJSONObject("continuationContents")?.optJSONObject("liveChatContinuation")
                
                // Extract next continuation
                continuation = continuationData?.optJSONArray("continuations")?.optJSONObject(0)
                    ?.optJSONObject("invalidationContinuationData")?.optString("continuation")
                    ?: continuationData?.optJSONArray("continuations")?.optJSONObject(0)
                    ?.optJSONObject("timedContinuationData")?.optString("continuation")

                // Parse messages
                val actions = continuationData?.optJSONArray("actions")
                if (actions != null) {
                    val newMessages = mutableListOf<ChatMessage>()
                    for (i in 0 until actions.length()) {
                        val action = actions.getJSONObject(i)
                        val item = action.optJSONObject("addChatItemAction")?.optJSONObject("item")
                        val renderer = item?.optJSONObject("liveChatTextMessageRenderer")
                        
                        if (renderer != null) {
                            val id = renderer.optString("id", UUID.randomUUID().toString())
                            val author = renderer.optJSONObject("authorName")?.optString("simpleText", "Unknown") ?: "Unknown"
                            val timestamp = (renderer.optString("timestampUsec").toLongOrNull() ?: (System.currentTimeMillis() * 1000L)) / 1000L
                            
                            val messageObj = renderer.optJSONObject("message")
                            val messageText = parseRuns(messageObj)
                            val segments = parseSegments(messageObj)
                            
                            if (messageText.isNotBlank()) {
                                val badges = mutableListOf<Badge>()
                                // 1. Platform Icon
                                badges.add(Badge("youtube", "1", R.drawable.ic_youtube))
                                
                                // 2. YouTube Specific Badges (Moderator, Member, Owner)
                                val authorBadges = renderer.optJSONArray("authorBadges")
                                if (authorBadges != null) {
                                    Log.d(TAG, "Found ${authorBadges.length()} badges for user $author")
                                    for (j in 0 until authorBadges.length()) {
                                        val badgeObj = authorBadges.getJSONObject(j).optJSONObject("liveChatAuthorBadgeRenderer") 
                                            ?: authorBadges.getJSONObject(j).optJSONObject("liveChatBadgeRenderer")
                                            
                                        val tooltip = badgeObj?.optString("tooltip", "") ?: ""
                                        val iconType = badgeObj?.optJSONObject("icon")?.optString("iconType")
                                        
                                        Log.d(TAG, "Badge $j: tooltip=$tooltip, iconType=$iconType")

                                        when {
                                            iconType == "MODERATOR" || tooltip.contains("管理員") || tooltip.contains("Moderator") -> {
                                                badges.add(Badge("moderator", "1", R.drawable.ic_youtubemod))
                                            }
                                            iconType == "OWNER" || tooltip.contains("頻道擁有者") || tooltip.contains("Owner") -> {
                                                badges.add(Badge("broadcaster", "1", R.drawable.ic_badge_broadcaster))
                                            }
                                            tooltip.contains("會員") || tooltip.contains("Member") || badgeObj?.has("customThumbnail") == true -> {
                                                val customThumbnails = badgeObj?.optJSONObject("customThumbnail")?.optJSONArray("thumbnails")
                                                val badgeUrl = customThumbnails?.optJSONObject(0)?.optString("url")
                                                
                                                if (badgeUrl != null) {
                                                    badges.add(Badge("member", "1", 0, badgeUrl))
                                                } else {
                                                    badges.add(Badge("vip", "1", R.drawable.ic_badge_vip))
                                                }
                                            }
                                        }
                                    }
                                }

                                newMessages.add(ChatMessage(
                                    id = id,
                                    sender = author,
                                    message = messageText,
                                    color = "#FF0000",
                                    timestamp = timestamp,
                                    badges = badges,
                                    segments = segments
                                ))
                            }
                        }
                    }
                    if (newMessages.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            onNewMessages(newMessages)
                        }
                    }
                }

                delay(5000)

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "YouTube poll error", e)
                delay(5000)
            }
        }
    }

    private fun resolveVideoIdFromChannel(cid: String): String? {
        try {
            val url = "https://www.youtube.com/channel/$cid/live"
            Log.d(TAG, "Resolving Video ID from: $url")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null

            // The URL redirects to /watch?v=VIDEO_ID or the HTML contains the VIDEO_ID
            val finalUrl = response.request.url.toString()
            if (finalUrl.contains("v=")) {
                return finalUrl.substringAfter("v=").substringBefore("&")
            }

            // Fallback: search in HTML
            val html = response.body?.string() ?: ""
            return Regex("\"videoId\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve Video ID from channel", e)
            return null
        }
    }

    private fun fetchInitialData(): Boolean {
        try {
            val vid = videoId ?: return false
            val url = "https://www.youtube.com/live_chat?v=$vid"
            Log.d(TAG, "Fetching initial data from: $url")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch initial page: ${response.code}")
                return false
            }

            val html = response.body?.string() ?: ""
            
            // Use RegEx as provided by the user
            apiKey = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            continuation = Regex("\"continuation\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)

            Log.d(TAG, "Extracted API Key: ${apiKey != null}, Continuation: ${continuation != null}")

            return apiKey != null && continuation != null
        } catch (e: Exception) {
            Log.e(TAG, "Fetch initial data failed", e)
            return false
        }
    }

    private fun parseRuns(obj: JSONObject?): String {
        if (obj == null) return ""
        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            if (run.has("text")) {
                sb.append(run.getString("text"))
            } else if (run.has("emoji")) {
                val emoji = run.getJSONObject("emoji")
                val text = emoji.optJSONArray("shortcuts")?.optString(0) ?: emoji.optString("emojiId", "emoji")
                sb.append(text)
            }
        }
        return sb.toString()
    }

    private fun parseSegments(obj: JSONObject?): List<MessageSegment> {
        val segments = mutableListOf<MessageSegment>()
        if (obj == null) return segments
        val runs = obj.optJSONArray("runs") ?: return segments
        
        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            if (run.has("text")) {
                segments.add(MessageSegment.Text(run.getString("text")))
            } else if (run.has("emoji")) {
                val emoji = run.getJSONObject("emoji")
                val name = emoji.optJSONArray("shortcuts")?.optString(0) ?: emoji.optString("emojiId", "emoji")
                val thumbnails = emoji.optJSONObject("image")?.optJSONArray("thumbnails")
                val url = thumbnails?.optJSONObject(0)?.optString("url")
                
                if (url != null) {
                    segments.add(MessageSegment.Emote(name, url))
                } else {
                    segments.add(MessageSegment.Text(name))
                }
            }
        }
        return segments
    }

    companion object {
        private const val TAG = "YouTubeChatManager"
    }
}