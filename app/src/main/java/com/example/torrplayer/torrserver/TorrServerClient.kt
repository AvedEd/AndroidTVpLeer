package com.example.torrplayer.torrserver

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Тонкая обёртка над TorrServerApi.
 *
 * Раньше клиент всегда ходил по адресу, вручную заданному в настройках. Теперь
 * основной сценарий — плеер запущен из Lampa/TorrServer как внешний плеер, и
 * ссылка на поток УЖЕ содержит правильный хост TorrServer. Поэтому клиент
 * создаётся с конкретным host:port на конкретный случай (см. TorrServerUrlUtils),
 * а не привязан намертво к глобальным настройкам.
 */
class TorrServerClient(private val host: String, private val scheme: String = "http") {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Поле "stat" в ответе TorrServer на разных версиях сервера приходит по-разному —
     * иногда объектом с полями (download_speed, peers, ...), а иногда просто числом.
     * Без этого терпимого разбора несовпадение формата ронял бы разбор ВСЕГО ответа
     * целиком, включая file_stats — из-за чего пропадал бы список серий и переставал
     * работать автопереход, хотя сами файлы сервер прислал нормально.
     */
    private val gson = GsonBuilder()
        .registerTypeAdapter(
            TorrentStat::class.java,
            JsonDeserializer<TorrentStat?> { json, _, context ->
                if (json != null && json.isJsonObject) {
                    context.deserialize(json, TorrentStat::class.java)
                } else {
                    null
                }
            }
        )
        .create()

    private val api: TorrServerApi by lazy {
        val base = "$scheme://$host/"
        Retrofit.Builder()
            .baseUrl(base)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(TorrServerApi::class.java)
    }

    suspend fun getTorrent(hash: String): TorrentInfo? {
        val resp = api.torrentInfo(TorrentActionRequest(action = "get", hash = hash))
        return if (resp.isSuccessful) resp.body() else null
    }

    /** Проверка доступности сервера — используется кнопкой "Проверить соединение" в настройках. */
    suspend fun ping(): Boolean = try {
        api.torrentsList(TorrentActionRequest(action = "list")).isSuccessful
    } catch (e: Exception) {
        false
    }
}
