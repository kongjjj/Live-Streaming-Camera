package com.kongjjj.livestreamingcamera

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference

class CustomSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.seekBarPreferenceStyle
) : SeekBarPreference(context, attrs, defStyleAttr) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val titleView = holder.findViewById(android.R.id.title) as? TextView
        titleView?.apply {
            textSize = 12f // 字體縮小
            translationX = 20f.dpToPx(context) // 向右移動
        }
    }

    private fun Float.dpToPx(context: Context): Float {
        return this * context.resources.displayMetrics.density
    }
}