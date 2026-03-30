package com.kongjjj.livestreamingcamera

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class VersionInfoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 鎖定橫屏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, VersionInfoFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "版本資訊"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}