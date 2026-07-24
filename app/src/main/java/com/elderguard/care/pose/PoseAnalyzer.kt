package com.elderguard.care.pose

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.sqrt

class PoseAnalyzer private constructor(private val context: Context) {

    private val tag = "PoseAnalyzer"
    private var poseLandmarker: PoseLandmarker? = null

    // 跌倒检测状态
    private var wasStanding = false
    private var fallStartTime = 0L
    private var isFallDetected = false

    // 静止检测状态
    private val landmarkHistory = ArrayDeque<List<NormalizedLandmark>>()
    private val maxHistorySize = 30
    private var stillnessStartTime = 0L
    private var isStillnessDetected = false

    // 加速度计数据源（跌倒融合判定）
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastSvm: Float = 0f          // 最近一次合加速度（Sum Vector Magnitude）
    private var fallCandidate: Boolean = false  // 加速度候选标志
    private val SVM_FALL_THRESHOLD = 2.0f    // 2g，候选阈值
    private val SVM_NORMAL_THRESHOLD = 1.5f  // 恢复阈值

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                // SVM = sqrt(x^2 + y^2 + z^2) / g，单位 g
                val g = SensorManager.STANDARD_GRAVITY
                lastSvm = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / g
                if (lastSvm > SVM_FALL_THRESHOLD) {
                    fallCandidate = true
                } else if (lastSvm < SVM_NORMAL_THRESHOLD) {
                    // 恢复正常，候选标志保持（等姿态判定确认）
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    var onFallDetected: (() -> Unit)? = null
    var onStillnessDetected: (() -> Unit)? = null
    var onStateChange: ((String) -> Unit)? = null
    var onCaptureEvidence: ((String) -> Unit)? = null  // type = "fall" / "stillness"

    companion object {
        @Volatile private var instance: PoseAnalyzer? = null
        fun getInstance(context: Context): PoseAnalyzer =
            instance ?: synchronized(this) {
                instance ?: PoseAnalyzer(context.applicationContext).also { instance = it }
            }
    }

    /**
     * 启动加速度监听（在 Service startMonitoring 时调用）
     */
    fun startAccelerometer(context: Context) {
        if (sensorManager == null) {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        accelerometer?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    /**
     * 停止加速度监听（在 Service stopMonitoring 时调用）
     */
    fun stopAccelerometer() {
        sensorManager?.unregisterListener(sensorListener)
        fallCandidate = false
        lastSvm = 0f
    }

    fun init(): Boolean {
        return try {
            val baseOptions = com.google.mediapipe.tasks.core.BaseOptions.builder()
                .setModelAssetPath("pose_landmarker.task")
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(1)
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            Log.i(tag, "✅ PoseLandmarker initialized")
            true
        } catch (e: Exception) {
            Log.e(tag, "❌ Failed to init PoseLandmarker", e)
            poseLandmarker = null
            false
        }
    }

    fun processBitmap(bitmap: Bitmap) {
        if (poseLandmarker == null) return
        try {
            val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
            val result = poseLandmarker?.detect(mpImage)
            if (result != null && result.landmarks().isNotEmpty()) {
                val landmarks = result.landmarks()[0]
                analyzeResult(landmarks)
            }
        } catch (e: Exception) {
            Log.e(tag, "Detection error", e)
        }
    }

    private fun analyzeResult(landmarks: List<NormalizedLandmark>) {
        if (landmarks.size < 33) return

        // 关键点
        val nose = landmarks[0]
        val leftShoulder = landmarks[11]
        val rightShoulder = landmarks[12]
        val leftHip = landmarks[23]
        val rightHip = landmarks[24]
        val leftAnkle = landmarks[27]
        val rightAnkle = landmarks[28]

        // 计算身体边界框
        val allY = landmarks.map { it.y() }
        val allX = landmarks.map { it.x() }
        val minY = allY.min()
        val maxY = allY.max()
        val minX = allX.min()
        val maxX = allX.max()

        val bodyHeight = maxY - minY
        val bodyWidth = maxX - minX
        val aspectRatio = if (bodyHeight > 0) bodyWidth / bodyHeight else 0f

        // 跌倒检测：身体宽高比 > 0.6 表示水平躺倒
        val isLyingDown = aspectRatio > 0.6f
        val isStanding = aspectRatio < 0.4f && bodyHeight > 0.3f

        // 融合加速度：必须满足 (姿态躺下 AND 加速度有候选) 或 (姿态躺下 AND 持续超时)
        // 加速度候选在 SVM > 2g 时置位，给姿态判定一个时间窗
        val hasFallCandidate = fallCandidate || lastSvm > SVM_FALL_THRESHOLD

        if (isStanding) {
            wasStanding = true
            if (fallStartTime > 0) {
                fallStartTime = 0
                onStateChange?.invoke("站立")
            }
        }

        if (isLyingDown && wasStanding && fallStartTime == 0L) {
            fallStartTime = System.currentTimeMillis()
            onStateChange?.invoke("疑似跌倒")
        }

        if (isLyingDown && fallStartTime > 0) {
            val duration = System.currentTimeMillis() - fallStartTime
            // 加速度候选存在时缩短窗口为 3s，否则拉长至 8s 降低误报
            val fallDurationMs = if (hasFallCandidate) 3000L else 8000L
            if (duration > fallDurationMs && !isFallDetected) {
                isFallDetected = true
                fallCandidate = false  // 消费候选
                onFallDetected?.invoke()
                onCaptureEvidence?.invoke("fall")
                onStateChange?.invoke("⚠ 跌倒！")
            }
        }

        // 静止检测
        landmarkHistory.addLast(landmarks)
        while (landmarkHistory.size > maxHistorySize) {
            landmarkHistory.removeFirst()
        }

        if (landmarkHistory.size >= maxHistorySize) {
            val movement = calculateMovement()
            if (movement < 0.005f) {
                if (stillnessStartTime == 0L) {
                    stillnessStartTime = System.currentTimeMillis()
                }
            } else {
                stillnessStartTime = 0
                if (isStillnessDetected) {
                    isStillnessDetected = false
                }
            }
        }
    }

    private fun calculateMovement(): Float {
        if (landmarkHistory.size < 2) return 0f
        // 计算所有相邻帧的位移总和 + 总体方差
        var totalMovement = 0f
        var frameCount = 0
        val prev = landmarkHistory.first()
        for (i in 1 until landmarkHistory.size) {
            val curr = landmarkHistory[i]
            var frameMovement = 0f
            for (j in curr.indices) {
                val dx = curr[j].x() - prev[j].x()
                val dy = curr[j].y() - prev[j].y()
                frameMovement += sqrt(dx * dx + dy * dy)
            }
            totalMovement += frameMovement
            frameCount++
        }
        // 平均每帧位移
        return if (frameCount > 0) totalMovement / frameCount else 0f
    }

    fun checkStillness(thresholdMinutes: Int): Boolean {
        if (stillnessStartTime == 0L) return false
        val elapsed = System.currentTimeMillis() - stillnessStartTime
        if (elapsed > thresholdMinutes * 60_000L && !isStillnessDetected) {
            isStillnessDetected = true
            onStillnessDetected?.invoke()
            return true
        }
        return false
    }

    fun resetFallDetection() {
        isFallDetected = false
        fallStartTime = 0
        fallCandidate = false
        resetStillness()
    }

    fun resetStillness() {
        isStillnessDetected = false
        stillnessStartTime = 0
        landmarkHistory.clear()
    }

    fun release() {
        poseLandmarker?.close()
        poseLandmarker = null
    }
}
