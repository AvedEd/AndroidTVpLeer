package com.example.torrplayer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.torrplayer.databinding.ActivitySettingsBinding
import com.example.torrplayer.prefs.AppPrefs
import com.example.torrplayer.torrserver.TorrServerClient
import com.example.torrplayer.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs

    private val seekOptions = listOf(1, 2, 3, 5, 10, 15, 30, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)

        binding.editHost.setText(prefs.serverHost)
        binding.checkBuffer.isChecked = prefs.showBufferOverlay
        binding.checkResume.isChecked = prefs.resumePlayback
        binding.checkAudioPcm.isChecked = prefs.audioForcePcm

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, seekOptions.map { "$it сек" })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSeek.adapter = adapter
        val currentIndex = seekOptions.indexOf(prefs.seekStepSeconds).coerceAtLeast(0)
        binding.spinnerSeek.setSelection(currentIndex)

        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }
        binding.btnSave.setOnClickListener { save() }
    }

    private fun testConnection() {
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

    private fun checkForUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        val currentCode = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
            else @Suppress("DEPRECATION") info.versionCode
        } catch (e: Exception) {
            0
        }

        lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate(currentCode) }
            binding.btnCheckUpdate.isEnabled = true

            if (update == null) {
                Toast.makeText(this@SettingsActivity, R.string.no_update, Toast.LENGTH_LONG).show()
                return@launch
            }

            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    this@SettingsActivity,
                    "Разрешите установку из TorrPlayer в открывшихся настройках, затем нажмите ещё раз",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return@launch
            }

            Toast.makeText(this@SettingsActivity, "Скачивание обновления ${update.tagName}…", Toast.LENGTH_SHORT).show()
            val file = withContext(Dispatchers.IO) { UpdateChecker.downloadApk(this@SettingsActivity, update.downloadUrl) }
            if (file == null) {
                Toast.makeText(this@SettingsActivity, "Не удалось скачать обновление", Toast.LENGTH_LONG).show()
                return@launch
            }

            val uri = FileProvider.getUriForFile(this@SettingsActivity, "$packageName.fileprovider", file)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(installIntent)
        }
    }

    private fun save() {
        prefs.serverHost = binding.editHost.text.toString().trim()
        prefs.seekStepSeconds = seekOptions[binding.spinnerSeek.selectedItemPosition]
        prefs.showBufferOverlay = binding.checkBuffer.isChecked
        prefs.resumePlayback = binding.checkResume.isChecked
        prefs.audioForcePcm = binding.checkAudioPcm.isChecked
        finish()
    }
}
