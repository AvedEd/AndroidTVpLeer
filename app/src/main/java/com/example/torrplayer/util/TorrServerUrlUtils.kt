package com.example.torrplayer.util

import android.net.Uri

/**
 * Lampa (через TorrServer) отдаёт внешнему плееру прямую ссылку вида:
 *   http://192.168.1.10:8090/stream/Movie.mkv?link=<hash>&index=1&play
 * Отсюда можно достать и хост TorrServer, и хеш торрента — без какой-либо
 * ручной настройки со стороны пользователя.
 */
object TorrServerUrlUtils {

    /** "192.168.1.10:8090" или null, если ссылка не похожа на http(s)-адрес. */
    fun hostOf(streamUrl: String): String? {
        val uri = Uri.parse(streamUrl)
        val host = uri.host ?: return null
        return if (uri.port != -1) "$host:${uri.port}" else host
    }

    fun schemeOf(streamUrl: String): String =
        Uri.parse(streamUrl).scheme ?: "http"

    /** Значение query-параметра link=... (хеш торрента), если он есть в ссылке. */
    fun hashOf(streamUrl: String): String? =
        try {
            Uri.parse(streamUrl).getQueryParameter("link")
        } catch (e: Exception) {
            null
        }

    fun fileNameOf(streamUrl: String): String =
        Uri.parse(streamUrl).lastPathSegment ?: "video"

    /**
     * Значение query-параметра index=N (порядковый номер файла внутри торрента на
     * стороне TorrServer), если он есть в ссылке. Это надёжнее, чем сверять имена
     * файлов строками — их кодирование в URL может отличаться от того, что реально
     * прислал TorrServer в file_stats, а index — просто целое число, тут сверяться
     * не с чем.
     */
    fun indexOf(streamUrl: String): Int? =
        try {
            Uri.parse(streamUrl).getQueryParameter("index")?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
}
