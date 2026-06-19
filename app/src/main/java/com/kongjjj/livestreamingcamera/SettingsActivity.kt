package com.kongjjj.livestreamingcamera

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        private val dirPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                val context = requireContext()
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val prefKey = getString(R.string.file_name_key)
                preferenceManager.sharedPreferences?.edit()?.putString(prefKey, it.toString())?.apply()
                findPreference<Preference>(prefKey)?.summary = it.path
            }
        }

        private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            uri?.let { exportSettings(it) }
        }

        private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importSettings(it) }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            val endpointTypePref = findPreference<androidx.preference.ListPreference>(getString(R.string.endpoint_type_key))
            endpointTypePref?.setOnPreferenceChangeListener { preference, newValue ->
                val oldValue = (preference as androidx.preference.ListPreference).value
                if (oldValue != newValue) {
                    androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("更改推流端點")
                        .setMessage("更改端點後，程式將會重新啟動。確定要繼續嗎？")
                        .setPositiveButton("確定") { _, _ ->
                            // 使用 SharedPreferences 強制即時儲存（commit），確保重啟後讀取正確
                            preference.sharedPreferences?.edit()?.putString(preference.key, newValue as String)?.commit()
                            restartApp()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    false // 返回 false 以防止自動更改，我們在確定後手動更改並重啟
                } else {
                    true
                }
            }

            val fileLocationPref = findPreference<Preference>(getString(R.string.file_name_key))
            fileLocationPref?.summary = preferenceManager.sharedPreferences?.getString(getString(R.string.file_name_key), "未選擇")
            fileLocationPref?.setOnPreferenceClickListener {
                dirPickerLauncher.launch(null)
                true
            }

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

            findPreference<Preference>("export_settings")?.setOnPreferenceClickListener {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                exportLauncher.launch("livestream_settings_$timestamp.bak")
                true
            }

            findPreference<Preference>("import_settings")?.setOnPreferenceClickListener {
                importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                true
            }
        }

        private fun exportSettings(uri: Uri) {
            try {
                val prefs = preferenceManager.sharedPreferences
                val allEntries = prefs?.all ?: return
                val jsonObject = JSONObject()
                for ((key, value) in allEntries) {
                    jsonObject.put(key, value)
                }

                requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonObject.toString(4).toByteArray())
                }
                Toast.makeText(requireContext(), "設定已匯出", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "匯出失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        private fun importSettings(uri: Uri) {
            try {
                val context = requireContext()
                val stringBuilder = StringBuilder()
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stringBuilder.append(line)
                        }
                    }
                }

                val jsonObject = JSONObject(stringBuilder.toString())
                val prefs = preferenceManager.sharedPreferences ?: return
                val editor = prefs.edit()
                editor.clear() // 清除目前所有設定

                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = jsonObject.get(key)
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is String -> editor.putString(key, value)
                    }
                }
                editor.commit()

                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle("匯入成功")
                    .setMessage("設定已匯入，程式將重新啟動以套用設定。")
                    .setCancelable(false)
                    .setPositiveButton("確定") { _, _ ->
                        restartApp()
                    }
                    .show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "匯入失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        private fun restartApp() {
            val context = requireContext()
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val componentName = intent?.component
            val mainIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(mainIntent)
            Runtime.getRuntime().exit(0)
        }
    }
}