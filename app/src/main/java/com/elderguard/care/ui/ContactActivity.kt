package com.elderguard.care.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.elderguard.care.R
import com.elderguard.care.data.AppConfig
import com.elderguard.care.data.LicenseManager
import com.google.android.material.floatingactionbutton.FloatingActionButton

class ContactActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ContactAdapter
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var layoutEmpty: View
    private var contacts: MutableList<AppConfig.Contact> = mutableListOf()
    private val config by lazy { AppConfig.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact)
        // 使用自定义 Toolbar 替代默认 ActionBar
        findViewById<androidx.appcompat.widget.Toolbar?>(R.id.toolbar)?.let {
            setSupportActionBar(it)
            supportActionBar?.setDisplayShowTitleEnabled(false)
        }

        recyclerView = findViewById(R.id.rv_contacts)
        fabAdd = findViewById(R.id.fab_add)
        layoutEmpty = findViewById(R.id.layout_empty)

        adapter = ContactAdapter(
            contacts,
            onDelete = { position -> deleteContact(position) },
            onEdit = { contact, position -> showEditDialog(contact, position) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        fabAdd.setOnClickListener { showAddDialog() }

        val btnFirst = findViewById<android.widget.Button>(R.id.btn_add_first)
        btnFirst.setOnClickListener { showAddDialog() }

        loadContacts()
    }

    override fun onResume() {
        super.onResume()
        loadContacts()
    }

    private fun loadContacts() {
        contacts = config.getContacts().toMutableList()
        adapter.updateData(contacts)
        layoutEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (contacts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun isValidPhone(phone: String): Boolean {
        return android.util.Patterns.PHONE.matcher(phone).matches() &&
            phone.matches(Regex("^1[3-9]\\d{9}$"))
    }

    private fun isValidName(name: String): Boolean {
        // 姓名不能为空、不能为纯数字、长度 2-20
        return name.length in 2..20 && !name.matches(Regex("^\\d+$"))
    }

    private fun showAddDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_name)
        val etPhone = dialogView.findViewById<EditText>(R.id.et_phone)
        etPhone.inputType = InputType.TYPE_CLASS_PHONE

        AlertDialog.Builder(this)
            .setTitle("添加紧急联系人")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val phone = etPhone.text.toString().trim()
                when {
                    name.isEmpty() -> Toast.makeText(this, "请输入姓名", Toast.LENGTH_SHORT).show()
                    !isValidName(name) -> Toast.makeText(this, "姓名需 2-20 字且不能为纯数字", Toast.LENGTH_SHORT).show()
                    !isValidPhone(phone) -> Toast.makeText(this, "请输入有效的手机号（11位数字，1开头）", Toast.LENGTH_SHORT).show()
                    else -> {
                        val contacts = config.getContacts().toMutableList()
                        contacts.add(AppConfig.Contact(name, phone))
                        config.saveContacts(contacts)
                        adapter.updateData(contacts)
                        Toast.makeText(this, "已添加", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditDialog(contact: AppConfig.Contact, position: Int) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_name)
        val etPhone = dialogView.findViewById<EditText>(R.id.et_phone)
        etPhone.inputType = InputType.TYPE_CLASS_PHONE

        etName.setText(contact.name)
        etPhone.setText(contact.phone)

        AlertDialog.Builder(this)
            .setTitle("编辑联系人")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val newName = etName.text.toString().trim()
                val newPhone = etPhone.text.toString().trim()
                when {
                    newName.isEmpty() -> Toast.makeText(this, "请输入姓名", Toast.LENGTH_SHORT).show()
                    !isValidName(newName) -> Toast.makeText(this, "姓名需 2-20 字且不能为纯数字", Toast.LENGTH_SHORT).show()
                    !isValidPhone(newPhone) -> Toast.makeText(this, "请输入有效的手机号（11位数字，1开头）", Toast.LENGTH_SHORT).show()
                    else -> {
                        val contacts = config.getContacts().toMutableList()
                        if (position < contacts.size) {
                            contacts[position] = contact.copy(name = newName, phone = newPhone)
                            config.saveContacts(contacts)
                            adapter.updateData(contacts)
                            Toast.makeText(this, "已更新", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteContact(position: Int) {
        val contacts = config.getContacts().toMutableList()
        contacts.removeAt(position)
        config.saveContacts(contacts)
        adapter.updateData(contacts)
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
    }
}

class ContactAdapter(
    private var contacts: MutableList<AppConfig.Contact>,
    private val onDelete: (Int) -> Unit,
    private val onEdit: (AppConfig.Contact, Int) -> Unit
) : RecyclerView.Adapter<ContactAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: android.widget.TextView = view.findViewById(R.id.tv_contact_name)
        val tvPhone: android.widget.TextView = view.findViewById(R.id.tv_contact_phone)
        val btnDelete: android.widget.ImageButton = view.findViewById(R.id.btn_delete)
        val btnEdit: android.widget.ImageButton = view.findViewById(R.id.btn_edit)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val c = contacts[position]
        holder.tvName.text = c.name
        holder.tvPhone.text = c.phone
        holder.btnDelete.setOnClickListener { onDelete(position) }
        holder.btnEdit.setOnClickListener { onEdit(c, position) }
    }

    override fun getItemCount() = contacts.size

    fun updateData(newContacts: List<AppConfig.Contact>) {
        contacts = newContacts.toMutableList()
        notifyDataSetChanged()
    }
}
