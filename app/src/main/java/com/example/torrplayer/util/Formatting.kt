package com.example.torrplayer.util

import java.util.Locale

object Formatting {

    fun bytes(value: Long?): String {
        val v = value ?: return "—"
        if (v <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = v.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", size, units[unitIndex])
    }

    fun speed(bytesPerSec: Long?): String = "${bytes(bytesPerSec)}/s"

    fun time(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun bitrate(bps: Int): String {
        if (bps <= 0) return ""
        val mbps = bps / 1_000_000.0
        return if (mbps >= 1) String.format(Locale.US, "%.1f Мбит/с", mbps)
        else String.format(Locale.US, "%.0f Кбит/с", bps / 1000.0)
    }

    /** Человекочитаемое имя видеокодека по MIME-типу из Format.sampleMimeType. */
    fun videoCodecName(mime: String?): String = when (mime) {
        null -> "—"
        "video/avc" -> "H.264/AVC"
        "video/hevc" -> "H.265/HEVC"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4"
        "video/mpeg2" -> "MPEG-2"
        else -> mime.substringAfter("/")
    }

    /**
     * Человекочитаемое имя аудиокодека. AC-3/E-AC-3/DTS/TrueHD воспроизводятся
     * только благодаря FFmpeg-расширению — если звук на этих форматах слышен,
     * значит оно подключилось и работает.
     */
    fun audioCodecName(mime: String?): String = when (mime) {
        null -> "—"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/ac3" -> "AC-3 (Dolby Digital)"
        "audio/eac3" -> "E-AC-3 (Dolby Digital+)"
        "audio/eac3-joc" -> "E-AC-3 JOC (Atmos)"
        "audio/true-hd" -> "Dolby TrueHD"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/opus" -> "Opus"
        "audio/flac" -> "FLAC"
        "audio/vorbis" -> "Vorbis"
        "audio/raw" -> "PCM"
        else -> mime.substringAfter("/")
    }
}
