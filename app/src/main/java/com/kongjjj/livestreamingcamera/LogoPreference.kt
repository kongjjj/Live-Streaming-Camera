package com.kongjjj.livestreamingcamera

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

class LogoPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    init {
        layoutResource = R.layout.preference_logo
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val imageView = holder.findViewById(R.id.logo_image) as? ImageView
        imageView?.setImageResource(R.mipmap.ic_launcher) // 使用應用程式圖示
    }
}