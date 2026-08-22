package com.example.torrplayer.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL

object IptvPlaylistParser {

    /**
     * Загружает и парсит M3U-плейлист по ссылке
     * @param playlistUrl ссылка на M3U-файл
     * @return список каналов
     */
    suspend fun parsePlaylist(playlistUrl: String): List<IptvChannel> = withContext(Dispatchers.IO) {
        val channels = mutableListOf<IptvChannel>()
        
        try {
            val url = URL(playlistUrl)
            val connection = url.openConnection()
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            var line: String?
            var currentName = ""
            var currentGroup = "Общие"
            var currentLogo: String? = null
            
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                
                // Парсим строку с информацией о канале
                if (currentLine.startsWith("#EXTINF:")) {
                    // Извлекаем название канала
                    val nameMatch = Regex(""",([^,]+)$""").find(currentLine)
                    currentName = nameMatch?.groupValues?.get(1)?.trim() ?: "Неизвестный канал"
                    
                    // Извлекаем группу
                    val groupMatch = Regex("""group-title="([^"]+)"""").find(currentLine)
                    currentGroup = groupMatch?.groupValues?.get(1) ?: "Общие"
                    
                    // Извлекаем логотип
                    val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(currentLine)
                    currentLogo = logoMatch?.groupValues?.get(1)
                }
                
                // Строка с URL канала (не начинается с #)
                if (!currentLine.startsWith("#") && currentLine.isNotBlank()) {
                    if (currentName.isNotEmpty()) {
                        channels.add(
                            IptvChannel(
                                name = currentName,
                                url = currentLine.trim(),
                                group = currentGroup,
                                logo = currentLogo
                            )
                        )
                    }
                    // Сбрасываем для следующего канала
                    currentName = ""
                    currentGroup = "Общие"
                    currentLogo = null
                }
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        channels
    }
}
