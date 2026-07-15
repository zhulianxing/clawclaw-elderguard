package com.elderguard.care.ui

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.elderguard.care.R
import com.elderguard.care.data.AppConfig
import com.elderguard.care.data.LicenseManager
import com.elderguard.care.service.ElderMonitorService

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnContacts: Button
    private lateinit var btnSettings: Button
    private lateinit var btnActivate: Button
    private lateinit var btnLogs: Button
    private lateinit var btnPreview: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLicense: TextView
    private lateinit var tvLog: TextView
    private lateinit var layoutRecentLog: View
    private lateinit var indicatorDot: View
    private lateinit var headerDetails: View
    private lateinit var contentDetails: View
    private lateinit var tvDetailsArrow: TextView
    private var detailsExpanded = false

    private val licenseManager by lazy { LicenseManager.getInstance(this) }
    private val config by lazy { AppConfig.getInstance(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            openCameraPreview()
        } else {
            Toast.makeText(this, "需要所有权限才能正常工作", Toast.LENGTH_LONG).show()
        }
    }

    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == CameraPreviewActivity.RESULT_CONFIRM) {
            startMonitoring()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 注册 UI 刷新广播
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiRefreshReceiver,
                android.content.IntentFilter("com.elderguard.care.REFRESH_UI"),
                android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(uiRefreshReceiver,
                android.content.IntentFilter("com.elderguard.care.REFRESH_UI"))
        }

        initViews()
        updateUI()
        checkLicense()
    }

    private val uiRefreshReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            runOnUiThread { updateUI() }
        }
    }

    private fun initViews() {
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnContacts = findViewById(R.id.btn_contacts)
        btnSettings = findViewById(R.id.btn_settings)
        btnActivate = findViewById(R.id.btn_activate)
        btnLogs = findViewById(R.id.btn_logs)
        btnPreview = findViewById(R.id.btn_preview)
        tvStatus = findViewById(R.id.tv_status)
        tvLicense = findViewById(R.id.tv_license)
        tvLog = findViewById(R.id.tv_log)
        layoutRecentLog = findViewById(R.id.layout_recent_log)
        indicatorDot = findViewById(R.id.indicator_dot)
        headerDetails = findViewById(R.id.header_details)
        contentDetails = findViewById(R.id.content_details)
        tvDetailsArrow = findViewById(R.id.tv_details_arrow)

        headerDetails.setOnClickListener {
            detailsExpanded = !detailsExpanded
            contentDetails.visibility = if (detailsExpanded) View.VISIBLE else View.GONE
            tvDetailsArrow.text = if (detailsExpanded) "▲" else "▼"
        }

        btnStart.setOnClickListener { checkPermissionsAndStart() }
        btnStop.setOnClickListener { stopMonitoring() }

        btnContacts.setOnClickListener {
            startActivity(Intent(this, ContactActivity::class.java))
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnActivate.setOnClickListener { showActivationDialog() }
        btnLogs.setOnClickListener { showEventLogs() }
        btnPreview.setOnClickListener {
            startActivity(Intent(this, CameraPreviewActivity::class.java))
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isEmpty()) {
            openCameraPreview()
        } else {
            permissionLauncher.launch(ungranted.toTypedArray())
        }
    }

    private fun openCameraPreview() {
        if (!licenseManager.isUsable()) {
            Toast.makeText(this, "试用已过期，请激活", Toast.LENGTH_LONG).show()
            showActivationDialog()
            return
        }
        val intent = Intent(this, CameraPreviewActivity::class.java)
        previewLauncher.launch(intent)
    }

    private fun startMonitoring() {
        if (!licenseManager.isUsable()) {
            Toast.makeText(this, "试用已过期，请激活", Toast.LENGTH_LONG).show()
            showActivationDialog()
            return
        }

        val intent = Intent(this, ElderMonitorService::class.java)
        intent.action = ElderMonitorService.ACTION_START
        ContextCompat.startForegroundService(this, intent)

        config.setMonitoringEnabled(true)
        updateUI()
        Toast.makeText(this, "✅ 监护已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        val intent = Intent(this, ElderMonitorService::class.java)
        intent.action = ElderMonitorService.ACTION_STOP
        startService(intent)

        config.setMonitoringEnabled(false)
        updateUI()
        Toast.makeText(this, "🛡 监护已停止", Toast.LENGTH_SHORT).show()
    }

    private fun showActivationDialog() {
        ActivationDialogFragment().show(supportFragmentManager, "activation")
    }

    private fun showEventLogs() {
        val logs = config.getLogs()
        if (logs.isEmpty()) {
            Toast.makeText(this, "暂无事件记录", Toast.LENGTH_SHORT).show()
            return
        }

        val items: Array<String> = logs.takeLast(25).reversed().map { entry ->
            val time = DateFormat.format("MM-dd HH:mm:ss", entry.timestamp)
            val icon = when (entry.type) {
                "alert" -> "⚠️"
                "info" -> "ℹ️"
                else -> "📝"
            }
            "$icon $time ${entry.message}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("📋 事件日志（最近 25 条）")
            .setItems(items, DialogInterface.OnClickListener { _: DialogInterface, _: Int -> })
            .setPositiveButton("清空日志") { _, _ ->
                config.clearLogs()
                updateUI()
                Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun checkLicense() {
        if (!licenseManager.isUsable()) {
            tvLicense.text = "⚠ 试用已过期，请激活"
            tvLicense.setTextColor(getColor(R.color.alert_red))
        } else if (licenseManager.isActivated()) {
            tvLicense.text = "✅ 已激活 · 永久授权"
            tvLicense.setTextColor(getColor(R.color.safe_green))
        } else {
            val days = licenseManager.getRemainingDays()
            tvLicense.text = "试用期剩余 $days 天"
            tvLicense.setTextColor(getColor(R.color.warning_orange))
        }
    }

    private fun updateUI() {
        val monitoring = config.isMonitoringEnabled()
        btnStart.visibility = if (monitoring) View.GONE else View.VISIBLE
        btnStop.visibility = if (monitoring) View.VISIBLE else View.GONE
        btnPreview.visibility = if (monitoring) View.VISIBLE else View.GONE
        tvStatus.text = if (monitoring) "🟢 监护中" else "⚪ 待启动"

        // 状态指示点动画
        val dotDrawable = indicatorDot.background as? GradientDrawable
        dotDrawable?.setColor(
            if (monitoring) getColor(R.color.safe_green) else getColor(R.color.text_hint)
        )

        // 显示最近日志
        val logs = config.getLogs()
        if (logs.isNotEmpty()) {
            val last = logs.last()
            val time = DateFormat.format("MM-dd HH:mm:ss", last.timestamp)
            val prefix = when (last.type) {
                "alert" -> "⚠️"
                else -> "ℹ️"
            }
            tvLog.text = "$prefix $time ${last.message}"
            layoutRecentLog.visibility = View.VISIBLE
        } else {
            layoutRecentLog.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        checkLicense()
    }

    fun onActivated() {
        checkLicense()
        Toast.makeText(this, "✅ 激活成功！感谢购买长者守护", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        try { unregisterReceiver(uiRefreshReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
