package com.elderguard.care.pose

import android.content.Context
import android.graphics.Bitmap
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
    private val landmarkHistory = ArrayList<List<NormalizedLandmark>>()
    private val maxHistorySize = 30
    private var stillnessStartTime = 0L
    private var isStillnessDetected = false

    var onFallDetected: (() -> Unit)? = null
    var onStillnessDetected: (() -> Unit)? = null
    var onStateChange: ((String) -> Unit)? = null

    companion object {
        @Volatile private var instance: PoseAnalyzer? = null
        fun getInstance(context: Context): PoseAnalyzer =
            instance ?: synchronized(this) {
                instance ?: PoseAnalyzer(context.applicationContext).also { instance = it }
            }
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
            if (duration > 5000 && !isFallDetected) {
                isFallDetected = true
                onFallDetected?.invoke()
                onStateChange?.invoke("⚠ 跌倒！")
            }
        }

        // 静止检测
        landmarkHistory.add(landmarks)
        if (landmarkHistory.size > maxHistorySize) {
            landmarkHistory.removeAt(0)
        }

        if (landmarkHistory.size >= maxHistorySize) {
            val movement = calculateMovement()
            if (movement < 0.01f) {
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
        if (landmarkHistory.size < 2) return 1f

        var totalMovement = 0f
        val first = landmarkHistory.first()
        val last = landmarkHistory.last()

        for (i in first.indices) {
            val dx = last[i].x() - first[i].x()
            val dy = last[i].y() - first[i].y()
            totalMovement += sqrt(dx * dx + dy * dy)
        }

        return totalMovement / first.size
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
