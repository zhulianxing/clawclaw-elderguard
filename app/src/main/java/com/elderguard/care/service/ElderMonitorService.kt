package com.elderguard.care.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.SmsManager
import android.media.session.MediaSession
import android.view.KeyEvent
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Lifecycle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.elderguard.care.R
import com.elderguard.care.data.AppConfig
import com.elderguard.care.pose.PoseAnalyzer
import com.elderguard.care.ui.MainActivity
import com.elderguard.care.voice.VoiceDetector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import com.google.common.util.concurrent.ListenableFuture

class ElderMonitorService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        const val ACTION_START = "com.elderguard.care.START"
        const val ACTION_STOP = "com.elderguard.care.STOP"
        private const val CHANNEL_ID = "elder_guard_channel"
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "ElderMonitorService"
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var config: AppConfig
    private lateinit var poseAnalyzer: PoseAnalyzer
    private lateinit var voiceDetector: VoiceDetector

    private var lastAlertTime = 0L
    private var alertCooldownMs = 60_000L // 1分钟冷却
    private var simulateReceiver: SimulateReceiver? = null
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val wakeLockRenewal = object : Runnable {
        override fun run() {
            try {
                if (wakeLock?.isHeld == false) wakeLock?.acquire()
            } catch (_: Exception) {}
            wakeLockHandler.postDelayed(this, 5 * 60 * 1000L) // 每 5 分钟检查续期
        }
    }

    // ── SOS 物理按钮（音量键连按）──
    private var mediaSession: MediaSession? = null
    private var volumePressCount = 0
    private var lastVolumePressTime = 0L
    private val VOLUME_SOS_THRESHOLD = 3  // 连按 3 次音量键触发 SOS
    private val VOLUME_SOS_WINDOW_MS = 2000L  // 2 秒内连按

    override fun onCreate() {
        super.onCreate()
        config = AppConfig.getInstance(this)
        poseAnalyzer = PoseAnalyzer.getInstance(this)
        voiceDetector = VoiceDetector.getInstance(this)
        createNotificationChannel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification("监护运行中"))
                registerSimulateReceiver()
                startMonitoring()
            }
            ACTION_STOP -> {
                unregisterSimulateReceiver()
                stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        // WakeLock 保持 CPU 运行（10分钟超时，参考计数模式）
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ElderGuard::MonitorWakeLock"
        )
        wakeLock?.acquire()
        wakeLockHandler.postDelayed(wakeLockRenewal, 5 * 60 * 1000L)

        // 注册 SMS 状态接收器
        registerSmsReceivers()

        // 初始化姿态分析
        if (config.isFallDetectionEnabled() || config.isStillnessDetectionEnabled()) {
            if (poseAnalyzer.init()) {
                startCamera()
            } else {
                Log.e(TAG, "PoseAnalyzer init failed, vision features disabled")
            }
        }

        // 初始化语音检测
        if (config.isVoiceDetectionEnabled()) {
            voiceDetector.onSOSDetected = { keyword ->
                Log.i(TAG, "SOS detected: $keyword")
                triggerAlert("语音求救: $keyword")
            }
            voiceDetector.start()
        }

        // 设置姿态回调
        poseAnalyzer.onFallDetected = {
            triggerAlert("检测到跌倒")
        }
        poseAnalyzer.onStillnessDetected = {
            triggerAlert("长时间静止")
        }

        // 启动 MediaSession 监听音量键 SOS
        try {
            mediaSession = MediaSession(this, "AnNestSOS").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { triggerSOS() }
                    override fun onPause() { triggerSOS() }
                    override fun onMediaButtonEvent(mediaButtonIntent: android.content.Intent): Boolean {
                        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)
                        if (event != null && event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                            handleVolumePress()
                            return true
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                })
                isActive = true
            }
            android.util.Log.i("AnNest", "🔴 SOS 物理按钮监听已启动（音量上键连按 $VOLUME_SOS_THRESHOLD 次）")
        } catch (e: Exception) {
            android.util.Log.e("AnNest", "MediaSession 启动失败", e)
        }

        Log.i(TAG, "✅ Monitoring started")
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture = future

        future.addListener({
            try {
                // Guard: check lifecycle state before binding
                if (lifecycle.currentState < Lifecycle.State.STARTED) {
                    Log.w(TAG, "Lifecycle already ${lifecycle.currentState}, skipping camera bind")
                    return@addListener
                }

                val cameraProvider = future.get()

                val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(
                        android.util.Size(480, 640),
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    ))
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalysis
                )

                Log.i(TAG, "✅ Camera started")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Camera init failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            // Convert ImageProxy to Bitmap
            val bitmap = imageProxy.toBitmap()
            if (bitmap != null) {
                poseAnalyzer.processBitmap(bitmap)
                
                // 检查静止
                if (config.isStillnessDetectionEnabled()) {
                    val minutes = config.getStillnessThresholdMinutes()
                    poseAnalyzer.checkStillness(minutes)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun triggerAlert(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < alertCooldownMs) {
            Log.i(TAG, "Alert cooldown, skipping: $reason")
            return
        }
        lastAlertTime = now

        Log.w(TAG, "⚠ ALERT: $reason")

        // 记录日志
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        config.addLog(AppConfig.LogEntry(now, "alert", "$reason at $time"))

        // 更新通知
        val notification = createNotification("⚠ $reason")
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)

        // 发送短信
        sendSmsAlerts(reason, time)

        // 发送 SMS 后，自动拨打第一联系人
        try {
            val contacts = config.getContacts()
            val firstContact = contacts.firstOrNull()
            if (firstContact != null) {
                val callIntent = android.content.Intent(android.content.Intent.ACTION_CALL).apply {
                    data = android.net.Uri.parse("tel:${firstContact.phone}")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(callIntent)
                android.util.Log.i("AnNest", "📞 自动拨打紧急联系人: ${firstContact.name} ${firstContact.phone}")
            }
        } catch (e: Exception) {
            android.util.Log.e("AnNest", "拨号失败，降级为仅 SMS", e)
        }

        // 发送邮件告警（补充通道，启动邮件客户端预填内容）
        try {
            EmailAlerter.sendAlert(this, "监护告警", reason)
        } catch (e: Exception) {
            android.util.Log.e("AnNest", "邮件告警失败", e)
        }

        // 刷新主界面
        sendBroadcast(Intent("com.elderguard.care.REFRESH_UI"))

        // 重置检测状态
        poseAnalyzer.resetFallDetection()
    }

    private fun handleVolumePress() {
        val now = System.currentTimeMillis()
        if (now - lastVolumePressTime > VOLUME_SOS_WINDOW_MS) {
            volumePressCount = 1
        } else {
            volumePressCount++
        }
        lastVolumePressTime = now
        android.util.Log.i("AnNest", "🔴 音量键按下 $volumePressCount/$VOLUME_SOS_THRESHOLD")

        if (volumePressCount >= VOLUME_SOS_THRESHOLD) {
            volumePressCount = 0
            triggerSOS()
        }
    }

    /**
     * 老人主动 SOS 求救（音量键连按触发）
     */
    private fun triggerSOS() {
        android.util.Log.w("AnNest", "🆘 老人主动 SOS 触发！")
        // 复用现有告警链路，type=voice 表示语音求救类
        triggerAlert("老人主动 SOS 求救（音量键连按 3 次触发）")
    }

    private fun sendSmsAlerts(reason: String, time: String) {
        val contacts = config.getContacts()
        if (contacts.isEmpty()) {
            Log.w(TAG, "No contacts configured, skipping SMS")
            return
        }

        val template = config.getSmsTemplate()
        val message = "$template$reason，时间：$time"

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        for (contact in contacts) {
            try {
                // Android 10+ 要求必须提供 sentIntent，否则短信会被系统静默丢弃
                val sentIntent = Intent("com.elderguard.care.SMS_SENT").apply {
                    putExtra("contact_name", contact.name)
                    putExtra("contact_phone", contact.phone)
                }
                val sentPendingIntent = PendingIntent.getBroadcast(
                    this,
                    contact.phone.hashCode(),
                    sentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val deliveryIntent = Intent("com.elderguard.care.SMS_DELIVERED").apply {
                    putExtra("contact_name", contact.name)
                    putExtra("contact_phone", contact.phone)
                }
                val deliveryPendingIntent = PendingIntent.getBroadcast(
                    this,
                    contact.phone.hashCode() + 10000,
                    deliveryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                smsManager.sendTextMessage(
                    contact.phone,
                    null,
                    message,
                    sentPendingIntent,
                    deliveryPendingIntent
                )
                Log.i(TAG, "SMS sent to ${contact.name} (${contact.phone})")
            } catch (e: Exception) {
                Log.e(TAG, "SMS failed for ${contact.phone}", e)
            }
        }
    }

    private fun stopMonitoring() {
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (_: Exception) {}
        wakeLockHandler.removeCallbacks(wakeLockRenewal)
        unregisterSmsReceivers()
        voiceDetector.stop()
        poseAnalyzer.release()
        cameraExecutor.shutdown()
        releaseWakeLock()
        config.setMonitoringEnabled(false)
        Log.i(TAG, "Monitoring stopped")
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock release error", e)
        } finally {
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "安巢",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "老人监护告警通知"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("安巢")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel pending camera provider future to prevent lifecycle crash
        cameraProviderFuture?.let {
            if (!it.isDone) {
                it.cancel(true)
                Log.i(TAG, "Camera provider future cancelled")
            }
        }
        cameraProviderFuture = null
        releaseWakeLock()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        stopMonitoring()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── SMS 发送状态接收器 ──
    inner class SmsSentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val name = intent.getStringExtra("contact_name") ?: "unknown"
            val phone = intent.getStringExtra("contact_phone") ?: ""
            when (resultCode) {
                Activity.RESULT_OK ->
                    Log.i(TAG, "✅ SMS sent to $name ($phone)")
                SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
                    Log.e(TAG, "❌ SMS generic failure for $name ($phone), errorCode=$resultCode")
                SmsManager.RESULT_ERROR_NO_SERVICE ->
                    Log.e(TAG, "❌ SMS no service for $name ($phone)")
                SmsManager.RESULT_ERROR_NULL_PDU ->
                    Log.e(TAG, "❌ SMS null PDU for $name ($phone)")
                SmsManager.RESULT_ERROR_RADIO_OFF ->
                    Log.e(TAG, "❌ SMS radio off for $name ($phone)")
                else ->
                    Log.e(TAG, "❌ SMS unknown error for $name ($phone), code=$resultCode")
            }
        }
    }

    inner class SmsDeliveredReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val name = intent.getStringExtra("contact_name") ?: "unknown"
            val phone = intent.getStringExtra("contact_phone") ?: ""
            when (resultCode) {
                Activity.RESULT_OK ->
                    Log.i(TAG, "✅ SMS delivery confirmed for $name ($phone)")
                Activity.RESULT_CANCELED ->
                    Log.w(TAG, "⚠ SMS delivery failed for $name ($phone)")
            }
        }
    }

    private var smsSentReceiver: SmsSentReceiver? = null
    private var smsDeliveredReceiver: SmsDeliveredReceiver? = null

    private fun registerSmsReceivers() {
        smsSentReceiver = SmsSentReceiver()
        smsDeliveredReceiver = SmsDeliveredReceiver()
        val sentFilter = IntentFilter("com.elderguard.care.SMS_SENT")
        val deliveredFilter = IntentFilter("com.elderguard.care.SMS_DELIVERED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, sentFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(smsDeliveredReceiver, deliveredFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsSentReceiver, sentFilter)
            registerReceiver(smsDeliveredReceiver, deliveredFilter)
        }
        Log.i(TAG, "SMS receivers registered")
    }

    private fun unregisterSmsReceivers() {
        smsSentReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        smsDeliveredReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        smsSentReceiver = null
        smsDeliveredReceiver = null
    }

    // ── ADB 模拟测试 ──
    inner class SimulateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra("type")) {
                "fall" -> triggerAlert("检测到跌倒")
                "voice" -> triggerAlert("语音求救: 救命")
                "still" -> triggerAlert("长时间静止")
                "nocd" -> { alertCooldownMs = 0; Log.i(TAG, "冷却已禁用") }
                "cd" -> { alertCooldownMs = 60_000; Log.i(TAG, "冷却已恢复") }
                else -> Log.w(TAG, "未知模拟类型")
            }
        }
    }

    private fun registerSimulateReceiver() {
        simulateReceiver = SimulateReceiver()
        val filter = IntentFilter("com.elderguard.care.SIMULATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(simulateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(simulateReceiver, filter)
        }
        Log.i(TAG, "Simulate receiver registered")
    }

    private fun unregisterSimulateReceiver() {
        simulateReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            simulateReceiver = null
        }
        Log.i(TAG, "Simulate receiver unregistered")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val config = AppConfig.getInstance(context)
            if (config.isMonitoringEnabled()) {
                val serviceIntent = Intent(context, ElderMonitorService::class.java)
                serviceIntent.action = ElderMonitorService.ACTION_START
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
