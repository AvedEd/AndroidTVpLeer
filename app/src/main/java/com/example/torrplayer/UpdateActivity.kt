package com.example.torrplayer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.torrplayer.databinding.ActivityUpdateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class UpdateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_URL = "extra_apk_url"
        const val EXTRA_RELEASE_TAG = "extra_release_tag"
    }

    private lateinit var binding: ActivityUpdateBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val tag = intent.getStringExtra(EXTRA_RELEASE_TAG) ?: "новая версия"
        binding.textTitle.text = "Обновление доступно"
        binding.textVersion.text = "Версия: $tag"

        binding.btnInstall.setOnClickListener { downloadAndInstall() }
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun downloadAndInstall() {
        val url = intent.getStringExtra(EXTRA_APK_URL) ?: run {
            Toast.makeText(this, R.string.update_download_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.btnInstall.isEnabled = false
        binding.btnCancel.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            val apk = withContext(Dispatchers.IO) { downloadApk(url) }
            if (apk == null) {
                runOnUiThread {
                    binding.btnInstall.isEnabled = true
                    binding.btnCancel.isEnabled = true
                    binding.progressBar.visibility = android.view.View.GONE
                    Toast.makeText(this@UpdateActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            runOnUiThread {
                binding.btnInstall.isEnabled = true
                binding.btnCancel.isEnabled = true
                binding.progressBar.visibility = android.view.View.GONE
                installApk(apk)
            }
        }
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.update_install_denied, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            finish()
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }

    private fun downloadApk(url: String): File? = try {
        val request = Request.Builder().url(url).build()
        val response = OkHttpClient().newCall(request).execute()
        if (!response.isSuccessful || response.body == null) return null

        val target = File(cacheDir, "torrplayer-update.apk")
        target.outputStream().use { out ->
            response.body!!.byteStream().use { input -> input.copyTo(out) }
        }
        target
    } catch (_: Exception) {
        null
    }
}
