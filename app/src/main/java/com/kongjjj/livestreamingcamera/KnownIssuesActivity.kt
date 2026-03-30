package com.kongjjj.livestreamingcamera

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class KnownIssuesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, KnownIssuesFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "更新及問題"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}