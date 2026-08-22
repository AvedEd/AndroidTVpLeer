package com.example.torrplayer.iptv

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.torrplayer.PlayerActivity
import com.example.torrplayer.R
import com.example.torrplayer.TorrPlayerApplication
import kotlinx.coroutines.launch

class IptvActivity : AppCompatActivity() {

    private lateinit var etPlaylistUrl: EditText
    private lateinit var btnLoadPlaylist: Button
    private lateinit var spinnerGroups: Spinner
    private lateinit var listViewChannels: ListView
    private lateinit var progressBar: ProgressBar
    private lateinit var etSearch: EditText

    private val viewModel by lazy {
        IptvViewModel(TorrPlayerApplication.prefs)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_iptv)

        // Инициализация UI
        etPlaylistUrl = findViewById(R.id.et_playlist_url)
        btnLoadPlaylist = findViewById(R.id.btn_load_playlist)
        spinnerGroups = findViewById(R.id.spinner_groups)
        listViewChannels = findViewById(R.id.listview_channels)
        progressBar = findViewById(R.id.progress_bar)
        etSearch = findViewById(R.id.et_search)

        // Кнопка загрузки плейлиста
        btnLoadPlaylist.setOnClickListener {
            val url = etPlaylistUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                viewModel.loadPlaylist(url)
            } else {
                Toast.makeText(this, "Введите ссылку на M3U-плейлист", Toast.LENGTH_SHORT).show()
            }
        }

        // Наблюдатели
        viewModel.channels.observe(this) { channels ->
            if (channels.isNotEmpty()) {
                Toast.makeText(this, "Загружено ${channels.size} каналов", Toast.LENGTH_SHORT).show()
                updateSpinner()
            }
        }

        viewModel.filteredChannels.observe(this) { channels ->
            updateChannelList(channels)
        }

        viewModel.isLoading.observe(this) { loading ->
            progressBar.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            }
        }

        // Клик по каналу — открываем в плеере
        listViewChannels.setOnItemClickListener { _, _, position, _ ->
            val channel = viewModel.filteredChannels.value?.get(position)
            channel?.let {
                openPlayer(it)
            }
        }

        // Поиск
        etSearch.setOnEditorActionListener { _, _, _ ->
            val query = etSearch.text.toString()
            viewModel.searchChannels(query)
            true
        }

        // Загружаем сохранённый плейлист
        val savedUrl = TorrPlayerApplication.prefs.loadIptvPlaylistUrl()
        if (!savedUrl.isNullOrEmpty()) {
            etPlaylistUrl.setText(savedUrl)
            viewModel.loadPlaylist(savedUrl)
        }
    }

    private fun updateSpinner() {
        val groups = viewModel.getGroups()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, groups)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGroups.adapter = adapter

        spinnerGroups.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val group = parent?.getItemAtPosition(position) as? String ?: "Все"
                viewModel.filterByGroup(group)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                // Игнорируем
            }
        }
    }

    private fun updateChannelList(channels: List<IptvChannel>) {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            channels.map { it.name }
        )
        listViewChannels.adapter = adapter
    }

    private fun openPlayer(channel: IptvChannel) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.url)
            putExtra(PlayerActivity.EXTRA_TITLE, channel.name)
        }
        startActivity(intent)
    }
}
