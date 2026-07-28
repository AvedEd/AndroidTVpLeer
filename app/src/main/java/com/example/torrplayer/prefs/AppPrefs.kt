package com.example.torrplayer.prefs

import android.content.Context

class AppPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("torrplayer_prefs", Context.MODE_PRIVATE)

    var serverHost: String
        get() = sp.getString(KEY_HOST, "127.0.0.1:8090") ?: "127.0.0.1:8090"
        set(value) = sp.edit().putString(KEY_HOST, value).apply()

    /** Шаг перемотки в секундах, доступный из настроек. */
    var seekStepSeconds: Int
        get() = sp.getInt(KEY_SEEK_STEP, 1)
        set(value) = sp.edit().putInt(KEY_SEEK_STEP, value).apply()

    var showBufferOverlay: Boolean
        get() = sp.getBoolean(KEY_SHOW_BUFFER, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_BUFFER, value).apply()

    var resumePlayback: Boolean
        get() = sp.getBoolean(KEY_RESUME, true)
        set(value) = sp.edit().putBoolean(KEY_RESUME, value).apply()

    /** Код языка последней выбранной аудиодорожки (например "rus"), либо null — нет предпочтения. */
    var preferredAudioLanguage: String?
        get() = sp.getString(KEY_AUDIO_LANG, null)
        set(value) = sp.edit().putString(KEY_AUDIO_LANG, value).apply()

    /** Код языка последних выбранных субтитров, либо null — нет предпочтения. */
    var preferredSubtitleLanguage: String?
        get() = sp.getString(KEY_SUB_LANG, null)
        set(value) = sp.edit().putString(KEY_SUB_LANG, value).apply()

    /** Были ли субтитры последний раз включены (по умолчанию — выключены). */
    var subtitlesEnabled: Boolean
        get() = sp.getBoolean(KEY_SUB_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_SUB_ENABLED, value).apply()

    /** Последняя выбранная скорость воспроизведения. */
    var playbackSpeed: Float
        get() = sp.getFloat(KEY_SPEED, 1.0f)
        set(value) = sp.edit().putFloat(KEY_SPEED, value).apply()

    /** Режим масштабирования картинки (значение AspectRatioFrameLayout.RESIZE_MODE_*). */
    var resizeMode: Int
        get() = sp.getInt(KEY_RESIZE_MODE, 0) // 0 = RESIZE_MODE_FIT
        set(value) = sp.edit().putInt(KEY_RESIZE_MODE, value).apply()

    /** Позиция воспроизведения для конкретного файла (ключ — url потока). */
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
    }
}
