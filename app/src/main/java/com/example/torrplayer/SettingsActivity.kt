package com.example.torrplayer

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.torrplayer.databinding.ActivitySettingsBinding
import com.example.torrplayer.prefs.AppPrefs
import com.example.torrplayer.torrserver.TorrServerClient
import com.example.torrplayer.update.UpdateManager
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs

    private val seekOptions = listOf(1, 2, 3, 5, 10, 15, 30, 60)
    private val charsetOptions = listOf(null, "UTF-8", "windows-1251", "koi8-r", "cp866")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = TorrPlayerApplication.prefs

        binding.editHost.setText(prefs.serverHost.removePrefix("http://").removePrefix("https://"))
        binding.checkBuffer.isChecked = prefs.showBufferOverlay
        binding.checkResume.isChecked = prefs.resumePlayback
        binding.checkAutoNext.isChecked = prefs.autoNextEpisode
        binding.checkTunneling.isChecked = prefs.tunnelingEnabled

        val audioAdapter = ArrayAdapter.createFromResource(
            this, R.array.audio_decode_mode_options, android.R.layout.simple_spinner_item
        )
        audioAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAudioMode.adapter = audioAdapter
        binding.spinnerAudioMode.setSelection(prefs.audioDecodeMode.coerceIn(0, 2))

        val charsetAdapter = ArrayAdapter.createFromResource(
            this, R.array.subtitle_charset_options, android.R.layout.simple_spinner_item
        )
        charsetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSubtitleCharset.adapter = charsetAdapter
        binding.spinnerSubtitleCharset.setSelection(
            charsetOptions.indexOf(prefs.subtitleCharset).coerceAtLeast(0)
        )

        val seekAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, seekOptions.map { "$it сек" })
        seekAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSeek.adapter = seekAdapter
        binding.spinnerSeek.setSelection(seekOptions.indexOf(prefs.seekStepSeconds).coerceAtLeast(0))

        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }
        binding.btnSave.setOnClickListener { save() }
    }

    private fun testConnection() {
        val host = binding.editHost.text.toString().trim()
        if (host.isEmpty()) return
        prefs.serverHost = "http://$host"

        lifecycleScope.launch {
            val client = TorrServerClient(host)
            val ok = client.ping()
            Toast.makeText(
                this@SettingsActivity,
                if (ok) R.string.connection_ok else R.string.connection_fail,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun checkForUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        lifecycleScope.launch {
            val updateManager = UpdateManager(this@SettingsActivity)
            val update = updateManager.checkForUpdates()
            binding.btnCheckUpdate.isEnabled = true

            if (update == null) {
                Toast.makeText(this@SettingsActivity, R.string.no_update, Toast.LENGTH_LONG).show()
                return@launch
            }

            Toast.makeText(
                this@SettingsActivity,
                "Доступно обновление ${update.versionName}, начинаю загрузку…",
                Toast.LENGTH_LONG
            ).show()
            updateManager.downloadUpdate(update)
        }
    }

    private fun save() {
        val host = binding.editHost.text.toString().trim()
        prefs.serverHost = if (host.isEmpty()) prefs.serverHost else "http://$host"
        prefs.seekStepSeconds = seekOptions[binding.spinnerSeek.selectedItemPosition]
        prefs.showBufferOverlay = binding.checkBuffer.isChecked
        prefs.resumePlayback = binding.checkResume.isChecked
        prefs.autoNextEpisode = binding.checkAutoNext.isChecked
        prefs.tunnelingEnabled = binding.checkTunneling.isChecked
        prefs.audioDecodeMode = binding.spinnerAudioMode.selectedItemPosition
        prefs.subtitleCharset = charsetOptions[binding.spinnerSubtitleCharset.selectedItemPosition]
        finish()
    }
}
