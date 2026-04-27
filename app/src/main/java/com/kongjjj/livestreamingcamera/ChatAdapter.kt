package com.kongjjj.livestreamingcamera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.ReplacementSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val coroutineScope: CoroutineScope,
    private val emoteManager: EmoteManager
) : ListAdapter<ChatMessage, ChatAdapter.ChatViewHolder>(ChatDiffCallback()) {

    private var fontSizeSp = 12f
    private var shadowEnabled = true
    private var shadowRadius = 2f
    private var shadowDx = 1f
    private var shadowDy = 1f
    private val shadowColor = 0xFF000000.toInt()

    // 用來暫存表情圖片的 Drawable（避免重複載入）
    private val emoteDrawableCache = mutableMapOf<String, Drawable>()

    fun setFontSize(sizeSp: Float) {
        fontSizeSp = sizeSp
        notifyItemRangeChanged(0, itemCount)
    }

    fun setShadowEnabled(enabled: Boolean) {
        shadowEnabled = enabled
        notifyItemRangeChanged(0, itemCount)
    }

    fun setShadowRadius(radius: Float) {
        shadowRadius = radius
        notifyItemRangeChanged(0, itemCount)
    }

    fun setShadowDistance(distance: Float) {
        shadowDx = distance
        shadowDy = distance
        notifyItemRangeChanged(0, itemCount)
    }

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTimestamp: TextView = itemView.findViewById(R.id.tv_timestamp)
        val tvSender: TextView = itemView.findViewById(R.id.tv_sender)
        val tvMessage: TextView = itemView.findViewById(R.id.tv_message)

        init {
            tvMessage.movementMethod = LinkMovementMethod.getInstance()
            tvMessage.isClickable = true
            tvMessage.isLongClickable = true
            tvMessage.setLinkTextColor(Color.parseColor("#00DDFF"))
        }

        fun applyShadow(enable: Boolean, radius: Float, dx: Float, dy: Float, color: Int) {
            if (enable) {
                tvTimestamp.setShadowLayer(radius, dx, dy, color)
                tvSender.setShadowLayer(radius, dx, dy, color)
                tvMessage.setShadowLayer(radius, dx, dy, color)
            } else {
                tvTimestamp.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                tvSender.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                tvMessage.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }
    }

    private class SpaceSpan(private val widthPx: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = widthPx
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = getItem(position)

        holder.tvTimestamp.textSize = fontSizeSp * 0.8f
        holder.tvSender.textSize = fontSizeSp
        holder.tvMessage.textSize = fontSizeSp

        holder.applyShadow(shadowEnabled, shadowRadius, shadowDx, shadowDy, shadowColor)

        val timeStr = msg.timestamp?.let { ts ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        } ?: ""
        holder.tvTimestamp.text = timeStr

        val senderColor = try {
            msg.color.toColorInt()
        } catch (_: IllegalArgumentException) {
            Color.WHITE
        }

        val spannable = SpannableStringBuilder()
        val spaceWidthPx = (2 * holder.itemView.context.resources.displayMetrics.density).toInt()

        // Badges
        for (badge in msg.badges) {
            val drawable = ContextCompat.getDrawable(holder.itemView.context, badge.imageResId)
            drawable?.let {
                val textSizePx = holder.tvMessage.textSize
                val height = textSizePx.toInt()
                val width = (height * it.intrinsicWidth / it.intrinsicHeight).toInt()
                it.setBounds(0, 0, width, height)
                val imageSpan = ImageSpan(it, ImageSpan.ALIGN_BASELINE)
                spannable.append("\u200B")
                spannable.setSpan(imageSpan, spannable.length - 1, spannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.append("\u200B")
                spannable.setSpan(SpaceSpan(spaceWidthPx), spannable.length - 1, spannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // 發送者名稱
        val senderStart = spannable.length
        spannable.append("${msg.sender}: ")
        spannable.setSpan(ForegroundColorSpan(senderColor), senderStart, spannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // 訊息內容（表情圖片：非同步載入並更新）
        if (msg.segments.isEmpty()) {
            // 若沒有預解析的 segments，直接顯示原始文字（向後相容）
            spannable.append(msg.message)
            holder.tvSender.visibility = View.GONE
            holder.tvMessage.text = spannable
        } else {
            // 先暫存基礎文字，等載入圖片後再更新
            val tempSpannable = SpannableStringBuilder()
            
            // ⬇️ 修復：在 Emote 模式下也要顯示 Badges 和 Sender
            // 1. Badges
            for (badge in msg.badges) {
                val drawable = ContextCompat.getDrawable(holder.itemView.context, badge.imageResId)
                drawable?.let {
                    val textSizePx = holder.tvMessage.textSize
                    val height = textSizePx.toInt()
                    val width = (height * it.intrinsicWidth / it.intrinsicHeight).toInt()
                    it.setBounds(0, 0, width, height)
                    val imageSpan = ImageSpan(it, ImageSpan.ALIGN_BASELINE)
                    tempSpannable.append("\u200B")
                    tempSpannable.setSpan(imageSpan, tempSpannable.length - 1, tempSpannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    tempSpannable.append("\u200B")
                    tempSpannable.setSpan(SpaceSpan(spaceWidthPx), tempSpannable.length - 1, tempSpannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            // 2. Sender
            val senderStart = tempSpannable.length
            tempSpannable.append("${msg.sender}: ")
            tempSpannable.setSpan(ForegroundColorSpan(senderColor), senderStart, tempSpannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            // 3. Message segments
            var hasEmote = false
            for (segment in msg.segments) {
                when (segment) {
                    is MessageSegment.Text -> tempSpannable.append(segment.text)
                    is MessageSegment.Emote -> {
                        hasEmote = true
                        // 用佔位符（如表情名稱）暫時顯示，之後再替換
                        val placeholder = "[${segment.name}]"
                        tempSpannable.append(placeholder)
                    }
                }
            }
            holder.tvSender.visibility = View.GONE
            holder.tvMessage.text = tempSpannable

            // 非同步載入所有表情圖片，完成後取代佔位符
            if (hasEmote) {
                coroutineScope.launch {
                    val drawableMap = mutableMapOf<String, Drawable?>()
                    for (segment in msg.segments) {
                        if (segment is MessageSegment.Emote) {
                            val drawable = emoteDrawableCache[segment.url] ?: run {
                                val loaded = emoteManager.loadEmoteDrawable(segment.url)
                                if (loaded != null) {
                                    emoteDrawableCache[segment.url] = loaded
                                }
                                loaded
                            }
                            drawableMap[segment.url] = drawable
                        }
                    }
                    withContext(Dispatchers.Main) {
                        // 重新建立完整的 Spannable (含圖片)
                        val finalSpannable = SpannableStringBuilder()
                        // 重新加入 badges 和發送者 (SpannableStringBuilder 較難部分取代，重新建立較穩)
                        
                        // 1. Badges
                        for (badge in msg.badges) {
                            val drawable = ContextCompat.getDrawable(holder.itemView.context, badge.imageResId)
                            drawable?.let {
                                val textSizePx = holder.tvMessage.textSize
                                val height = textSizePx.toInt()
                                val width = (height * it.intrinsicWidth / it.intrinsicHeight).toInt()
                                it.setBounds(0, 0, width, height)
                                val imageSpan = ImageSpan(it, ImageSpan.ALIGN_BASELINE)
                                finalSpannable.append("\u200B")
                                finalSpannable.setSpan(imageSpan, finalSpannable.length - 1, finalSpannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                finalSpannable.append("\u200B")
                                finalSpannable.setSpan(SpaceSpan(spaceWidthPx), finalSpannable.length - 1, finalSpannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                        
                        // 2. Sender
                        val senderStart = finalSpannable.length
                        finalSpannable.append("${msg.sender}: ")
                        finalSpannable.setSpan(ForegroundColorSpan(senderColor), senderStart, finalSpannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                        // 3. Message segments
                        for (segment in msg.segments) {
                            when (segment) {
                                is MessageSegment.Text -> finalSpannable.append(segment.text)
                                is MessageSegment.Emote -> {
                                    val drawable = drawableMap[segment.url] ?: run {
                                        finalSpannable.append("[${segment.name}]")
                                        null
                                    } ?: continue

                                    val textSizePx = holder.tvMessage.textSize
                                    val height = textSizePx.toInt()
                                    val width = (height * drawable.intrinsicWidth / drawable.intrinsicHeight).coerceAtLeast(1)
                                    drawable.setBounds(0, 0, width, height)
                                    val imageSpan = ImageSpan(drawable, ImageSpan.ALIGN_BASELINE)
                                    finalSpannable.append("\u200B")
                                    finalSpannable.setSpan(imageSpan, finalSpannable.length - 1, finalSpannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                            }
                        }
                        holder.tvMessage.text = finalSpannable
                    }
                }
            }
        }

        if (msg.isSystem) {
            holder.tvMessage.setTextColor(Color.LTGRAY)
        } else {
            holder.tvMessage.setTextColor(Color.WHITE)
        }
    }

    class ChatDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean = oldItem == newItem
    }
}