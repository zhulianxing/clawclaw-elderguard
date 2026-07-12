package com.elderguard.care.ui

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.elderguard.care.R
import com.elderguard.care.data.AppConfig

class SettingsActivity : AppCompatActivity() {

    private val config by lazy { AppConfig.getInstance(this) }

    private lateinit var swFall: Switch
    private lateinit var swVoice: Switch
    private lateinit var swStillness: Switch
    private lateinit var sbStillness: SeekBar
    private lateinit var tvStillnessValue: TextView
    private lateinit var sbAlertDelay: SeekBar
    private lateinit var tvAlertDelayValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        title = "设置"

        swFall = findViewById(R.id.sw_fall_detection)
        swVoice = findViewById(R.id.sw_voice_detection)
        swStillness = findViewById(R.id.sw_stillness_detection)
        sbStillness = findViewById(R.id.sb_stillness_minutes)
        tvStillnessValue = findViewById(R.id.tv_stillness_value)
        sbAlertDelay = findViewById(R.id.sb_alert_delay)
        tvAlertDelayValue = findViewById(R.id.tv_alert_delay_value)

        // 加载当前配置
        swFall.isChecked = config.isFallDetectionEnabled()
        swVoice.isChecked = config.isVoiceDetectionEnabled()
        swStillness.isChecked = config.isStillnessDetectionEnabled()
        sbStillness.progress = config.getStillnessThresholdMinutes() - 5
        tvStillnessValue.text = "${config.getStillnessThresholdMinutes()} 分钟"
        sbAlertDelay.progress = (config.getAlertDelaySeconds() - 10) / 5
        tvAlertDelayValue.text = "${config.getAlertDelaySeconds()} 秒"

        swFall.setOnCheckedChangeListener { _, checked -> config.setFallDetectionEnabled(checked) }
        swVoice.setOnCheckedChangeListener { _, checked -> config.setVoiceDetectionEnabled(checked) }
        swStillness.setOnCheckedChangeListener { _, checked -> config.setStillnessDetectionEnabled(checked) }

        sbStillness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = progress + 5
                tvStillnessValue.text = "$minutes 分钟"
                config.setStillnessThresholdMinutes(minutes)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        sbAlertDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress * 5 + 10
                tvAlertDelayValue.text = "$seconds 秒"
                config.setAlertDelaySeconds(seconds)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }
}
