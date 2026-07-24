package com.elderguard.care.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.elderguard.care.data.AppConfig

/**
 * 邮件告警通道（Intent 方式，零依赖）
 *
 * 设计取舍：
 * - 不引入 JavaMail 等重型 SMTP 库（避免依赖膨胀和密钥管理复杂度）
 * - 通过 ACTION_SENDTO 启动系统邮件客户端，预填收件人/主题/正文
 * - 老人跌倒场景下 SMS+电话已足够紧急；邮件作为"留证+通知非紧急联系人"的补充
 * - 家属收到 SMS 后可主动打开邮件查看详细情况
 *
 * 使用方式：
 *   EmailAlerter.sendAlert(context, "跌倒", "检测到老人跌倒，时间 2026-07-25 10:30")
 */
object EmailAlerter {

    private const val TAG = "AnNest"

    /**
     * 发送告警邮件（启动邮件客户端，预填内容）
     *
     * @param context 上下文
     * @param alertType 告警类型（跌倒/语音求救/长时间静止）
     * @param detail 详情
     * @return Boolean 是否成功启动邮件客户端（true=有邮件应用可处理，false=无邮件应用）
     */
    fun sendAlert(context: Context, alertType: String, detail: String): Boolean {
        return try {
            val config = AppConfig.getInstance(context)
            val contacts = config.getContacts()

            // 收件人：所有联系人的邮箱（如果 Contact 有 email 字段）
            // 注意：当前 Contact 数据类可能只有 name/phone，无 email 字段
            // 降级方案：不预设收件人，让用户手动填写
            val recipientEmail = getRecipientEmail(contacts)

            val timestamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA
            ).format(java.util.Date())

            val subject = "【安巢】紧急告警：$alertType"
            val body = buildString {
                appendLine("安巢紧急告警通知")
                appendLine()
                appendLine("告警类型：$alertType")
                appendLine("告警时间：$timestamp")
                appendLine("详情：$detail")
                appendLine()
                appendLine("设备信息：")
                appendLine("- 应用：安巢 AnNest v${getAppVersion(context)}")
                appendLine("- 本告警由端侧 AI 自动检测触发，请尽快确认老人安全")
                appendLine()
                appendLine("—— 安巢 AnNest · 端侧视觉智能老人监护")
            }

            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                if (recipientEmail.isNotEmpty()) {
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(recipientEmail))
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // 检查是否有邮件应用可处理
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Log.i(TAG, "📧 邮件告警已启动客户端：$alertType")
                true
            } else {
                Log.w(TAG, "📧 设备未安装邮件客户端，跳过邮件告警")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "📧 邮件告警失败：${e.message}", e)
            false
        }
    }

    /**
     * 获取收件人邮箱
     *
     * 当前 Contact 数据类可能没有 email 字段。此方法尝试反射获取，
     * 失败则返回空字符串（用户手动填写）。
     * 后续如果 Contact 增加 email 字段，直接改这里即可。
     */
    private fun getRecipientEmail(contacts: List<AppConfig.Contact>): String {
        return try {
            // 尝试通过反射读取 email 字段（向前兼容）
            if (contacts.isEmpty()) return ""
            val firstContact = contacts.first()
            val emailField = firstContact.javaClass.getDeclaredField("email")
            emailField.isAccessible = true
            val email = emailField.get(firstContact) as? String
            email?.takeIf { it.isNotEmpty() } ?: ""
        } catch (_: NoSuchFieldException) {
            // Contact 无 email 字段，返回空
            ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 获取应用版本名
     */
    private fun getAppVersion(context: Context): String {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
