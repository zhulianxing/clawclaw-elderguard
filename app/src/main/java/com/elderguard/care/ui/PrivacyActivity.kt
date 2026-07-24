package com.elderguard.care.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.elderguard.care.R

class PrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)
        // 沿用 SettingsActivity 风格：使用默认 ActionBar 显示标题
        title = "隐私政策"
        // 启用 ActionBar 返回箭头
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
