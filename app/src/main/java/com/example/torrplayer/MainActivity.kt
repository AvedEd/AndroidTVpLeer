package com.example.torrplayer

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.animation.AnimationUtils
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
    private var currentFocusIndex = 0
    private val buttons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        prefs = TorrPlayerApplication.prefs

        // Собираем все кнопки для управления фокусом
        buttons.add(findViewById(R.id.btn_open_player))
        buttons.add(findViewById(R.id.btn_iptv))
        buttons.add(findViewById(R.id.btn_switch_decoder))
        buttons.add(findViewById(R.id.btn_check_updates))
        buttons.add(findViewById(R.id.btn_settings))

        // Настраиваем эффекты фокуса для каждой кнопки
        buttons.forEach { button ->
            setupFocusEffect(button)
        }

        // Устанавливаем фокус на первую кнопку
        buttons.firstOrNull()?.requestFocus()

        // Обработчики кликов
        findViewById<Button>(R.id.btn_open_player).setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_STREAM_URL, "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
                putExtra(PlayerActivity.EXTRA_TITLE, "Big Buck Bunny")
            }
            startActivity(intent)
        }

        findViewById<Button>(R.id.btn_iptv).setOnClickListener {
            startActivity(Intent(this, IptvActivity::class.java))
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
    }

    /**
     * Настраивает эффект фокуса для кнопки
     */
    private fun setupFocusEffect(button: Button) {
        val focusAnimation = AnimationUtils.loadAnimation(this, R.anim.tv_focus_animation)
        val unfocusAnimation = AnimationUtils.loadAnimation(this, R.anim.tv_unfocus_animation)

        button.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                button.startAnimation(focusAnimation)
                button.setTextColor(resources.getColor(R.color.text_primary))
                // Подсвечиваем кнопку
                button.elevation = 16f
                button.scaleX = 1.05f
                button.scaleY = 1.05f
            } else {
                button.startAnimation(unfocusAnimation)
                button.setTextColor(resources.getColor(R.color.text_secondary))
                button.elevation = 0f
                button.scaleX = 1.0f
                button.scaleY = 1.0f
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveFocus(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveFocus(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // Клик по кнопке в фокусе
                    buttons.getOrNull(currentFocusIndex)?.performClick()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (currentFocusIndex != 0) {
                        moveFocus(-1)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveFocus(direction: Int) {
        val newIndex = (currentFocusIndex + direction).coerceIn(0, buttons.size - 1)
        if (newIndex != currentFocusIndex) {
            currentFocusIndex = newIndex
            buttons[newIndex].requestFocus()
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
