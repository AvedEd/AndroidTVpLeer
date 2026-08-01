package com.example.torrplayer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.example.torrplayer.databinding.ActivityPlayerBinding
import com.example.torrplayer.prefs.AppPrefs
import com.example.torrplayer.torrserver.TorrServerClient
import com.example.torrplayer.util.Formatting
import com.example.torrplayer.util.TorrServerUrlUtils
import kotlinx.coroutines.launch

/**
 * Экран воспроизведения.
 *
 * Запускается двумя способами:
 *  1) Извне, из Lampa (или любого другого приложения), стандартным
 *     Intent.ACTION_VIEW со ссылкой на поток TorrServer — именно за счёт
 *     intent-filter в манифесте это приложение появляется в списке плееров.
 *  2) Изнутри самого TorrPlayer с явными extra (EXTRA_STREAM_URL и т.д.),
 *     если он когда-нибудь понадобится как отдельное приложение.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_HASH = "extra_hash"

        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        private const val PANEL_AUTO_HIDE_MS = 6000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: AppPrefs
    private var statsClient: TorrServerClient? = null
    private var player: ExoPlayer? = null

    private var streamUrl: String = ""
    private var hash: String? = null
    private var speedIndex = 2 // 1.0x
    private var panelVisible = false
    private var serverStatusText: String? = null

    private val uiHandler = Handler(Looper.getMainLooper())

    private val hidePanelRunnable = Runnable { hidePanel() }

    // Обновляет индикатор буфера раз в секунду данными самого плеера
    // (это всегда работает, независимо от точной схемы JSON-ответа TorrServer).
    // Считает всегда, вне зависимости от того, видна ли панель — дёшево, а
    // при открытии панели данные уже готовы и не нужно ждать первого тика.
    private val bufferUpdater = object : Runnable {
        override fun run() {
            renderStatusText()
            uiHandler.postDelayed(this, 1000)
        }
    }

    // Дополнительно — раз в 4 секунды опрашивает TorrServer о скорости закачки/пирах, если хеш известен.
    private val serverStatsUpdater = object : Runnable {
        override fun run() {
            val h = hash
            val client = statsClient
            if (h != null && client != null) {
                lifecycleScope.launch {
                    val info = try { client.getTorrent(h) } catch (e: Exception) { null }
                    val stat = info?.stat
                    serverStatusText = if (stat != null) {
                        "↓ ${Formatting.speed(stat.downloadSpeed)}  Пиры: ${stat.peers ?: 0}"
                    } else {
                        null
                    }
                    renderStatusText()
                }
            }
            uiHandler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)

        val resolved = resolveIncomingVideo()
        if (resolved == null) {
            android.widget.Toast.makeText(this, R.string.no_video_link, android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        streamUrl = resolved.first
        hash = resolved.second

        // Хост для статистики берём прямо из полученной ссылки — так плеер работает
        // "из коробки" с любым TorrServer, без ручной настройки адреса.
        TorrServerUrlUtils.hostOf(streamUrl)?.let { host ->
            statsClient = TorrServerClient(host, TorrServerUrlUtils.schemeOf(streamUrl))
        }

        // Панель (буфер + аудио/субтитры/скорость) по умолчанию скрыта — ничего не
        // загромождает экран. Открывается кнопкой "вниз" на пульте (см. dispatchKeyEvent).
        binding.infoPanel.visibility = View.GONE
        updateInfoVisibility()

        binding.btnAudio.setOnClickListener { showTrackPicker(C.TRACK_TYPE_AUDIO, "Аудио дорожка") }
        binding.btnSubs.setOnClickListener { showTrackPicker(C.TRACK_TYPE_TEXT, "Субтитры") }
        binding.btnSpeed.setOnClickListener { cycleSpeed() }

        initPlayer()
    }

    /**
     * Кнопка "вниз" на пульте открывает/закрывает информационную панель
     * (вместо того, чтобы она постоянно висела на экране поверх видео).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> {
                if (event.action == KeyEvent.ACTION_DOWN) togglePanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) togglePanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    seekByStep(-(prefs.seekStepSeconds * 1000L))
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    seekByStep(prefs.seekStepSeconds * 1000L)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    togglePlayback()
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (panelVisible) {
                        hidePanel()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun togglePlayback() {
        player?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    private fun seekByStep(deltaMs: Long) {
        val p = player ?: return
        val newPos = (p.currentPosition + deltaMs).coerceIn(0L, if (p.duration > 0) p.duration else Long.MAX_VALUE)
        p.seekTo(newPos)
        if (prefs.showBufferOverlay || panelVisible) {
            val direction = if (deltaMs >= 0) "Вперёд" else "Назад"
            binding.textBuffer.text = "$direction ${Formatting.time(kotlin.math.abs(deltaMs))}"
        }
    }

    private fun togglePanel() {
        if (panelVisible) hidePanel() else showPanel()
    }

    private fun showPanel() {
        binding.infoPanel.visibility = View.VISIBLE
        panelVisible = true
        updateInfoVisibility()
        uiHandler.removeCallbacks(hidePanelRunnable)
        uiHandler.postDelayed(hidePanelRunnable, PANEL_AUTO_HIDE_MS)
    }

    private fun hidePanel() {
        binding.infoPanel.visibility = View.GONE
        panelVisible = false
        updateInfoVisibility()
        uiHandler.removeCallbacks(hidePanelRunnable)
    }

    private fun updateInfoVisibility() {
        binding.textBuffer.visibility = if (prefs.showBufferOverlay || panelVisible) View.VISIBLE else View.GONE
    }

    private fun renderStatusText() {
        val info = StringBuilder()
        player?.let {
            val bufferedPct = it.bufferedPercentage
            val posMs = it.currentPosition
            val durMs = if (it.duration > 0) it.duration else 0
            info.append("Буфер: $bufferedPct%\n")
            info.append("${Formatting.time(posMs)} / ${Formatting.time(durMs)}")
        }
        serverStatusText?.let {
            if (info.isNotEmpty()) info.append("\n")
            info.append(it)
        }
        binding.textBuffer.text = info.toString().ifEmpty { "Подготовка к воспроизведению…" }
    }

    /**
     * Достаёт URL видео и (если получится) хеш торрента либо из внешнего
     * Intent.ACTION_VIEW (Lampa и подобные), либо из собственных extra.
     * Возвращает null, если плеер запущен без данных о видео.
     */
    private fun resolveIncomingVideo(): Pair<String, String?>? {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val url = intent.data.toString()
            // "link" в query-строке TorrServer — это и есть хеш торрента.
            val hashFromUrl = TorrServerUrlUtils.hashOf(url)
            return url to hashFromUrl
        }
        val extraUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: return null
        val extraHash = intent.getStringExtra(EXTRA_HASH) ?: TorrServerUrlUtils.hashOf(extraUrl)
        return extraUrl to extraHash
    }

    private fun initPlayer() {
        val seekMs = prefs.seekStepSeconds * 1000L

        val renderersFactory = DefaultRenderersFactory(this)
            // PREFER означает: если в проект добавлено расширение с программными декодерами
            // (например, media3 FFmpeg extension для DTS/TrueHD), оно будет использовано
            // в приоритете перед стандартными — без этого расширения приложение всё равно
            // воспроизводит все контейнеры/кодеки, поддерживаемые железом приставки.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setSeekForwardIncrementMs(seekMs)
            .setSeekBackIncrementMs(seekMs)
            .build()

        player = exoPlayer
        binding.playerView.player = exoPlayer

        val mediaItem = MediaItem.fromUri(streamUrl)
        val startPos = if (prefs.resumePlayback) prefs.loadPosition(streamUrl) else 0L

        exoPlayer.setMediaItem(mediaItem, startPos)
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Показать ошибку прямо на панели и открыть её — без сети/декодера
                // проигрывание невозможно, человек должен это увидеть сразу.
                binding.textBuffer.text = "Ошибка воспроизведения:\n${error.errorCodeName}"
                binding.textBuffer.visibility = View.VISIBLE
                showPanel()
            }
        })

        uiHandler.post(bufferUpdater)
        uiHandler.post(serverStatsUpdater)
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.size
        val speed = SPEEDS[speedIndex]
        player?.playbackParameters = PlaybackParameters(speed)
        binding.btnSpeed.text = "${speed}x"
    }

    private fun showTrackPicker(trackType: Int, dialogTitle: String) {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == trackType }
        if (groups.isEmpty()) {
            AlertDialog.Builder(this).setTitle(dialogTitle).setMessage("Дорожки не найдены").show()
            return
        }
        val labels = mutableListOf("Выключить")
        val entries = mutableListOf<Pair<Int, Int>?>(null) // (groupIndex, trackIndex)

        groups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                labels.add(format.label ?: format.language ?: "Дорожка ${entries.size}")
                entries.add(groupIndex to trackIndex)
            }
        }

        AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setItems(labels.toTypedArray()) { _, which ->
                val builder = p.trackSelectionParameters.buildUpon()
                if (which == 0) {
                    builder.setTrackTypeDisabled(trackType, true)
                } else {
                    val (groupIndex, trackIndex) = entries[which]!!
                    val group = groups[groupIndex]
                    builder.setTrackTypeDisabled(trackType, false)
                    builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                }
                p.trackSelectionParameters = builder.build()
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        // Запоминаем позицию, чтобы при повторном открытии этого же файла продолжить с места остановки.
        player?.let { prefs.savePosition(streamUrl, it.currentPosition) }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
