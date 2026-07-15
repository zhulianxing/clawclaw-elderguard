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

        recyclerView = findViewById(R.id.rv_contacts)
        fabAdd = findViewById(R.id.fab_add)
        layoutEmpty = findViewById(R.id.layout_empty)

        adapter = ContactAdapter(contacts) { position ->
            deleteContact(position)
        }

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
                if (name.isNotEmpty() && phone.length >= 11) {
                    val contacts = config.getContacts().toMutableList()
                    contacts.add(AppConfig.Contact(name, phone))
                    config.saveContacts(contacts)
                    adapter.updateData(contacts)
                    Toast.makeText(this, "已添加", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "请输入正确的姓名和手机号", Toast.LENGTH_SHORT).show()
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
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<ContactAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: android.widget.TextView = view.findViewById(R.id.tv_contact_name)
        val tvPhone: android.widget.TextView = view.findViewById(R.id.tv_contact_phone)
        val btnDelete: android.widget.ImageButton = view.findViewById(R.id.btn_delete)
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
    }

    override fun getItemCount() = contacts.size

    fun updateData(newContacts: List<AppConfig.Contact>) {
        contacts = newContacts.toMutableList()
        notifyDataSetChanged()
    }
}
