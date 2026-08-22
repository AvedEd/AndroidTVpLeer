package com.example.torrplayer

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.torrplayer.iptv.IptvActivity
import com.example.torrplayer.prefs.AppPrefs
import com.example.torrplayer.update.UpdateManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        prefs = TorrPlayerApplication.prefs

        findViewById<Button>(R.id.btn_open_player).setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_STREAM_URL, "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
                putExtra(PlayerActivity.EXTRA_TITLE, "Big Buck Bunny")
            }
            startActivity(intent)
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btn_check_updates).setOnClickListener {
            checkForUpdates()
        }

        findViewById<Button>(R.id.btn_switch_decoder).setOnClickListener {
            switchDecoder()
        }

        // НОВАЯ КНОПКА ДЛЯ IPTV
        findViewById<Button>(R.id.btn_iptv).setOnClickListener {
            startActivity(Intent(this, IptvActivity::class.java))
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Проверка обновлений...", Toast.LENGTH_SHORT).show()
            val updateManager = UpdateManager(this@MainActivity)
            val updateInfo = updateManager.checkForUpdates()
            if (updateInfo != null) {
                Toast.makeText(
                    this@MainActivity,
                    "Доступно обновление ${updateInfo.versionName}",
                    Toast.LENGTH_LONG
                ).show()
                updateManager.downloadUpdate(updateInfo)
            } else {
                Toast.makeText(this@MainActivity, "Нет доступных обновлений", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchDecoder() {
        val currentMode = prefs.decoderMode
        val newMode = if (currentMode == 0) 1 else 0
        prefs.decoderMode = newMode
        prefs.clearDecoderFallback()
        Toast.makeText(
            this,
            "Декодер: ${if (newMode == 0) "HW (Аппаратный)" else "SW (Программный)"}",
            Toast.LENGTH_SHORT
        ).show()
    }
}
