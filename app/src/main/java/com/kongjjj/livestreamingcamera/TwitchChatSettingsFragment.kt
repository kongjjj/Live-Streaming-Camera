package com.kongjjj.livestreamingcamera

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.core.content.edit
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

class TwitchChatSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_twitch_chat, rootKey)

        val clearPreference = findPreference<Preference>("clear_chat_history")
        clearPreference?.setOnPreferenceClickListener {
            // 直接呼叫 MainActivity 的清除方法（如果有的話）
            if (activity is MainActivity) {
                (activity as MainActivity).clearChatHistory()
                // 隱藏設定面板
                (activity as MainActivity).toggleSettingsPanel()
            } else {
                // 原有邏輯：在 SharedPreferences 中設一個標誌，表示需要清除歷史
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                prefs.edit {
                    putBoolean("pending_clear_history", true)
                }
                // 關閉目前 Activity
                activity?.finish()
            }
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 取得列表視圖
        val listView = listView

        // 將 dp 轉換為像素
        val topPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            10f,
            resources.displayMetrics
        ).toInt()
        val rightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            50f,
            resources.displayMetrics
        ).toInt()

        // 設定 padding (左邊維持 0)
        listView.setPadding(0, topPx, rightPx, 0)
        // 允許滾動越過 padding 區域，讓內容平滑顯示
        listView.clipToPadding = false
    }
}
