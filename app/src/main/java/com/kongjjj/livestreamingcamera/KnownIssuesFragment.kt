package com.kongjjj.livestreamingcamera

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat

class KnownIssuesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_known_issues, rootKey)
    }
}