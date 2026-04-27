// EmoteManager.kt
package com.kongjjj.livestreamingcamera

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 訊息片段：文字或表情圖片
 */
sealed class MessageSegment {
    data class Text(val text: String) : MessageSegment()
    data class Emote(val name: String, val url: String) : MessageSegment()
}

/**
 * 表情管理器：載入 Twitch 原生、7TV、BTTV、FFZ 表情，並將聊天訊息解析為 [MessageSegment] 列表
 */
class EmoteManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // 全局表情（名稱 -> 圖片 URL）
    private val globalEmotes = mutableMapOf<String, String>()
    // 頻道表情（名稱 -> 圖片 URL）
    private val channelEmotes = mutableMapOf<String, String>()
    // 簡單 LRU 快取，儲存已經載入的 Drawable，避免重複同步載入
    private val drawableCache = LruCache<String, Drawable>(100)

    /**
     * 載入所有全域表情（7TV、BTTV、FFZ）
     */
    suspend fun loadGlobalEmotes(
        enable7tv: Boolean = true,
        enableBttv: Boolean = true,
        enableFfz: Boolean = true
    ) = withContext(Dispatchers.IO) {
        globalEmotes.clear()
        if (enable7tv) globalEmotes.putAll(fetch7TVGlobal())
        if (enableBttv) globalEmotes.putAll(fetchBTTVGlobal())
        if (enableFfz)  globalEmotes.putAll(fetchFFZGlobal())
    }

    /**
     * 載入頻道專屬表情（需要 Twitch 頻道數字 ID）
     */
    suspend fun loadChannelEmotes(channelId: String, channelName: String) = withContext(Dispatchers.IO) {
        channelEmotes.clear()
        channelEmotes.putAll(fetch7TVChannel(channelId))
        channelEmotes.putAll(fetchBTTVChannel(channelId))
        channelEmotes.putAll(fetchFFZChannel(channelName))
    }

    /**
     * 將原始訊息 + IRC emotes tag 解析為 [MessageSegment] 列表
     */
    fun parseMessage(message: String, emotesTag: String?): List<MessageSegment> {
        // 1. 解析 Twitch 原生表情位置
        val twitchPositions = parseTwitchEmotesTag(emotesTag, message)
        // 2. 合併第三方表情（全局 + 頻道）
        val allThirdParty = globalEmotes + channelEmotes
        // 3. 生成片段
        return buildSegments(message, twitchPositions, allThirdParty)
    }

    /**
     * 非同步載入表情圖片（供 Adapter 呼叫，返回 Drawable 或 null）
     */
    suspend fun loadEmoteDrawable(url: String): Drawable? = withContext(Dispatchers.IO) {
        drawableCache.get(url)?.let { return@withContext it }
        try {
            val drawable = Glide.with(context)
                .asDrawable()
                .load(url)
                .submit()
                .get()
            drawable?.let { drawableCache.put(url, it) }
            drawable
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 私有解析方法 ----------
    private fun parseTwitchEmotesTag(tag: String?, message: String): List<Triple<Int, Int, String>> {
        val positions = mutableListOf<Triple<Int, Int, String>>()
        tag?.split("/")?.forEach { entry ->
            val colonIdx = entry.indexOf(':')
            if (colonIdx < 0) return@forEach
            val emoteId = entry.substring(0, colonIdx)
            val url = "https://static-cdn.jtvnw.net/emoticons/v2/$emoteId/default/dark/1.0"
            entry.substring(colonIdx + 1).split(",").forEach { range ->
                val dashIdx = range.indexOf('-')
                if (dashIdx < 0) return@forEach
                val start = range.substring(0, dashIdx).toIntOrNull() ?: return@forEach
                val end = range.substring(dashIdx + 1).toIntOrNull() ?: return@forEach
                if (start <= end && end < message.length) {
                    positions.add(Triple(start, end, url))
                }
            }
        }
        positions.sortBy { it.first }
        return positions
    }

    private fun buildSegments(
        message: String,
        twitchPositions: List<Triple<Int, Int, String>>,
        thirdParty: Map<String, String>
    ): List<MessageSegment> {
        val segments = mutableListOf<MessageSegment>()
        var cursor = 0
        for ((start, end, url) in twitchPositions) {
            if (start > cursor) {
                val text = message.substring(cursor, start).trim()
                if (text.isNotEmpty()) segments.add(MessageSegment.Text(text))
            }
            val name = message.substring(start, end + 1)
            segments.add(MessageSegment.Emote(name, url))
            cursor = end + 1
            if (cursor < message.length && message[cursor] == ' ') cursor++
        }
        if (cursor < message.length) {
            val tail = message.substring(cursor).trim()
            if (tail.isNotEmpty()) segments.add(MessageSegment.Text(tail))
        }
        // 針對 Text 片段掃描第三方表情
        return segments.flatMap { segment ->
            if (segment is MessageSegment.Text) scanThirdPartyWords(segment.text, thirdParty)
            else listOf(segment)
        }
    }

    private fun scanThirdPartyWords(text: String, emotes: Map<String, String>): List<MessageSegment> {
        val result = mutableListOf<MessageSegment>()
        val words = text.split(" ")
        val pending = StringBuilder()
        for ((idx, word) in words.withIndex()) {
            val url = emotes[word]
            if (url != null) {
                if (pending.isNotEmpty()) {
                    result.add(MessageSegment.Text(pending.toString().trimEnd()))
                    pending.clear()
                }
                result.add(MessageSegment.Emote(word, url))
            } else {
                pending.append(word)
                if (idx < words.lastIndex) pending.append(' ')
            }
        }
        if (pending.isNotEmpty()) result.add(MessageSegment.Text(pending.toString().trimEnd()))
        return result
    }

    // ---------- API 請求（簡化版，僅供參考） ----------
    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: Exception) { null }
    }

    private suspend fun fetch7TVGlobal(): Map<String, String> {
        val body = get("https://7tv.io/v3/emote-sets/global") ?: return emptyMap()
        val root = JSONObject(body)
        val emotes = root.getJSONArray("emotes")
        val map = mutableMapOf<String, String>()
        for (i in 0 until emotes.length()) {
            val obj = emotes.getJSONObject(i)
            val name = obj.getString("name")
            val host = obj.getJSONObject("data").getJSONObject("host")
            val url = "https:${host.getString("url")}/1x.webp"
            map[name] = url
        }
        return map
    }

    private suspend fun fetchBTTVGlobal(): Map<String, String> {
        val body = get("https://api.betterttv.net/3/cached/emotes/global") ?: return emptyMap()
        val arr = JSONArray(body)
        val map = mutableMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.getString("id")
            val code = obj.getString("code")
            map[code] = "https://cdn.betterttv.net/emote/$id/1x"
        }
        return map
    }

    private suspend fun fetchFFZGlobal(): Map<String, String> {
        val body = get("https://api.frankerfacez.com/v1/set/global") ?: return emptyMap()
        val root = JSONObject(body)
        val sets = root.getJSONObject("sets")
        val map = mutableMapOf<String, String>()
        sets.keys().forEach { setKey ->
            val setObj = sets.getJSONObject(setKey)
            val emoticons = setObj.getJSONArray("emoticons")
            for (i in 0 until emoticons.length()) {
                val em = emoticons.getJSONObject(i)
                val name = em.getString("name")
                val url = "https:${em.getJSONObject("urls").getString("1")}"
                map[name] = url
            }
        }
        return map
    }

    private suspend fun fetch7TVChannel(channelId: String): Map<String, String> {
        val body = get("https://7tv.io/v3/users/twitch/$channelId") ?: return emptyMap()
        val root = JSONObject(body)
        val emoteSet = root.optJSONObject("emote_set") ?: return emptyMap()
        val emotes = emoteSet.optJSONArray("emotes") ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        for (i in 0 until emotes.length()) {
            val obj = emotes.getJSONObject(i)
            val name = obj.getString("name")
            val host = obj.getJSONObject("data").getJSONObject("host")
            val url = "https:${host.getString("url")}/1x.webp"
            map[name] = url
        }
        return map
    }

    private suspend fun fetchBTTVChannel(channelId: String): Map<String, String> {
        val body = get("https://api.betterttv.net/3/cached/users/twitch/$channelId") ?: return emptyMap()
        val root = JSONObject(body)
        val map = mutableMapOf<String, String>()
        listOf("channelEmotes", "sharedEmotes").forEach { key ->
            val arr = root.optJSONArray(key) ?: return@forEach
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val code = obj.getString("code")
                map[code] = "https://cdn.betterttv.net/emote/$id/1x"
            }
        }
        return map
    }

    private suspend fun fetchFFZChannel(channelName: String): Map<String, String> {
        val body = get("https://api.frankerfacez.com/v1/room/$channelName") ?: return emptyMap()
        val root = JSONObject(body)
        val sets = root.getJSONObject("sets")
        val map = mutableMapOf<String, String>()
        sets.keys().forEach { setKey ->
            val setObj = sets.getJSONObject(setKey)
            val emoticons = setObj.optJSONArray("emoticons") ?: return@forEach
            for (i in 0 until emoticons.length()) {
                val em = emoticons.getJSONObject(i)
                val name = em.getString("name")
                val url = "https:${em.getJSONObject("urls").getString("1")}"
                map[name] = url
            }
        }
        return map
    }
}