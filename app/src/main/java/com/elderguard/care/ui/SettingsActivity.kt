package com.elderguard.care.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.elderguard.care.R
import com.elderguard.care.data.AppConfig
import com.elderguard.care.pose.PoseAnalyzer

class SettingsActivity : AppCompatActivity() {

    private val config by lazy { AppConfig.getInstance(this) }

    private lateinit var swFall: Switch
    private lateinit var swVoice: Switch
    private lateinit var swStillness: Switch
    private lateinit var sbStillness: SeekBar
    private lateinit var tvStillnessValue: TextView
    private lateinit var sbAlertDelay: SeekBar
    private lateinit var tvAlertDelayValue: TextView
    private lateinit var tvPoseState: TextView
    private lateinit var etSmsTemplate: EditText
    private lateinit var btnSaveSms: Button

    // Debounce: delay SharedPreferences writes while user is still dragging
    private val handler = Handler(Looper.getMainLooper())
    private var pendingStillness: Runnable? = null
    private var pendingAlertDelay: Runnable? = null
    private val DEBOUNCE_MS = 300L

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
        tvPoseState = findViewById(R.id.tv_pose_state)
        etSmsTemplate = findViewById(R.id.et_sms_template)
        btnSaveSms = findViewById(R.id.btn_save_sms)

        // 加载配置
        swFall.isChecked = config.isFallDetectionEnabled()
        swVoice.isChecked = config.isVoiceDetectionEnabled()
        swStillness.isChecked = config.isStillnessDetectionEnabled()

        // 静止阈值：5~60分钟，拖动步长1
        sbStillness.max = 55
        sbStillness.progress = config.getStillnessThresholdMinutes() - 5
        tvStillnessValue.text = "${config.getStillnessThresholdMinutes()} 分钟"

        // 告警延迟：10~60秒，拖动步长5
        sbAlertDelay.max = 10
        sbAlertDelay.progress = (config.getAlertDelaySeconds() - 10) / 5
        tvAlertDelayValue.text = "${config.getAlertDelaySeconds()} 秒"

        // SMS 模板
        etSmsTemplate.setText(config.getSmsTemplate())
        btnSaveSms.setOnClickListener {
            val template = etSmsTemplate.text.toString().trim()
            if (template.isNotEmpty()) {
                config.setSmsTemplate(template)
                Toast.makeText(this, "✅ 已保存", Toast.LENGTH_SHORT).show()
            }
        }

        // 开关监听
        swFall.setOnCheckedChangeListener { _, checked ->
            config.setFallDetectionEnabled(checked)
            updatePoseState()
        }
        swVoice.setOnCheckedChangeListener { _, checked ->
            config.setVoiceDetectionEnabled(checked)
        }
        swStillness.setOnCheckedChangeListener { _, checked ->
            config.setStillnessDetectionEnabled(checked)
            updatePoseState()
        }

        // 静止阈值 — debounced to avoid hammering SharedPreferences on every tick
        sbStillness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = progress + 5
                tvStillnessValue.text = "$minutes 分钟"
                pendingStillness?.let { handler.removeCallbacks(it) }
                val r = Runnable { config.setStillnessThresholdMinutes(minutes) }
                pendingStillness = r
                handler.postDelayed(r, DEBOUNCE_MS)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                // Flush immediately when user lifts finger
                pendingStillness?.let { handler.removeCallbacks(it); it.run() }
                pendingStillness = null
            }
        })

        // 告警延迟 — debounced
        sbAlertDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress * 5 + 10
                tvAlertDelayValue.text = "$seconds 秒"
                pendingAlertDelay?.let { handler.removeCallbacks(it) }
                val r = Runnable { config.setAlertDelaySeconds(seconds) }
                pendingAlertDelay = r
                handler.postDelayed(r, DEBOUNCE_MS)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                pendingAlertDelay?.let { handler.removeCallbacks(it); it.run() }
                pendingAlertDelay = null
            }
        })

        updatePoseState()
    }

    private fun updatePoseState() {
        val fall = config.isFallDetectionEnabled()
        val stillness = config.isStillnessDetectionEnabled()

        val state = when {
            fall && stillness -> "🟢 跌倒检测 + 静止检测 已就绪"
            fall -> "🟢 跌倒检测已就绪"
            stillness -> "🟢 静止检测已就绪"
            else -> "⚪ 检测功能已关闭"
        }

        val monitoring = config.isMonitoringEnabled()
        tvPoseState.text = if (monitoring) "$state（运行中）" else "$state（待启动）"
    }

    override fun onResume() {
        super.onResume()
        updatePoseState()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Flush any pending writes before the activity is destroyed
        pendingStillness?.let { handler.removeCallbacks(it); it.run() }
        pendingAlertDelay?.let { handler.removeCallbacks(it); it.run() }
        pendingStillness = null
        pendingAlertDelay = null
    }
}
