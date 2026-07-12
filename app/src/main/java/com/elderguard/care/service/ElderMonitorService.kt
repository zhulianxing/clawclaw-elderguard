package com.elderguard.care.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SmsManager
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
    private val alertCooldownMs = 60_000L // 1分钟冷却

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
                startMonitoring()
            }
            ACTION_STOP -> {
                stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
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

        Log.i(TAG, "✅ Monitoring started")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

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

        // 重置检测状态
        poseAnalyzer.resetFallDetection()
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
                smsManager.sendTextMessage(contact.phone, null, message, null, null)
                Log.i(TAG, "SMS sent to ${contact.name} (${contact.phone})")
            } catch (e: Exception) {
                Log.e(TAG, "SMS failed for ${contact.phone}", e)
            }
        }
    }

    private fun stopMonitoring() {
        voiceDetector.stop()
        poseAnalyzer.release()
        cameraExecutor.shutdown()
        config.setMonitoringEnabled(false)
        Log.i(TAG, "Monitoring stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "长者守护",
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
            .setContentTitle("长者守护")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        stopMonitoring()
    }

    override fun onBind(intent: Intent?): IBinder? = null
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
