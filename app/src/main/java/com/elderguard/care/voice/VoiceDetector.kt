package com.elderguard.care.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException

class VoiceDetector private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VoiceDetector"
        private const val MODEL_DIR = "vosk-model"
        private const val EXTRACTED_DIR = "vosk-model-cn"

        @Volatile private var instance: VoiceDetector? = null
        fun getInstance(context: Context): VoiceDetector =
            instance ?: synchronized(this) {
                instance ?: VoiceDetector(context.applicationContext).also { instance = it }
            }
    }

    private val keywords = listOf("救命", "帮帮我", "救救我", "来人啊", "摔倒了", "救命啊", "帮忙")

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var isListening = false
    private var job: Job? = null

    var onSOSDetected: ((String) -> Unit)? = null

    fun start() {
        if (isListening) return
        isListening = true

        job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                if (!initModel()) {
                    Log.e(TAG, "Model init failed, voice disabled")
                    withContext(Dispatchers.Main) { isListening = false }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    startRecognition()
                }
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                isListening = false
            }
        }
    }

    private fun initModel(): Boolean {
        if (model != null) return true

        try {
            val modelDir = File(context.filesDir, EXTRACTED_DIR)
            val modelCheckFile = File(modelDir, "am/final.mdl")

            if (!modelCheckFile.exists()) {
                Log.i(TAG, "Extracting Vosk model from assets...")
                if (!extractModel(modelDir)) {
                    Log.e(TAG, "Model extraction failed")
                    return false
                }
            }

            model = Model(modelDir.absolutePath)
            Log.i(TAG, "✅ Vosk model loaded")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Model init error", e)
            return false
        }
    }

    private fun extractModel(targetDir: File): Boolean {
        return try {
            val am = context.assets
            val entries = am.list(MODEL_DIR)
            if (entries.isNullOrEmpty()) {
                Log.e(TAG, "No model files in assets/vosk-model/")
                return false
            }

            targetDir.mkdirs()
            copyAssetsDir(am, MODEL_DIR, targetDir)
            Log.i(TAG, "Model extracted: ${targetDir.absolutePath}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Extract error", e)
            false
        }
    }

    private fun copyAssetsDir(am: android.content.res.AssetManager, path: String, target: File) {
        val files = am.list(path) ?: return
        for (name in files) {
            val subPath = "$path/$name"
            val targetFile = File(target, name)
            val subEntries = am.list(subPath)
            if (subEntries != null && subEntries.isNotEmpty()) {
                targetFile.mkdirs()
                copyAssetsDir(am, subPath, targetFile)
            } else {
                am.open(subPath).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun startRecognition() {
        val m = model ?: run {
            Log.e(TAG, "Model is null")
            return
        }

        try {
            recognizer = Recognizer(m, 16000.0f)
        } catch (e: Exception) {
            Log.e(TAG, "Recognizer creation failed", e)
            return
        }

        try {
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    checkKeyword(hypothesis)
                }

                override fun onResult(hypothesis: String?) {
                    Log.d(TAG, "识别结果: $hypothesis")
                    checkKeyword(hypothesis)
                }

                override fun onFinalResult(hypothesis: String?) {
                    checkKeyword(hypothesis)
                }

                override fun onError(exception: Exception?) {
                    Log.e(TAG, "SpeechService error", exception)
                }

                override fun onTimeout() {
                    Log.d(TAG, "Speech timeout, auto-restarting")
                }
            })

            Log.i(TAG, "✅ Vosk 离线语音识别已启动（关键字: $keywords）")
        } catch (e: Exception) {
            Log.e(TAG, "startRecognition failed, releasing resources", e)
            // Ensure AudioRecord inside SpeechService is released on failure
            try { speechService?.shutdown() } catch (_: Exception) {}
            speechService = null
            try { recognizer?.close() } catch (_: Exception) {}
            recognizer = null
        }
    }

    private fun checkKeyword(hypothesis: String?) {
        if (hypothesis.isNullOrEmpty()) return
        try {
            val json = org.json.JSONObject(hypothesis)
            val text = json.optString(
                "partial",
                json.optString("text", "")
            )
            if (text.isNotEmpty()) {
                Log.d(TAG, "识别到: $text")
                for (kw in keywords) {
                    if (text.contains(kw)) {
                        Log.i(TAG, "⚠ 检测到求救关键词: $kw")
                        onSOSDetected?.invoke(kw)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error", e)
        }
    }

    fun stop() {
        isListening = false
        job?.cancel()
        job = null

        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Exception) {}
        speechService = null

        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null

        try { model?.close() } catch (_: Exception) {}
        model = null

        Log.i(TAG, "Vosk stopped")
    }
}
