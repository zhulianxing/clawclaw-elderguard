package com.elderguard.care.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class VoiceDetector private constructor(private val context: Context) {

    private val tag = "VoiceDetector"
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private val keywords = listOf("救命", "帮帮我", "救救我", "来人啊", "摔倒了", "救命啊", "帮忙")

    var onSOSDetected: ((String) -> Unit)? = null

    companion object {
        @Volatile private var instance: VoiceDetector? = null
        fun getInstance(context: Context): VoiceDetector =
            instance ?: synchronized(this) {
                instance ?: VoiceDetector(context.applicationContext).also { instance = it }
            }
    }

    fun start() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(tag, "SpeechRecognizer not available")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                Log.d(tag, "开始识别语音...")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                Log.w(tag, "识别错误: $error")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // 自动重启
                        if (isListening) {
                            restartListening()
                        }
                    }
                    SpeechRecognizer.ERROR_AUDIO -> {
                        if (isListening) restartListening()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (match in matches) {
                        val text = match.lowercase(Locale.getDefault())
                        Log.d(tag, "识别到: $text")
                        for (kw in keywords) {
                            if (text.contains(kw)) {
                                Log.i(tag, "⚠ 检测到求救关键词: $kw")
                                onSOSDetected?.invoke(kw)
                                break
                            }
                        }
                    }
                }
                // 继续监听
                if (isListening) {
                    restartListening()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        isListening = true
        startListening()
        Log.i(tag, "✅ 语音检测已启动")
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(tag, "startListening failed", e)
        }
    }

    private fun restartListening() {
        speechRecognizer?.cancel()
        Thread.sleep(100)
        startListening()
    }

    fun stop() {
        isListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.i(tag, "语音检测已停止")
    }
}
