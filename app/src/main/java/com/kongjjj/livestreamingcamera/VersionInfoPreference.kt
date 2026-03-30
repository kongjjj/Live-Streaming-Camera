package com.kongjjj.livestreamingcamera

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

class VersionInfoPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    init {
        layoutResource = R.layout.pref_version_info_content
        isSelectable = false // 不可點擊
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        // 可以在此處動態設定文字，但這裡直接使用 XML 中的靜態文字即可
        // 如果需要動態取得版本號，可以這樣做：
        val context = holder.itemView.context
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = pInfo.versionName
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toString()
            } else {
                pInfo.versionCode.toString()
            }
            holder.findViewById(R.id.version_value)?.let { (it as TextView).text = versionName }
            holder.findViewById(R.id.build_value)?.let { (it as TextView).text = versionCode }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}