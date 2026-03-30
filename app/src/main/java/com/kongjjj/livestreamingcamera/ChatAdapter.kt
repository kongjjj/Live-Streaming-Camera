package com.kongjjj.livestreamingcamera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.ChatViewHolder>(ChatDiffCallback()) {

    private var fontSizeSp = 12f
    private var shadowEnabled = true
    private var shadowRadius = 2f
    private var shadowDx = 1f
    private var shadowDy = 1f
    private val shadowColor = 0xFF000000.toInt()

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

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
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

    /**
     * 自訂 ReplacementSpan，用於插入固定寬度的空白，確保在不同螢幕密度下都是準確的 2dp
     */
    private class SpaceSpan(private val widthPx: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = widthPx

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            // 不畫任何東西，只佔位
        }
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

        // 建構包含 badges、發送者名稱和訊息的 SpannableStringBuilder
        val spannable = SpannableStringBuilder()

        // 計算 2dp 的寬度（像素）
        val spaceWidthDp = 2
        val spaceWidthPx = (spaceWidthDp * holder.itemView.context.resources.displayMetrics.density).toInt()

        // 加入 Badge 圖示（每個 badge 後加上 2dp 空白）
        for (badge in msg.badges) {
            val drawable = ContextCompat.getDrawable(holder.itemView.context, badge.imageResId)
            drawable?.let {
                val textSizePx = holder.tvMessage.textSize
                val height = textSizePx.toInt()
                val width = (height * it.intrinsicWidth / it.intrinsicHeight).toInt()
                it.setBounds(0, 0, width, height)
                val imageSpan = ImageSpan(it, ImageSpan.ALIGN_BASELINE)
                spannable.append("\u200B") // 零寬空格，用於放置 ImageSpan
                spannable.setSpan(imageSpan, spannable.length - 1, spannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                // 插入固定寬度的空白
                spannable.append("\u200B")
                spannable.setSpan(SpaceSpan(spaceWidthPx), spannable.length - 1, spannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // 加入發送者名稱（帶顏色）
        val senderStart = spannable.length
        spannable.append("${msg.sender}: ")
        spannable.setSpan(
            ForegroundColorSpan(senderColor),
            senderStart,
            spannable.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // 加入訊息內容
        spannable.append(msg.message)

        // 隱藏原本的 sender TextView，因為發送者名稱已整合到訊息中
        holder.tvSender.visibility = View.GONE
        holder.tvMessage.text = spannable

        if (msg.isSystem) {
            holder.tvMessage.setTextColor(Color.LTGRAY)
        } else {
            holder.tvMessage.setTextColor(Color.WHITE)
        }
    }

    class ChatDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}