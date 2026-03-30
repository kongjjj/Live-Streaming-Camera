package com.kongjjj.livestreamingcamera

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat

class VersionInfoFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_version_info, rootKey)
    }
}