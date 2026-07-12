package com.elderguard.care.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
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
    private lateinit var tvStatus: TextView
    private lateinit var tvLicense: TextView
    private lateinit var tvLog: TextView
    private lateinit var cardMonitor: CardView

    private val licenseManager by lazy { LicenseManager.getInstance(this) }
    private val config by lazy { AppConfig.getInstance(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startMonitoring()
        } else {
            Toast.makeText(this, "需要所有权限才能正常工作", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        updateUI()
        checkLicense()
    }

    private fun initViews() {
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnContacts = findViewById(R.id.btn_contacts)
        btnSettings = findViewById(R.id.btn_settings)
        btnActivate = findViewById(R.id.btn_activate)
        tvStatus = findViewById(R.id.tv_status)
        tvLicense = findViewById(R.id.tv_license)
        tvLog = findViewById(R.id.tv_log)
        cardMonitor = findViewById(R.id.card_monitor)

        btnStart.setOnClickListener {
            checkPermissionsAndStart()
        }

        btnStop.setOnClickListener {
            stopMonitoring()
        }

        btnContacts.setOnClickListener {
            startActivity(Intent(this, ContactActivity::class.java))
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnActivate.setOnClickListener {
            showActivationDialog()
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
            startMonitoring()
        } else {
            permissionLauncher.launch(ungranted.toTypedArray())
        }
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
        Toast.makeText(this, "监护已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        val intent = Intent(this, ElderMonitorService::class.java)
        intent.action = ElderMonitorService.ACTION_STOP
        startService(intent)

        config.setMonitoringEnabled(false)
        updateUI()
        Toast.makeText(this, "监护已停止", Toast.LENGTH_SHORT).show()
    }

    private fun showActivationDialog() {
        ActivationDialogFragment().show(supportFragmentManager, "activation")
    }

    private fun checkLicense() {
        if (!licenseManager.isUsable()) {
            tvLicense.text = "⚠ 试用已过期，请激活"
            tvLicense.setTextColor(getColor(R.color.alert_red))
        } else if (licenseManager.isActivated()) {
            val days = licenseManager.getRemainingDays()
            tvLicense.text = "✅ 已激活（正式版）"
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
        tvStatus.text = if (monitoring) "🟢 监护中" else "⚪ 待启动"
        
        // 显示最近日志
        val logs = config.getLogs()
        if (logs.isNotEmpty()) {
            val last = logs.last()
            val time = DateFormat.format("MM-dd HH:mm:ss", last.timestamp)
            tvLog.text = "最近事件：$time ${last.message}"
            tvLog.visibility = View.VISIBLE
        } else {
            tvLog.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        checkLicense()
    }

    fun onActivated() {
        checkLicense()
        Toast.makeText(this, "激活成功！感谢购买长者守护", Toast.LENGTH_LONG).show()
    }
}
