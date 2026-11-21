package com.khader.mawjachat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.khader.mawjachat.R
import com.khader.mawjachat.data.UserDirectory
import com.khader.mawjachat.databinding.ActivityMainBinding
import com.khader.mawjachat.service.ChatService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var users: UserDirectory
    private val REQ_PERMISSIONS = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(BlockPatternDrawable())
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        users = UserDirectory(this)

        checkAndRequestPermissions()

        val prefs = getSharedPreferences("MawjaPrefs", MODE_PRIVATE)
        binding.etSenderName.setText(prefs.getString("last_sender", ""))
        binding.etReceiverName.setText(prefs.getString("last_receiver", ""))
        binding.etSharedCode.setText(prefs.getString("last_code", ""))

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnConnectRoom.setOnClickListener {
            val sender = UserDirectory.normalize(binding.etSenderName.text.toString())
            val receiver = UserDirectory.normalize(binding.etReceiverName.text.toString())
            val code = binding.etSharedCode.text.toString().trim()

            if (code.isEmpty()) {
                Toast.makeText(this, "يرجى إدخال الكود المشترك", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val error = users.validate(sender, receiver)
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                checkAndRequestPermissions()
                Toast.makeText(this, "يرجى منح صلاحية الميكروفون أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            users.saveMyName(sender)
            users.saveContact(receiver)
            prefs.edit()
                .putString("last_sender", sender)
                .putString("last_receiver", receiver)
                .putString("last_code", code)
                .apply()

            startActivity(Intent(this, ChatActivity::class.java).apply {
                putExtra("SENDER_NAME", sender)
                putExtra("RECEIVER_NAME", receiver)
                putExtra("SHARED_CODE", code)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        setupNameSuggestions(binding.etSenderName, users.myNames())
        setupNameSuggestions(binding.etReceiverName, users.contacts())
    }

    private fun setupNameSuggestions(view: AutoCompleteTextView, names: List<String>) {
        view.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, names))
        view.threshold = 0
        view.setDropDownBackgroundResource(R.drawable.bg_card_dark)
        view.setOnClickListener { if (names.isNotEmpty()) view.showDropDown() }
        view.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && names.isNotEmpty()) view.showDropDown() }
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
        }
    }
}
