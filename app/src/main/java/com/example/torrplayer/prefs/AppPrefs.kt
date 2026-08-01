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
        get() = sp.getInt(KEY_SEEK_STEP, 10)
        set(value) = sp.edit().putInt(KEY_SEEK_STEP, value).apply()

    var showBufferOverlay: Boolean
        get() = sp.getBoolean(KEY_SHOW_BUFFER, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_BUFFER, value).apply()

    var resumePlayback: Boolean
        get() = sp.getBoolean(KEY_RESUME, true)
        set(value) = sp.edit().putBoolean(KEY_RESUME, value).apply()

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
    }
}
