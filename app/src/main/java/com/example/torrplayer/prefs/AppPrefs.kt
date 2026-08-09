package com.example.torrplayer.prefs

import android.content.Context

class AppPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("torrplayer_prefs", Context.MODE_PRIVATE)

    var serverHost: String
        get() = sp.getString(KEY_HOST, "127.0.0.1:8090") ?: "127.0.0.1:8090"
        set(value) = sp.edit().putString(KEY_HOST, value).apply()

    var seekStepSeconds: Int
        get() = sp.getInt(KEY_SEEK_STEP, 1)
        set(value) = sp.edit().putInt(KEY_SEEK_STEP, value).apply()

    var showBufferOverlay: Boolean
        get() = sp.getBoolean(KEY_SHOW_BUFFER, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_BUFFER, value).apply()

    var resumePlayback: Boolean
        get() = sp.getBoolean(KEY_RESUME, true)
        set(value) = sp.edit().putBoolean(KEY_RESUME, value).apply()

    var preferredAudioLanguage: String?
        get() = sp.getString(KEY_AUDIO_LANG, null)
        set(value) = sp.edit().putString(KEY_AUDIO_LANG, value).apply()

    var preferredSubtitleLanguage: String?
        get() = sp.getString(KEY_SUB_LANG, null)
        set(value) = sp.edit().putString(KEY_SUB_LANG, value).apply()

    var subtitlesEnabled: Boolean
        get() = sp.getBoolean(KEY_SUB_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_SUB_ENABLED, value).apply()

    var playbackSpeed: Float
        get() = sp.getFloat(KEY_SPEED, 1.0f)
        set(value) = sp.edit().putFloat(KEY_SPEED, value).apply()

    var resizeMode: Int
        get() = sp.getInt(KEY_RESIZE_MODE, 0)
        set(value) = sp.edit().putInt(KEY_RESIZE_MODE, value).apply()

    /**
     * Автоматически переключаться на следующую серию, когда текущая заканчивается
     * (только если у торрента больше одного видеофайла). Включено по умолчанию.
     */
    var autoNextEpisode: Boolean
        get() = sp.getBoolean(KEY_AUTO_NEXT, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_NEXT, value).apply()

    /**
     * Режим декодирования сложного звука (AC-3/DTS/TrueHD и т.п.):
     * 0 = АВТО — пытаться пробросить поток как есть на ТВ/ресивер (лучший вариант, если есть eARC).
     * 1 = ВСЕГДА PCM — декодировать в PCM собственным FFmpeg-декодером (надёжнее без eARC). По умолчанию.
     * 2 = PCM ТОЛЬКО ПРИ СБОЕ — сначала пробовать проброс/аппаратный декодер, PCM только если не вышло.
     */
    var audioDecodeMode: Int
        get() = sp.getInt(KEY_AUDIO_DECODE_MODE, 1)
        set(value) = sp.edit().putInt(KEY_AUDIO_DECODE_MODE, value).apply()

    /**
     * Кодировка внешних .srt субтитров. null — определять автоматически.
     * Значение — имя Java-кодировки (например "windows-1251", "koi8-r", "UTF-8").
     */
    var subtitleCharset: String?
        get() = sp.getString(KEY_SUBTITLE_CHARSET, null)
        set(value) = sp.edit().putString(KEY_SUBTITLE_CHARSET, value).apply()

    /**
     * Экспериментально: аппаратная синхронизация видео/звука (tunneling) через ExoPlayer.
     * Может сгладить рывки на тяжёлом 4K, но поддерживается не на всех приставках —
     * если устройство не умеет, ExoPlayer сам тихо откатится на обычный режим.
     * Выключено по умолчанию.
     */
    var tunnelingEnabled: Boolean
        get() = sp.getBoolean(KEY_TUNNELING, false)
        set(value) = sp.edit().putBoolean(KEY_TUNNELING, value).apply()

    fun savePosition(streamUrl: String, positionMs: Long) {
        sp.edit().putLong(posKey(streamUrl), positionMs).apply()
    }

    fun loadPosition(streamUrl: String): Long =
        sp.getLong(posKey(streamUrl), 0L)

    private fun posKey(streamUrl: String) = "pos_${streamUrl.hashCode()}"

    companion object {
        private const val KEY_HOST = "server_host"
        private const val KEY_SEEK_STEP = "seek_step"
        private const val KEY_SHOW_BUFFER = "show_buffer"
        private const val KEY_RESUME = "resume_playback"
        private const val KEY_AUDIO_LANG = "preferred_audio_lang"
        private const val KEY_SUB_LANG = "preferred_sub_lang"
        private const val KEY_SUB_ENABLED = "subtitles_enabled"
        private const val KEY_SPEED = "playback_speed"
        private const val KEY_RESIZE_MODE = "resize_mode"
        private const val KEY_AUDIO_DECODE_MODE = "audio_decode_mode"
        private const val KEY_SUBTITLE_CHARSET = "subtitle_charset"
        private const val KEY_AUTO_NEXT = "auto_next_episode"
        private const val KEY_TUNNELING = "tunneling_enabled"
    }
}
