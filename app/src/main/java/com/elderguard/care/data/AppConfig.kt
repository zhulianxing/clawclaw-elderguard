package com.elderguard.care.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class AppConfig private constructor(context: Context) {
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("elder_guard_config", Context.MODE_PRIVATE)
    
    companion object {
        @Volatile private var instance: AppConfig? = null
        fun getInstance(context: Context): AppConfig =
            instance ?: synchronized(this) {
                instance ?: AppConfig(context.applicationContext).also { instance = it }
            }
    }

    data class Contact(val name: String, val phone: String)

    fun getContacts(): List<Contact> {
        val json = prefs.getString("contacts", "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            Contact(obj.getString("name"), obj.getString("phone"))
        }
    }

    fun saveContacts(contacts: List<Contact>) {
        val arr = JSONArray()
        contacts.forEach { c ->
            arr.put(JSONObject().apply {
                put("name", c.name)
                put("phone", c.phone)
            })
        }
        prefs.edit().putString("contacts", arr.toString()).apply()
    }

    fun getSmsTemplate(): String {
        return prefs.getString("sms_template", 
            "【长者守护】紧急告警：检测到家中老人可能发生意外（跌倒/求救/长时间静止），请尽快查看！时间：")
            ?: "【长者守护】紧急告警：检测到家中老人可能发生意外，请尽快查看！"
    }

    fun setSmsTemplate(template: String) {
        prefs.edit().putString("sms_template", template).apply()
    }

    fun getAlertDelaySeconds(): Int = prefs.getInt("alert_delay", 30)
    fun setAlertDelaySeconds(seconds: Int) = prefs.edit().putInt("alert_delay", seconds).apply()

    fun getStillnessThresholdMinutes(): Int = prefs.getInt("stillness_minutes", 10)
    fun setStillnessThresholdMinutes(minutes: Int) = prefs.edit().putInt("stillness_minutes", minutes).apply()

    fun isMonitoringEnabled(): Boolean = prefs.getBoolean("monitoring_enabled", false)
    fun setMonitoringEnabled(enabled: Boolean) = prefs.edit().putBoolean("monitoring_enabled", enabled).apply()

    fun isVoiceDetectionEnabled(): Boolean = prefs.getBoolean("voice_detection", true)
    fun setVoiceDetectionEnabled(enabled: Boolean) = prefs.edit().putBoolean("voice_detection", enabled).apply()

    fun isFallDetectionEnabled(): Boolean = prefs.getBoolean("fall_detection", true)
    fun setFallDetectionEnabled(enabled: Boolean) = prefs.edit().putBoolean("fall_detection", enabled).apply()

    fun isStillnessDetectionEnabled(): Boolean = prefs.getBoolean("stillness_detection", true)
    fun setStillnessDetectionEnabled(enabled: Boolean) = prefs.edit().putBoolean("stillness_detection", enabled).apply()

    // 事件日志
    data class LogEntry(val timestamp: Long, val type: String, val message: String)

    fun addLog(entry: LogEntry) {
        val logs = getLogs().toMutableList()
        logs.add(entry)
        if (logs.size > 100) logs.removeAt(0)
        val arr = JSONArray()
        logs.forEach { l ->
            arr.put(JSONObject().apply {
                put("timestamp", l.timestamp)
                put("type", l.type)
                put("message", l.message)
            })
        }
        prefs.edit().putString("logs", arr.toString()).apply()
    }

    fun getLogs(): List<LogEntry> {
        val json = prefs.getString("logs", "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            LogEntry(obj.getLong("timestamp"), obj.getString("type"), obj.getString("message"))
        }
    }

    fun clearLogs() = prefs.edit().remove("logs").apply()
}
