package com.example.torrplayer

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.torrplayer.databinding.ActivitySettingsBinding
import com.example.torrplayer.prefs.AppPrefs
import com.example.torrplayer.torrserver.TorrServerClient
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs

    private val seekOptions = listOf(5, 10, 15, 30, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)

        binding.editHost.setText(prefs.serverHost)
        binding.checkBuffer.isChecked = prefs.showBufferOverlay
        binding.checkResume.isChecked = prefs.resumePlayback

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, seekOptions.map { "$it сек" })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSeek.adapter = adapter
        val currentIndex = seekOptions.indexOf(prefs.seekStepSeconds).coerceAtLeast(0)
        binding.spinnerSeek.setSelection(currentIndex)

        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnSave.setOnClickListener { save() }
    }

    private fun testConnection() {
        // Сохраняем адрес перед проверкой, иначе проверка уйдёт по старому хосту.
        val host = binding.editHost.text.toString().trim()
        prefs.serverHost = host
        val client = TorrServerClient(host)
        lifecycleScope.launch {
            val ok = try {
                client.ping()
            } catch (e: Exception) {
                false
            }
            Toast.makeText(
                this@SettingsActivity,
                if (ok) R.string.connection_ok else R.string.connection_fail,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun save() {
        prefs.serverHost = binding.editHost.text.toString().trim()
        prefs.seekStepSeconds = seekOptions[binding.spinnerSeek.selectedItemPosition]
        prefs.showBufferOverlay = binding.checkBuffer.isChecked
        prefs.resumePlayback = binding.checkResume.isChecked
        finish()
    }
}
