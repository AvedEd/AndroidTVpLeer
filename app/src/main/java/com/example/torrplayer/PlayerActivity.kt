package com.example.torrplayer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

        // Ускоренная перемотка при удержании влево/вправо.
        private const val SEEK_TICK_MS = 300L          // как часто "подкручиваем" во время удержания
        private const val SEEK_ACCEL_STAGE1_MS = 1500L // после этого — средняя скорость
        private const val SEEK_ACCEL_STAGE2_MS = 4000L // после этого — быстрая скорость
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: AppPrefs
    private var statsClient: TorrServerClient? = null
    private var player: ExoPlayer? = null

    private var streamUrl: String = ""
    private var hash: String? = null
    private var speedIndex = 2 // 1.0x
    private var panelVisible = false

    private val uiHandler = Handler(Looper.getMainLooper())

    private val hidePanelRunnable = Runnable { hidePanel() }

    // Направление удерживаемой перемотки: -1 назад, +1 вперёд, 0 — не удерживается.
    private var seekHoldDirection = 0
    private var seekHoldStartedAt = 0L

    private val seekHoldRunnable = object : Runnable {
        override fun run() {
            if (seekHoldDirection == 0) return
            val heldMs = SystemClock.elapsedRealtime() - seekHoldStartedAt
            val stepSeconds = seekStepForHold(heldMs)
            performSeek(stepSeconds * seekHoldDirection)
            uiHandler.postDelayed(this, SEEK_TICK_MS)
        }
    }

    // Обновляет индикатор буфера раз в секунду данными самого плеера
    // (это всегда работает, независимо от точной схемы JSON-ответа TorrServer).
    // Считает всегда, вне зависимости от того, видна ли панель — дёшево, а
    // при открытии панели данные уже готовы и не нужно ждать первого тика.
    private val bufferUpdater = object : Runnable {
        override fun run() {
            player?.let {
                val bufferedPct = it.bufferedPercentage
                val posMs = it.currentPosition
                val durMs = if (it.duration > 0) it.duration else 0
                binding.textBuffer.text = "Буфер: $bufferedPct%\n" +
                    "${Formatting.time(posMs)} / ${Formatting.time(durMs)}"
            }
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
                    if (stat != null) {
                        val extra = "\n↓ ${Formatting.speed(stat.downloadSpeed)}  Пиры: ${stat.peers ?: 0}"
                        binding.textBuffer.append(extra)
                    }
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
        binding.textBuffer.visibility = if (prefs.showBufferOverlay) View.VISIBLE else View.GONE

        binding.btnAudio.setOnClickListener { showTrackPicker(C.TRACK_TYPE_AUDIO, "Аудио дорожка") }
        binding.btnSubs.setOnClickListener { showTrackPicker(C.TRACK_TYPE_TEXT, "Субтитры") }
        binding.btnSpeed.setOnClickListener { cycleSpeed() }

        initPlayer()
    }

    /**
     * Кнопка "вниз" на пульте открывает/закрывает информационную панель.
     * Кнопки "влево"/"вправо" — перемотка: короткое нажатие — обычный шаг из
     * настроек, удержание — перемотка ускоряется. Пока панель открыта, эти
     * же кнопки вместо перемотки двигают фокус между её кнопками — поэтому
     * свою обработку включаем только когда панель скрыта.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) togglePanel()
            return true
        }

        if (!panelVisible &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        // Первое нажатие — сразу один обычный шаг, дальше ждём,
                        // не отпустили ли кнопку быстро, или начинаем ускорение.
                        seekHoldDirection = direction
                        seekHoldStartedAt = SystemClock.elapsedRealtime()
                        performSeek(prefs.seekStepSeconds * direction)
                        uiHandler.removeCallbacks(seekHoldRunnable)
                        uiHandler.postDelayed(seekHoldRunnable, 400)
                    }
                    // Повторные системные ACTION_DOWN (repeatCount > 0) игнорируем —
                    // ускорением занимается свой собственный seekHoldRunnable.
                }
                KeyEvent.ACTION_UP -> {
                    seekHoldDirection = 0
                    uiHandler.removeCallbacks(seekHoldRunnable)
                }
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    /** Шаг перемотки (в секундах) в зависимости от того, сколько уже удерживается кнопка. */
    private fun seekStepForHold(heldMs: Long): Int {
        val base = prefs.seekStepSeconds.coerceAtLeast(1)
        return when {
            heldMs < SEEK_ACCEL_STAGE1_MS -> base * 3
            heldMs < SEEK_ACCEL_STAGE2_MS -> base * 10
            else -> base * 25
        }
    }

    private fun performSeek(deltaSeconds: Int) {
        val p = player ?: return
        val durationMs = if (p.duration > 0) p.duration else Long.MAX_VALUE
        val target = (p.currentPosition + deltaSeconds * 1000L).coerceIn(0, durationMs)
        p.seekTo(target)
    }

    private fun togglePanel() {
        if (panelVisible) hidePanel() else showPanel()
    }

    private fun showPanel() {
        binding.infoPanel.visibility = View.VISIBLE
        panelVisible = true
        uiHandler.removeCallbacks(hidePanelRunnable)
        uiHandler.postDelayed(hidePanelRunnable, PANEL_AUTO_HIDE_MS)
    }

    private fun hidePanel() {
        binding.infoPanel.visibility = View.GONE
        panelVisible = false
        uiHandler.removeCallbacks(hidePanelRunnable)
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

    override fun onStop() {
        super.onStop()
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
