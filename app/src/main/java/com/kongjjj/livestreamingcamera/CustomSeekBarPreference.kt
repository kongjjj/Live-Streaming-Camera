package com.kongjjj.livestreamingcamera

import android.content.Context
import android.util.AttributeSet
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference

class CustomSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.seekBarPreferenceStyle
) : SeekBarPreference(context, attrs, defStyleAttr) {

    private var increment: Int = 1

    init {
        // 從 XML 讀取 app:seekBarIncrement
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.CustomSeekBarPreference, defStyleAttr, 0)
        increment = typedArray.getInt(R.styleable.CustomSeekBarPreference_seekBarIncrement, 1)
        typedArray.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val titleView = holder.findViewById(android.R.id.title) as? TextView
        titleView?.apply {
            textSize = 12f // 字體縮小
            translationX = 20f.dpToPx(context) // 向右移動
        }

        // SeekBarPreference 的核心邏輯在於調用 callChangeListener 和 persistInt
        // 我們不應該直接覆蓋 OnSeekBarChangeListener，因為這會打破 SeekBarPreference 內部的狀態管理
        // 更好的做法是在 XML 中使用 android:updatesContinuously="true" 並在 setter 中對齊數值
    }

    // 重寫 value setter 來實現 Snap
    override fun setValue(value: Int) {
        val snappedValue = Math.round(value.toFloat() / increment) * increment
        super.setValue(snappedValue)
    }

    private fun Float.dpToPx(context: Context): Float {
        return this * context.resources.displayMetrics.density
    }
}
