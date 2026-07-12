package com.elderguard.care.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.elderguard.care.R
import com.elderguard.care.data.LicenseManager

class ActivationDialogFragment : DialogFragment() {

    private val licenseManager by lazy { LicenseManager.getInstance(requireContext()) }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_activation, null)
        val etCode = view.findViewById<EditText>(R.id.et_activation_code)

        return AlertDialog.Builder(requireContext())
            .setTitle("输入激活码")
            .setView(view)
            .setMessage("购买激活码请联系客服。¥268 买断，支持所有卷积产品。")
            .setPositiveButton("激活") { _, _ ->
                val code = etCode.text.toString().trim().uppercase()
                if (code.isEmpty()) {
                    Toast.makeText(context, "请输入激活码", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (licenseManager.activate(code)) {
                    Toast.makeText(context, "✅ 激活成功！感谢购买", Toast.LENGTH_LONG).show()
                    (activity as? MainActivity)?.onActivated()
                    dismiss()
                } else {
                    Toast.makeText(context, "❌ 激活码无效，请检查后重试", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .create()
    }
}
