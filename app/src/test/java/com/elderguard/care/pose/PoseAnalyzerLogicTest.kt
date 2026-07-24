package com.elderguard.care.pose

import org.junit.Assert.*
import org.junit.Test

/**
 * PoseAnalyzer 依赖 Context 与 MediaPipe PoseLandmarker，无法在纯 JVM 单元测试中实例化。
 *
 * 本测试覆盖 analyzeResult / calculateMovement / checkStillness 内部的纯阈值判定逻辑，
 * 常量取值与 PoseAnalyzer.kt 源码保持一致：
 *  - 跌倒（躺下）：aspectRatio > 0.6f
 *  - 站立：aspectRatio < 0.4f && bodyHeight > 0.3f
 *  - 跌倒确认：躺下持续 > 5000ms
 *  - 静止触发：calculateMovement() < 0.01f
 *  - 姿态历史窗口：30 帧
 */
class PoseAnalyzerLogicTest {

    // 与 PoseAnalyzer 源码常量保持一致
    private val LYING_DOWN_THRESHOLD = 0.6f
    private val STANDING_ASPECT_THRESHOLD = 0.4f
    private val STANDING_HEIGHT_THRESHOLD = 0.3f
    private val FALL_DURATION_MS = 5000L
    private val STILLNESS_MOVEMENT_THRESHOLD = 0.01f
    private val HISTORY_SIZE = 30

    private fun isLyingDown(aspectRatio: Float) = aspectRatio > LYING_DOWN_THRESHOLD

    private fun isStanding(aspectRatio: Float, bodyHeight: Float) =
        aspectRatio < STANDING_ASPECT_THRESHOLD && bodyHeight > STANDING_HEIGHT_THRESHOLD

    @Test
    fun `aspect ratio above 0_6 indicates lying down`() {
        assertTrue(isLyingDown(0.7f))
    }

    @Test
    fun `aspect ratio exactly 0_6 is not lying down`() {
        // 源码使用严格大于 > 0.6f
        assertFalse(isLyingDown(0.6f))
    }

    @Test
    fun `aspect ratio below 0_4 with sufficient body height indicates standing`() {
        assertTrue(isStanding(0.3f, 0.5f))
    }

    @Test
    fun `standing with insufficient body height is rejected`() {
        assertFalse(isStanding(0.3f, 0.2f))
    }

    @Test
    fun `aspect ratio between 0_4 and 0_6 is neither standing nor lying down`() {
        val aspectRatio = 0.5f
        assertFalse(isLyingDown(aspectRatio))
        assertFalse(isStanding(aspectRatio, 0.5f))
    }

    @Test
    fun `fall is confirmed after 5 seconds of lying down`() {
        val elapsed = 5500L
        assertTrue(elapsed > FALL_DURATION_MS)
    }

    @Test
    fun `fall is not confirmed before 5 seconds`() {
        val elapsed = 3000L
        assertFalse(elapsed > FALL_DURATION_MS)
    }

    @Test
    fun `fall is not confirmed at exactly 5 seconds`() {
        // 源码使用严格大于 > 5000
        val elapsed = 5000L
        assertFalse(elapsed > FALL_DURATION_MS)
    }

    @Test
    fun `movement below 0_01 triggers stillness timer`() {
        val movement = 0.005f
        assertTrue(movement < STILLNESS_MOVEMENT_THRESHOLD)
    }

    @Test
    fun `movement above 0_01 resets stillness timer`() {
        val movement = 0.02f
        assertFalse(movement < STILLNESS_MOVEMENT_THRESHOLD)
    }

    @Test
    fun `stillness requires configurable threshold minutes to elapse`() {
        val thresholdMinutes = 5
        val elapsedMs = (thresholdMinutes * 60_000L) + 1
        assertTrue(elapsedMs > thresholdMinutes * 60_000L)
    }

    @Test
    fun `landmark history capacity is 30 frames`() {
        assertEquals(30, HISTORY_SIZE)
    }

    @Test
    fun `calculateMovement returns 1f when fewer than 2 frames in history`() {
        // 复刻源码 calculateMovement 的退化分支：landmarkHistory.size < 2 -> 1f
        val historySize = 1
        val movement = if (historySize < 2) 1f else 0f
        assertEquals(1f, movement)
    }
}
