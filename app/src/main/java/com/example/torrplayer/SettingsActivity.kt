package com.example.torrplayer

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.torrplayer.prefs.AppPrefs

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = TorrPlayerApplication.prefs

        val radioGroup = findViewById<RadioGroup>(R.id.radio_decoder_group)
        val radioHw = findViewById<RadioButton>(R.id.radio_hw)
        val radioSw = findViewById<RadioButton>(R.id.radio_sw)

        if (prefs.decoderMode == 0) {
            radioHw.isChecked = true
        } else {
            radioSw.isChecked = true
        }

        findViewById<Button>(R.id.btn_save_settings).setOnClickListener {
            val selectedId = radioGroup.checkedRadioButtonId
            val mode = if (selectedId == R.id.radio_hw) 0 else 1
            prefs.decoderMode = mode
            prefs.clearDecoderFallback()
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
