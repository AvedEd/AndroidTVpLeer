package com.example.torrplayer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.torrplayer.databinding.ActivitySettingsBinding
import com.example.torrplayer.prefs.AppPrefs
import com.example.torrplayer.torrserver.TorrServerClient
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

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
        binding.spinnerSeek.setSelection(currentIndex.coerceAtMost(seekOptions.lastIndex))

        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }
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

    private fun checkForUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
            if (release == null) {
                runOnUiThread {
                    binding.btnCheckUpdate.isEnabled = true
                    Toast.makeText(this@SettingsActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val latestBuild = release.tagName.toBuildNumber()
            val currentBuild = BuildConfig.VERSION_CODE

            if (latestBuild <= currentBuild) {
                runOnUiThread {
                    binding.btnCheckUpdate.isEnabled = true
                    Toast.makeText(this@SettingsActivity, R.string.update_latest, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val apkUrl = release.apkUrl() ?: run {
                runOnUiThread {
                    binding.btnCheckUpdate.isEnabled = true
                    Toast.makeText(this@SettingsActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            runOnUiThread {
                binding.btnCheckUpdate.isEnabled = true
                startActivity(
                    Intent(this@SettingsActivity, UpdateActivity::class.java).apply {
                        putExtra(UpdateActivity.EXTRA_APK_URL, apkUrl)
                        putExtra(UpdateActivity.EXTRA_RELEASE_TAG, release.tagName ?: "новая версия")
                    }
                )
            }
        }
    }

    private data class GitHubRelease(
        val tag_name: String?,
        val assets: List<GitHubAsset> = emptyList()
    ) {
        fun apkUrl(): String? = assets.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }?.browser_download_url
    }

    private data class GitHubAsset(
        val name: String?,
        val browser_download_url: String?
    )

    private fun fetchLatestRelease(): GitHubRelease? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/AvedEd/Xer/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "TorrPlayer-Android")
            .build()

        val response = OkHttpClient().newCall(request).execute()
        if (!response.isSuccessful || response.body == null) return null

        val json = response.body?.string() ?: return null
        return Gson().fromJson(json, GitHubRelease::class.java)
    }

    private fun String.toBuildNumber(): Int {
        val match = Regex("\\d+").find(this)
        return match?.value?.toIntOrNull() ?: 0
    }
}
