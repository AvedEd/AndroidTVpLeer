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
     * true — всегда декодировать сложный звук (AC-3/DTS/TrueHD и т.п.) в PCM самим плеером,
     * не полагаясь на "проброс" (passthrough) через HDMI. Лучше подходит для ТВ без eARC,
     * где обычный ARC не пропускает TrueHD/DTS-HD/lossless Atmos целиком.
     * false — предпочитать проброс исходного потока как есть, если система его поддерживает
     * (актуально для тех, у кого ТВ/ресивер с eARC).
     * Включено по умолчанию — большинство ТВ пока без eARC.
     */
    var audioForcePcm: Boolean
        get() = sp.getBoolean(KEY_AUDIO_FORCE_PCM, true)
        set(value) = sp.edit().putBoolean(KEY_AUDIO_FORCE_PCM, value).apply()

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
        private const val KEY_AUDIO_FORCE_PCM = "audio_force_pcm"
    }
}
