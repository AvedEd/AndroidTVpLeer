package com.example.torrplayer.iptv

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.torrplayer.prefs.AppPrefs
import kotlinx.coroutines.launch

class IptvViewModel(
    private val prefs: AppPrefs
) : ViewModel() {

    private val _channels = MutableLiveData<List<IptvChannel>>(emptyList())
    val channels: LiveData<List<IptvChannel>> = _channels

    private val _filteredChannels = MutableLiveData<List<IptvChannel>>(emptyList())
    val filteredChannels: LiveData<List<IptvChannel>> = _filteredChannels

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private var allChannels: List<IptvChannel> = emptyList()
    private var currentGroup: String = "Все"

    /**
     * Загружает плейлист по ссылке
     */
    fun loadPlaylist(playlistUrl: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val parsedChannels = IptvPlaylistParser.parsePlaylist(playlistUrl)
                allChannels = parsedChannels
                _channels.value = parsedChannels
                filterByGroup(currentGroup)
                prefs.saveIptvPlaylistUrl(playlistUrl)
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки плейлиста: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Фильтрует каналы по группе
     */
    fun filterByGroup(group: String) {
        currentGroup = group
        if (group == "Все") {
            _filteredChannels.value = allChannels
        } else {
            _filteredChannels.value = allChannels.filter { it.group == group }
        }
    }

    /**
     * Возвращает список доступных групп
     */
    fun getGroups(): List<String> {
        val groups = allChannels.map { it.group }.distinct().sorted()
        return listOf("Все") + groups
    }

    /**
     * Поиск каналов по названию
     */
    fun searchChannels(query: String) {
        if (query.isEmpty()) {
            filterByGroup(currentGroup)
            return
        }
        val filtered = allChannels.filter { 
            it.name.contains(query, ignoreCase = true) 
        }
        _filteredChannels.value = filtered
    }

    /**
     * Выбрать канал для воспроизведения
     */
    fun selectChannel(channel: IptvChannel): String {
        return channel.url
    }
}

/**
 * Расширение для AppPrefs — сохранение ссылки на плейлист
 */
fun AppPrefs.saveIptvPlaylistUrl(url: String) {
    // Можно сохранить в SharedPreferences
}

fun AppPrefs.loadIptvPlaylistUrl(): String? {
    return null // Заглушка — реализуйте по желанию
}
