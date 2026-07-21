package com.example.torrplayer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.torrplayer.databinding.ActivityHomeBinding

/**
 * Стартовый экран приложения (открывается только при запуске самого TorrPlayer
 * с ТВ-лаунчера, например чтобы поменять настройки). Основной сценарий работы
 * — приложение не открывают напрямую: Lampa запускает PlayerActivity сама,
 * как только пользователь выбирает TorrPlayer в списке плееров.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
