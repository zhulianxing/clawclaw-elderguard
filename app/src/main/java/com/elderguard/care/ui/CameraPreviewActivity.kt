package com.elderguard.care.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.elderguard.care.R

/**
 * 启动前摄像头预览 — 用户确认对准后点「开始监护」。
 * 返回值 RESULT_OK 表示确认启动。
 */
class CameraPreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraPreviewActivity"
        const val RESULT_CONFIRM = RESULT_FIRST_USER + 1
    }

    private lateinit var previewView: PreviewView
    private lateinit var lifecycleRegistry: LifecycleRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView)

        // ── 顶部提示条 ──
        val hintBar = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(140)
            ).apply { setMargins(0, dp(48), 0, 0) }
            setBackgroundColor(0xAA_000000.toInt())
        }
        val hintText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(dp(16), 0, 0, 0)
            }
            text = "📷 前置摄像头 — 请确认对准老人活动区域"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
        }
        hintBar.addView(hintText)
        root.addView(hintBar)

        // ── 关闭 X ──
        val closeBtn = ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(56), dp(16), 0)
            }
            setBackgroundResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(0xFFFFFFFF.toInt())
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        root.addView(closeBtn)

        // ── 底部开始按钮 + 说明 ──
        val bottomLayout = android.widget.LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(dp(32), 0, dp(32), dp(80))
            }
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val startBtn = android.widget.Button(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(240), dp(56))
            text = "✅ 开始监护"
            contentDescription = "开始监护"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF_4CAF50.toInt())
            setOnClickListener {
                setResult(RESULT_CONFIRM)
                finish()
            }
        }
        bottomLayout.addView(startBtn)

        val footerText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            text = "画面仅在本地分析 · 不联网 · 不录像\n对准活动区域后点击上方按钮开始监护"
            textSize = 13f
            setTextColor(0xAA_FFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        bottomLayout.addView(footerText)
        root.addView(bottomLayout)

        setContentView(root)
        startCamera()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                // 不用 unbindAll — 保留 ElderMonitorService 的 ImageAnalysis 绑定
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
                Log.i(TAG, "✅ Preview started")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Preview failed", e)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
