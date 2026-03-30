package com.kongjjj.livestreamingcamera

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "設定"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            val backgroundSwitch = findPreference<SwitchPreference>("background_service_enabled")
            backgroundSwitch?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                val context = requireContext()
                val intent = Intent(context, MyPersistentService::class.java)

                if (enabled) {
                    // 啟動前景服務
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    // 停止服務
                    context.stopService(intent)
                }
                true
            }
        }
    }
}