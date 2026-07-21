package com.example.torrplayer.torrserver

import com.google.gson.annotations.SerializedName

/**
 * Тело запроса к POST /torrents.
 * Формат подтверждён документацией/issue-трекером TorrServer (MatriX):
 * { "action": "add|get|list|rem|drop", "link": "...", "hash": "...", "title": "...",
 *   "poster": "...", "save_to_db": true }
 */
data class TorrentActionRequest(
    val action: String,
    val link: String? = null,
    val hash: String? = null,
    val title: String? = null,
    val poster: String? = null,
    @SerializedName("save_to_db") val saveToDb: Boolean? = null
)

data class TorrentFileStat(
    val id: Int,
    val path: String,
    val length: Long = 0
)

/**
 * Ответ TorrServer на add/get/list может отличаться между версиями (MatriX / 1.x),
 * поэтому большинство полей — nullable, а неизвестные поля просто игнорируются Gson.
 */
data class TorrentInfo(
    val hash: String? = null,
    val title: String? = null,
    val poster: String? = null,
    val name: String? = null,
    @SerializedName("file_stats") val fileStats: List<TorrentFileStat>? = null,
    val stat: TorrentStat? = null
)

data class TorrentStat(
    @SerializedName("download_speed") val downloadSpeed: Long? = null,
    val peers: Int? = null,
    @SerializedName("torrent_size") val torrentSize: Long? = null,
    @SerializedName("preloaded_bytes") val preloadedBytes: Long? = null
)
