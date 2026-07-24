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
import com.elderguard.care.data.Web3LicenseManager
import com.elderguard.care.service.ElderMonitorService

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnContacts: View
    private lateinit var btnSettings: View
    private lateinit var btnActivate: View
    private lateinit var btnLogs: View
    private lateinit var btnPreview: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLicense: TextView
    private lateinit var tvLog: TextView
    private lateinit var layoutRecentLog: View
    private lateinit var indicatorDot: View
    private lateinit var cardMonitor: View
    private lateinit var headerDetails: View
    private lateinit var contentDetails: View
    private lateinit var tvDetailsArrow: TextView
    private var detailsExpanded = false

    private val REQUEST_CODE_NOTIFICATIONS = 1001

    private val licenseManager by lazy { LicenseManager.getInstance(this) }
    private val web3License by lazy { Web3LicenseManager(this, packageName) }
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
        // 使用自定义 Toolbar 替代默认 ActionBar
        findViewById<androidx.appcompat.widget.Toolbar?>(R.id.toolbar)?.let {
            setSupportActionBar(it)
            supportActionBar?.setDisplayShowTitleEnabled(false)
        }

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

        // Android 13+ 主动请求通知权限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATIONS
                )
            }
        }
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
        cardMonitor = findViewById(R.id.card_monitor)
        headerDetails = findViewById(R.id.header_details)
        contentDetails = findViewById(R.id.content_details)
        tvDetailsArrow = findViewById(R.id.tv_details_arrow)

        headerDetails.setOnClickListener {
            detailsExpanded = !detailsExpanded
            contentDetails.visibility = if (detailsExpanded) View.VISIBLE else View.GONE
            tvDetailsArrow.text = if (detailsExpanded) "收起" else "展开"
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
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE
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
        val options = arrayOf(
            "🌐 前往官网 ClawClaw.tech 激活",
            "🔗 导入钱包验证 NFT (daix.fun)",
            "🔑 输入离线激活码"
        )
        AlertDialog.Builder(this)
            .setTitle("激活安巢")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openWebsite()
                    1 -> showWalletImport()
                    2 -> showCodeInput()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 打开官网 ClawClaw.tech 激活
     */
    private fun openWebsite() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.clawclaw.tech"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "未找到浏览器应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showWalletImport() {
        val input = EditText(this).apply {
            hint = "粘贴钱包私钥（0x开头，64位hex）"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("导入Polygon钱包")
            .setMessage("需要有daix.fun购买的NFT数字凭证\n私钥仅本地存储，不上传")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                val key = input.text.toString().trim()
                if (web3License.importWallet(key)) {
                    Toast.makeText(this, "钱包已导入，正在验证NFT...", Toast.LENGTH_SHORT).show()
                    web3License.verifyLicense { verified, msg, _ ->
                        runOnUiThread {
                            if (verified) {
                                Toast.makeText(this, "✅ $msg", Toast.LENGTH_LONG).show()
                                checkLicense()
                            } else {
                                Toast.makeText(this, "❌ $msg", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    Toast.makeText(this, "私钥格式无效", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCodeInput() {
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
        if (licenseManager.isActivated() || web3License.isVerified()) {
            tvLicense.text = "已激活 · 永久授权"
            tvLicense.setTextColor(getColor(R.color.safe_green))
        } else if (!licenseManager.isUsable()) {
            tvLicense.text = "试用已过期，请激活"
            tvLicense.setTextColor(getColor(R.color.alert_red))
        } else {
            val days = licenseManager.getRemainingDays()
            tvLicense.text = "试用期剩余 $days 天"
            tvLicense.setTextColor(getColor(R.color.warning_orange))
        }
        // 后台链上验证
        if (!web3License.isVerified() && web3License.hasWallet()) {
            web3License.verifyLicense { verified, _, _ ->
                if (verified) runOnUiThread { checkLicense() }
            }
        }
    }

    private fun updateUI() {
        val monitoring = config.isMonitoringEnabled()
        btnStart.visibility = if (monitoring) View.GONE else View.VISIBLE
        btnStop.visibility = if (monitoring) View.VISIBLE else View.GONE
        btnPreview.visibility = if (monitoring) View.VISIBLE else View.GONE
        tvStatus.text = if (monitoring) "监护中" else "待启动"

        // 状态指示点 + 卡片背景（selector 响应 selected 状态）
        indicatorDot.isSelected = monitoring
        cardMonitor.isSelected = monitoring
        // 运行态文字改白色，保证渐变背景对比度
        if (monitoring) {
            tvStatus.setTextColor(getColor(R.color.text_on_primary))
            tvLicense.setTextColor(getColor(R.color.text_on_primary))
        } else {
            tvStatus.setTextColor(getColor(R.color.text_primary))
            tvLicense.setTextColor(getColor(R.color.text_secondary))
        }

        // 显示最近日志
        val logs = config.getLogs()
        if (logs.isNotEmpty()) {
            val last = logs.last()
            val time = DateFormat.format("MM-dd HH:mm:ss", last.timestamp)
            val prefix = when (last.type) {
                "alert" -> "[告警]"
                else -> "[事件]"
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
        Toast.makeText(this, "✅ 激活成功！感谢购买安巢", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        try { unregisterReceiver(uiRefreshReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
