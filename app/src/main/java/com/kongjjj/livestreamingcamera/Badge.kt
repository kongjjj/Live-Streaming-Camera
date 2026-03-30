package com.kongjjj.livestreamingcamera

import androidx.annotation.DrawableRes

data class Badge(
    val name: String,
    val version: String,
    @DrawableRes val imageResId: Int
)

fun getBadgeImageRes(name: String): Int = when (name) {
    "broadcaster" -> R.drawable.ic_badge_broadcaster
    "moderator"   -> R.drawable.ic_badge_moderator
    "subscriber"  -> R.drawable.ic_badge_subscriber
    "vip"         -> R.drawable.ic_badge_vip
    "bits"        -> R.drawable.ic_badge_bits
    "premium"     -> R.drawable.ic_badge_premium
    "admin"       -> R.drawable.ic_badge_admin
    "staff"       -> R.drawable.ic_badge_staff
    "lead_moderator" -> R.drawable.ic_badge_lead_moderator
    "verified"    -> R.drawable.ic_badge_verified
    "partner"     -> R.drawable.ic_badge_verified
    "bot-badge"   -> R.drawable.ic_badge_bot
    "founder"     -> R.drawable.ic_badge_founders
    "artist"      -> R.drawable.ic_badge_artist
    "no_audio"    -> R.drawable.ic_badge_no_audio
    "no_video"    -> R.drawable.ic_badge_no_video
    "hype-train"  -> R.drawable.ic_badge_hypetrain
    "twitch-recap-2025" -> R.drawable.ic_badge_2025
    "twitch-recap-2024" -> R.drawable.ic_badge_2024
    "twitch-recap-2023" -> R.drawable.ic_badge_2023
    "subtember-2025" -> R.drawable.ic_badge_subtember_2025
    "subtember-2024" -> R.drawable.ic_badge_subtember_2024
    "clip-champ" -> R.drawable.ic_badge_clip_champ
    "clips-leader" -> R.drawable.ic_badge_clips_leader
    "twitch-dj" -> R.drawable.ic_badge_dj
    "user-anniversary" -> R.drawable.ic_badge_user_anniversary
    "turbo" -> R.drawable.ic_badge_turbo
    "global_mod" -> R.drawable.ic_badge_global_mod
    "glitchcon2020" -> R.drawable.ic_badge_glitchcon2020
    "anonymous-cheerer" -> R.drawable.ic_badge_anonymous
    "ambassador" -> R.drawable.ic_badge_ambassador
    "bits-leader" -> R.drawable.ic_badge_bits_leader
    "sub-gifter" -> R.drawable.ic_badge_sub_gifter
    "twitchbot" -> R.drawable.ic_badge_twitchbot
    "share-the-love" -> R.drawable.ic_badge_share_the_love
    else          -> 0
}