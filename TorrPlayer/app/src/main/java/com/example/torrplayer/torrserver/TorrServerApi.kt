package com.example.torrplayer.torrserver

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface TorrServerApi {

    // Единая точка входа TorrServer: действие передаётся полем "action" в теле запроса.
    @POST("torrents")
    suspend fun torrentsList(@Body body: TorrentActionRequest): Response<List<TorrentInfo>>

    @POST("torrents")
    suspend fun torrentInfo(@Body body: TorrentActionRequest): Response<TorrentInfo>
}
