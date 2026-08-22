package com.example.torrplayer.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AppPrefs(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("torrplayer_prefs", Context.MODE_PRIVATE)

    // === НАСТРОЙКИ ВОСПРОИЗВЕДЕНИЯ ===
    var seekStepSeconds: Int
        get() = prefs.getInt("seek_step", 10)
        set(value) = prefs.edit { putInt("seek_step", value) }

    var playbackSpeed: Float
        get() = prefs.getFloat("playback_speed", 1.0f)
        set(value) = prefs.edit { putFloat("playback_speed", value) }

    var resizeMode: Int
        get() = prefs.getInt("resize_mode", 0)
        set(value) = prefs.edit { putInt("resize_mode", value) }

    var showBufferOverlay: Boolean
        get() = prefs.getBoolean("show_buffer", true)
        set(value) = prefs.edit { putBoolean("show_buffer", value) }

    var autoNextEpisode: Boolean
        get() = prefs.getBoolean("auto_next", true)
        set(value) = prefs.edit { putBoolean("auto_next", value) }

    var resumePlayback: Boolean
        get() = prefs.getBoolean("resume_playback", true)
        set(value) = prefs.edit { putBoolean("resume_playback", value) }

    var audioGainDb: Int
        get() = prefs.getInt("audio_gain", 0)
        set(value) = prefs.edit { putInt("audio_gain", value) }

    // === НАСТРОЙКИ СУБТИТРОВ ===
    var subtitleCharset: String?
        get() = prefs.getString("subtitle_charset", null)
        set(value) = prefs.edit { putString("subtitle_charset", value) }

    var preferredAudioLanguage: String?
        get() = prefs.getString("pref_audio_lang", null)
        set(value) = prefs.edit { putString("pref_audio_lang", value) }

    var preferredSubtitleLanguage: String?
        get() = prefs.getString("pref_subtitle_lang", null)
        set(value) = prefs.edit { putString("pref_subtitle_lang", value) }

    var subtitlesEnabled: Boolean
        get() = prefs.getBoolean("subtitles_enabled", true)
        set(value) = prefs.edit { putBoolean("subtitles_enabled", value) }

    // === НАСТРОЙКИ ДЕКОДЕРА ===
    var decoderMode: Int
        get() = prefs.getInt("decoder_mode", 0)
        set(value) = prefs.edit { putInt("decoder_mode", value) }

    var isDecoderFallback: Boolean
        get() = prefs.getBoolean("decoder_fallback", false)
        set(value) = prefs.edit { putBoolean("decoder_fallback", value) }

    // === НАСТРОЙКИ OTA ===
    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0)
        set(value) = prefs.edit { putLong("last_update_check", value) }

    var currentVersionCode: Int
        get() = prefs.getInt("current_version_code", 0)
        set(value) = prefs.edit { putInt("current_version_code", value) }

    // === ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ===
    fun savePosition(fileName: String, positionMs: Long) {
        if (positionMs > 5000) {
            prefs.edit { putLong("pos_$fileName", positionMs) }
        }
    }

    fun loadPosition(fileName: String): Long {
        return prefs.getLong("pos_$fileName", 0L)
    }

    fun clearDecoderFallback() {
        prefs.edit { putBoolean("decoder_fallback", false) }
    }
}
