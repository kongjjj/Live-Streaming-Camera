package com.kongjjj.livestreamingcamera

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        // 處理背景服務開關
        setupBackgroundServiceSwitch()
    }

    private fun setupBackgroundServiceSwitch() {
        val backgroundSwitch = findPreference<SwitchPreference>("background_service_enabled")
        backgroundSwitch?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            val context = requireContext()
            val intent = Intent(context, MyPersistentService::class.java)

            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.stopService(intent)
            }
            true
        }
    }
}