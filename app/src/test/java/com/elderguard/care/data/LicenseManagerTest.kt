package com.elderguard.care.data

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * LicenseManager 依赖 Context + SharedPreferences，无法在纯 JVM 单元测试中直接实例化。
 *
 * 本测试覆盖其激活码的纯算法逻辑：
 *  - 格式正则：^EG00-([0-9A-F]{4})-([0-9A-F]{8})$
 *  - 校验和：SHA-256(SALT + payload + SALT) 前 8 位 hex（大写）
 *
 * SALT 取自 LicenseManager 源码（运行时由 charArrayOf('A','n','N','e','s','t','_','2','0','2','6','_','s','a','l','t') 拼接），
 * 在测试中复刻以便离线复现整条生成-校验链路，无需 Android 运行时。
 */
class LicenseManagerTest {

    companion object {
        // 与 LicenseManager.SALT 保持一致
        private const val SALT = "AnNest_2026_salt"

        // 与 LicenseManager.isValidCode 内部正则保持一致
        private val FORMAT_REGEX = Regex("^EG00-([0-9A-F]{4})-([0-9A-F]{8})$")

        private fun sha256(input: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        /** 复刻 LicenseManager.generateCode() 的算法，生成可通过校验的激活码 */
        fun generateValidCode(payload: String = "1A2B"): String {
            val checksum = sha256("$SALT$payload$SALT").take(8).uppercase()
            return "EG00-$payload-$checksum"
        }
    }

    @Test
    fun `valid activation code matches EG00-XXXX-XXXXXXXX format`() {
        val code = generateValidCode("1A2B")
        assertTrue(FORMAT_REGEX.matches(code))
    }

    @Test
    fun `generated checksum equals sha256 of salt plus payload plus salt first 8 hex uppercase`() {
        val payload = "1A2B"
        val code = generateValidCode(payload)
        val match = FORMAT_REGEX.matchEntire(code)!!
        val actualChecksum = match.groupValues[2]
        val expected = sha256("$SALT$payload$SALT").take(8).uppercase()
        assertEquals(expected, actualChecksum)
    }

    @Test
    fun `code with wrong prefix should fail format check`() {
        val code = "EG01-1A2B-3C4D5E6F"
        assertFalse(FORMAT_REGEX.matches(code))
    }

    @Test
    fun `code with too short payload should fail format check`() {
        val code = "EG00-1A2-3C4D5E6F"
        assertFalse(FORMAT_REGEX.matches(code))
    }

    @Test
    fun `code with too short checksum should fail format check`() {
        val code = "EG00-1A2B-3C4D5E6"
        assertFalse(FORMAT_REGEX.matches(code))
    }

    @Test
    fun `code with non-hex characters should fail format check`() {
        // G/H 不是十六进制字符
        val code = "EG00-1G2H-3C4D5E6F"
        assertFalse(FORMAT_REGEX.matches(code))
    }

    @Test
    fun `lowercase code should fail raw format regex before uppercase normalization`() {
        // 注意：LicenseManager.isValidCode 会先 uppercase()，此处仅验证原始正则对大小写的敏感性
        val lowerCode = "eg00-1a2b-3c4d5e6f"
        assertFalse(FORMAT_REGEX.matches(lowerCode))
    }

    @Test
    fun `different payloads produce different checksums`() {
        val code1 = generateValidCode("1A2B")
        val code2 = generateValidCode("3C4D")
        assertNotEquals(code1, code2)
    }

    @Test
    fun `checksum is 8 uppercase hex characters`() {
        val match = FORMAT_REGEX.matchEntire(generateValidCode("ABCD"))!!
        val checksum = match.groupValues[2]
        assertTrue(checksum.length == 8)
        assertTrue(checksum.matches(Regex("^[0-9A-F]{8}$")))
    }
}
