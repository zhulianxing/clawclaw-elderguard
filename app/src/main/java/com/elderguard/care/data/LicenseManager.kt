package com.elderguard.care.data

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class LicenseManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("elder_guard_license", Context.MODE_PRIVATE)

    companion object {
        // SALT 分片存储，增加反编译静态扫描难度（非终极方案，应后续移入 Native 层）
        private val SALT: String by lazy {
            // 运行时拼接，避免单字符串在反编译后直接可见
            charArrayOf('A','n','N','e','s','t','_','2','0','2','6','_','s','a','l','t').concatToString()
        }
        private const val KEY_ACTIVATED = "activated"
        private const val KEY_ACTIVATION_CODE = "activation_code"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val TRIAL_DAYS = 7
        private const val PRICE = 268

        @Volatile private var instance: LicenseManager? = null
        fun getInstance(context: Context): LicenseManager =
            instance ?: synchronized(this) {
                instance ?: LicenseManager(context.applicationContext).also { instance = it }
            }
    }

    init {
        if (prefs.getLong(KEY_FIRST_LAUNCH, 0) == 0L) {
            prefs.edit().putLong(KEY_FIRST_LAUNCH, System.currentTimeMillis()).apply()
        }
    }

    fun isActivated(): Boolean = prefs.getBoolean(KEY_ACTIVATED, false)

    fun isUsable(): Boolean {
        if (isActivated()) return true
        val firstLaunch = prefs.getLong(KEY_FIRST_LAUNCH, System.currentTimeMillis())
        val elapsed = System.currentTimeMillis() - firstLaunch
        return elapsed < TimeUnit.DAYS.toMillis(TRIAL_DAYS.toLong())
    }

    fun getRemainingDays(): Int {
        if (isActivated()) return Int.MAX_VALUE
        val firstLaunch = prefs.getLong(KEY_FIRST_LAUNCH, System.currentTimeMillis())
        val elapsed = System.currentTimeMillis() - firstLaunch
        val remaining = TRIAL_DAYS - TimeUnit.MILLISECONDS.toDays(elapsed)
        return remaining.coerceAtLeast(0).toInt()
    }

    fun activate(code: String): Boolean {
        if (!isValidCode(code)) return false
        prefs.edit()
            .putBoolean(KEY_ACTIVATED, true)
            .putString(KEY_ACTIVATION_CODE, code)
            .apply()
        return true
    }

    /**
     * 激活码格式: EG00-XXXX-XXXXXXXX
     * 前缀: EG00
     * XXXX: 2位随机hex（大写）+ 2位随机hex（大写）
     * XXXXXXXX: SHA-256(SALT + payload + SALT) 前8位hex
     *
     * 完整验证: SHA-256(SALT + payload + SALT) 前12位hex == checksum
     */
    fun isValidCode(code: String): Boolean {
        val upper = code.uppercase().replace(" ", "")

        // 格式校验: EG00-XXXX-XXXXXXXX
        val regex = Regex("^EG00-([0-9A-F]{4})-([0-9A-F]{8})$")
        val match = regex.matchEntire(upper) ?: return false

        val payload = match.groupValues[1] // XXXX
        val checksum = match.groupValues[2] // XXXXXXXX

        // 计算校验: SHA-256(SALT + payload + SALT) 前8位hex
        val input = "$SALT$payload$SALT"
        val hash = sha256(input)
        val expectedChecksum = hash.take(8).uppercase()

        return checksum == expectedChecksum
    }

    /**
     * 生成激活码（用于调试/销售）
     */
    // 仅供开发测试，发布版本应移除或移入 Native 层
    private fun generateCode(): String {
        val random = (0..0xFFFF).random().toString(16).uppercase().padStart(4, '0')
        val input = "$SALT$random$SALT"
        val hash = sha256(input)
        val checksum = hash.take(8).uppercase()
        return "EG00-$random-$checksum"
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
