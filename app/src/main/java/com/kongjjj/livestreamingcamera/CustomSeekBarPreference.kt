package com.kongjjj.livestreamingcamera

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference

class CustomSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.seekBarPreferenceStyle,
) : SeekBarPreference(context, attrs, defStyleAttr) {

    private var increment: Int = 1

    init {
        // 使用 KTX 擴充函式 Context.withStyledAttributes
        context.withStyledAttributes(attrs, R.styleable.CustomSeekBarPreference, defStyleAttr, 0) {
            increment = getInt(R.styleable.CustomSeekBarPreference_seekBarIncrement, 1)
        }
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
        // 將數值的個位數與十位數歸零 (例如 2375 -> 2300)
        val roundedValue = (value / 100) * 100
        // 必須使用明確的 super.setValue(int) 呼叫，避免 Kotlin 屬性語法 (super.value = ...) 
        // 在某些情況下可能誤觸發 overridden setter 導致 StackOverflowError
        super.setValue(roundedValue)
    }

    private fun Float.dpToPx(context: Context): Float {
        return this * context.resources.displayMetrics.density
    }
}
