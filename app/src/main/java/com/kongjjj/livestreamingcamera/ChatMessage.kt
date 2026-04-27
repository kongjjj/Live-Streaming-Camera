package com.kongjjj.livestreamingcamera

data class ChatMessage(
    val id: String,
    val sender: String,
    val message: String,
    val color: String = "#FFFFFF",
    val isSystem: Boolean = false,
    val timestamp: Long? = null,
    val badges: List<Badge> = emptyList(),
    val segments: List<MessageSegment> = emptyList()   // 新增：解析後的表情片段
)