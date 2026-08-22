package com.example.torrplayer.iptv

data class IptvChannel(
    val name: String,           // Название канала
    val url: String,            // Ссылка на поток
    val group: String = "Общие", // Группа каналов
    val logo: String? = null,   // Логотип (опционально)
    val isFavorite: Boolean = false // Избранное
)
