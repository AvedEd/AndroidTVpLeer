package com.example.torrplayer

import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.torrplayer.databinding.ActivityPlayerBinding
import com.example.torrplayer.prefs.AppPrefs
import com.example.torrplayer.torrserver.TorrServerClient
import com.example.torrplayer.torrserver.TorrentFileStat
import com.example.torrplayer.util.Formatting
import com.example.torrplayer.util.TorrServerUrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_HASH = "extra_hash"

        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        private const val PANEL_AUTO_HIDE_MS = 12000L
        private const val BACK_EXIT_WINDOW_MS = 2000L

        private val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        private val RESIZE_LABELS = arrayOf("По размеру", "Обрезать", "Растянуть")

        private const val SEEK_TICK_MS = 300L
        private const val SEEK_ACCEL_STAGE1_MS = 1500L
        private const val SEEK_ACCEL_STAGE2_MS = 4000L
        private const val OK_LONG_PRESS_MS = 500L

        private const val MIN_BUFFER_MS = 30_000
        private const val MAX_BUFFER_MS = 90_000
        private const val BUFFER_FOR_PLAYBACK_MS = 5_000
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 10_000

        private const val HTTP_CONNECT_TIMEOUT_MS = 15_000
        private const val HTTP_READ_TIMEOUT_MS = 20_000

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts", "mts",
            "3gp", "3gpp", "flv", "wmv", "mpg", "mpeg", "ogv", "divx", "vob"
        )
    }

    private enum class Panel { NONE, CONTROLS, INFO, EPISODES }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: AppPrefs
    private var statsClient: TorrServerClient? = null
    private var player: ExoPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var streamUrl: String = ""
    private var hash: String? = null
    private var speedIndex = 2
    private var aspectIndex = 0
    private var currentPanel = Panel.NONE
    private var lastAppliedFrameRate = 0f
    private var lastBackPressAt = 0L
    private var episodeFiles: List<TorrentFileStat> = emptyList()
    /**
     * Числовой id (в терминах TorrServer — "index") сейчас играющего файла внутри
     * торрента. Раньше "текущую серию" определяли сравнением имени файла из URL с
     * именами из file_stats — это ломалось, если TorrServer/Lampa присылали ссылку
     * в формате, где имя файла не совпадает буквально (кодирование, регистр и т.п.).
     * Число же в query-параметре index= сравнивать не с чем — оно либо совпадает,
     * либо нет, без вариантов.
     */
    private var currentEpisodeFileId: Int? = null

    private var incomingTitle: String? = null
    private var externalStartPositionMs: Long? = null
    private var externalSubtitles: List<Pair<Uri, String?>> = emptyList()
    private var externalHeaders: Map<String, String> = emptyMap()
    private var shouldReturnResult = false

    private val uiHandler = Handler(Looper.getMainLooper())

    private val hidePanelRunnable = Runnable { hideAllPanels() }

    private var seekHoldDirection = 0
    private var seekHoldStartedAt = 0L

    private var centerLongPressTriggered = false
    private val centerLongPressRunnable = Runnable {
        centerLongPressTriggered = true
        togglePanel(Panel.CONTROLS)
    }

    private val seekHoldRunnable = object : Runnable {
        override fun run() {
            if (seekHoldDirection == 0) return
            val heldMs = SystemClock.elapsedRealtime() - seekHoldStartedAt
            val stepSeconds = seekStepForHold(heldMs)
            performSeek(stepSeconds * seekHoldDirection)
            uiHandler.postDelayed(this, SEEK_TICK_MS)
        }
    }

    private val bufferUpdater = object : Runnable {
        override fun run() {
            player?.let {
                val bufferedPct = it.bufferedPercentage
                val posMs = it.currentPosition
                val durMs = if (it.duration > 0) it.duration else 0
                binding.textBuffer.text = "Буфер: $bufferedPct%\n" +
                    "${Formatting.time(posMs)} / ${Formatting.time(durMs)}"

                if (currentPanel == Panel.CONTROLS && !binding.seekBar.isPressed) {
                    updateSeekBar()
                }

                val vf = it.videoFormat
                val af = it.audioFormat
                val videoLine = vf?.let { f ->
                    val res = if (f.width > 0 && f.height > 0) "${f.width}x${f.height} " else ""
                    val br = Formatting.bitrate(f.bitrate)
                    "Видео: $res${Formatting.videoCodecName(f.sampleMimeType)}" +
                        if (br.isNotEmpty()) " • $br" else ""
                } ?: "Видео: —"
                val audioLine = af?.let { f ->
                    val ch = if (f.channelCount > 0) " ${f.channelCount}ch" else ""
                    "Аудио: ${Formatting.audioCodecName(f.sampleMimeType)}$ch"
                } ?: "Аудио: —"
                binding.textVideoInfo.text = "$videoLine\n$audioLine"

                vf?.frameRate?.takeIf { fr -> fr > 0f }?.let { adjustDisplayRefreshRate(it) }
            }
            uiHandler.postDelayed(this, 1000)
        }
    }

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
            Toast.makeText(this, R.string.no_video_link, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        streamUrl = resolved.first
        hash = resolved.second
        currentEpisodeFileId = TorrServerUrlUtils.indexOf(streamUrl)

        parseExternalPlayerExtras()

        TorrServerUrlUtils.hostOf(streamUrl)?.let { host ->
            statsClient = TorrServerClient(host, TorrServerUrlUtils.schemeOf(streamUrl))
        }

        binding.panelInfo.visibility = View.GONE
        binding.panelControls.visibility = View.GONE
        binding.errorBanner.visibility = View.GONE
        binding.textBuffer.visibility = if (prefs.showBufferOverlay) View.VISIBLE else View.GONE
        binding.textVideoInfo.visibility = if (prefs.showBufferOverlay) View.VISIBLE else View.GONE

        incomingTitle?.let { title ->
            binding.textTitle.text = title
            binding.textTitle.visibility = View.VISIBLE
            uiHandler.postDelayed({ binding.textTitle.visibility = View.GONE }, 5000)
        }

        binding.btnSpeed.setOnClickListener { cycleSpeed() }
        binding.btnAspect.setOnClickListener { cycleAspect() }
        binding.btnRestart.setOnClickListener { restartFromBeginning() }
        binding.btnPlaylist.setOnClickListener { togglePanel(Panel.EPISODES) }
        binding.btnRetry.setOnClickListener { retryPlayback() }

        // Скрываем встроенную шестерёнку настроек ExoPlayer — она дублирует
        // наши собственные панели аудио/субтитров/скорости.
        binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
        // Скрываем встроенную полоску перемотки ExoPlayer — используем свою,
        // первой в панели, чтобы порядок был предсказуемым (ползунок → аудио → субтитры).
        binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.visibility = View.GONE

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPanel != Panel.NONE) {
                    hideAllPanels()
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackPressAt <= BACK_EXIT_WINDOW_MS) {
                    reportResultAndFinish()
                } else {
                    lastBackPressAt = now
                    Toast.makeText(this@PlayerActivity, "Нажмите «Назад» ещё раз для выхода", Toast.LENGTH_SHORT).show()
                }
            }
        })

        aspectIndex = RESIZE_MODES.indexOf(prefs.resizeMode).coerceAtLeast(0)
        binding.playerView.resizeMode = RESIZE_MODES[aspectIndex]
        binding.btnAspect.text = RESIZE_LABELS[aspectIndex]

        loadEpisodesInBackground()

        val charset = prefs.subtitleCharset
        if (charset != null && externalSubtitles.isNotEmpty()) {
            lifecycleScope.launch {
                externalSubtitles = withContext(Dispatchers.IO) {
                    reencodeSubtitlesToUtf8(externalSubtitles, charset)
                }
                initPlayer()
            }
        } else {
            initPlayer()
        }
    }

    /**
     * Перекодирует внешние .srt субтитры из выбранной кодировки (например Windows-1251)
     * в UTF-8, сохраняя результат во временный файл в кеше приложения. Media3 сам по себе
     * не умеет выбирать кодировку для SRT — только угадывает UTF-8/BOM, поэтому старые
     * русские субтитры из раздач иначе показываются кракозябрами.
     */
    private fun reencodeSubtitlesToUtf8(
        subs: List<Pair<Uri, String?>>,
        charsetName: String
    ): List<Pair<Uri, String?>> {
        val charset = try {
            java.nio.charset.Charset.forName(charsetName)
        } catch (e: Exception) {
            return subs
        }
        val subsDir = java.io.File(cacheDir, "subs").apply { mkdirs() }

        return subs.mapIndexed { index, (uri, name) ->
            try {
                val rawBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@mapIndexed uri to name
                val text = String(rawBytes, charset)
                val outFile = java.io.File(subsDir, "sub_$index.srt")
                outFile.writeText(text, Charsets.UTF_8)
                val outUri = androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", outFile
                )
                outUri to name
            } catch (e: Exception) {
                uri to name
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Пока открыта любая панель — любое нажатие пультом (навигация внутри неё)
        // сбрасывает таймер автозакрытия, чтобы панель не пропадала во время выбора.
        if (currentPanel != Panel.NONE &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_BACK
        ) {
            resetPanelHideTimer()
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN && currentPanel != Panel.CONTROLS) {
                    togglePanel(Panel.CONTROLS)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN && currentPanel != Panel.INFO && currentPanel != Panel.CONTROLS) {
                    togglePanel(Panel.INFO)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (currentPanel == Panel.CONTROLS && isTimeBarFocused()) {
                    val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (event.repeatCount == 0) {
                                seekHoldDirection = direction
                                seekHoldStartedAt = SystemClock.elapsedRealtime()
                                performSeek(prefs.seekStepSeconds * direction)
                                uiHandler.removeCallbacks(seekHoldRunnable)
                                uiHandler.postDelayed(seekHoldRunnable, 400)
                            }
                        }
                        KeyEvent.ACTION_UP -> {
                            seekHoldDirection = 0
                            uiHandler.removeCallbacks(seekHoldRunnable)
                        }
                    }
                    return true
                }
                if (currentPanel == Panel.NONE) {
                    // Не даём ExoPlayer самому перематывать по влево/вправо, когда панель
                    // закрыта — перемотка теперь доступна только через открытую панель,
                    // с фокусом именно на ползунке (иначе легко случайно перемотать видео).
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (currentPanel == Panel.NONE) {
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (event.repeatCount == 0) {
                                centerLongPressTriggered = false
                                uiHandler.removeCallbacks(centerLongPressRunnable)
                                uiHandler.postDelayed(centerLongPressRunnable, OK_LONG_PRESS_MS)
                            }
                        }
                        KeyEvent.ACTION_UP -> {
                            uiHandler.removeCallbacks(centerLongPressRunnable)
                            if (!centerLongPressTriggered) {
                                togglePlayPause()
                            }
                            centerLongPressTriggered = false
                        }
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isTimeBarFocused(): Boolean = binding.seekBar.hasFocus()

    private fun parseExternalPlayerExtras() {
        incomingTitle = intent.getStringExtra("title")
            ?: intent.getStringExtra("android.intent.extra.TITLE")
            ?: intent.getStringExtra("filename")

        val pos = intent.getIntExtra("position", -1)
        if (pos >= 0) externalStartPositionMs = pos.toLong()

        shouldReturnResult = intent.getBooleanExtra("return_result", false)

        val subUris = try {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>("subs")
                ?: (intent.getParcelableArrayExtra("subs")?.mapNotNull { it as? Uri })
        } catch (e: Exception) {
            null
        }.orEmpty()
        val subNames = intent.getStringArrayExtra("subs.name")
        externalSubtitles = subUris.mapIndexed { index, uri -> uri to subNames?.getOrNull(index) }

        val headersArray = intent.getStringArrayExtra("headers")
        externalHeaders = if (headersArray != null) {
            val map = mutableMapOf<String, String>()
            var i = 0
            while (i + 1 < headersArray.size) {
                map[headersArray[i]] = headersArray[i + 1]
                i += 2
            }
            map
        } else {
            emptyMap()
        }
    }

    private fun reportResultAndFinish() {
        if (shouldReturnResult) {
            val result = Intent()
            player?.let {
                result.putExtra("position", it.currentPosition.toInt())
                result.putExtra("duration", it.duration.coerceAtLeast(0).toInt())
            }
            setResult(RESULT_OK, result)
        }
        finish()
    }

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
        updateSeekBar()
    }

    /** Обновляет положение и подпись нашего ползунка на основе текущей позиции плеера. */
    private fun updateSeekBar() {
        val p = player ?: return
        val durMs = p.duration
        if (durMs <= 0) return
        val posMs = p.currentPosition.coerceIn(0, durMs)
        binding.seekBar.max = 1000
        binding.seekBar.progress = ((posMs * 1000) / durMs).toInt()
        binding.textSeekTime.text = "${Formatting.time(posMs)} / ${Formatting.time(durMs)}"
    }

    private fun togglePlayPause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
    }

    private fun togglePanel(panel: Panel) {
        if (currentPanel == panel) hideAllPanels() else showPanel(panel)
    }

    private fun showPanel(panel: Panel) {
        val target = when (panel) {
            Panel.CONTROLS -> binding.panelControls
            Panel.INFO -> binding.panelInfo
            Panel.EPISODES -> binding.panelEpisodes
            Panel.NONE -> null
        } ?: return

        listOf(binding.panelControls, binding.panelInfo, binding.panelEpisodes).forEach { v ->
            if (v !== target && v.visibility == View.VISIBLE) animateHide(v)
        }
        animateShow(target)
        currentPanel = panel

        when (panel) {
            Panel.CONTROLS -> {
                populateAudioRow()
                populateSubsRow()
                updateSeekBar()
                binding.seekBar.requestFocus()
            }
            Panel.EPISODES -> populatePlaylistPanel()
            else -> {}
        }

        uiHandler.removeCallbacks(hidePanelRunnable)
        uiHandler.postDelayed(hidePanelRunnable, PANEL_AUTO_HIDE_MS)
    }

    private fun hideAllPanels() {
        listOf(binding.panelControls, binding.panelInfo, binding.panelEpisodes).forEach {
            if (it.visibility == View.VISIBLE) animateHide(it)
        }
        currentPanel = Panel.NONE
        uiHandler.removeCallbacks(hidePanelRunnable)
        binding.playerView.hideController()
    }

    /** Плавное появление панели (fade-in), вместо мгновенного показа. */
    private fun animateShow(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(180).start()
    }

    /** Плавное скрытие панели (fade-out), вместо мгновенного исчезновения. */
    private fun animateHide(view: View) {
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(150)
            .withEndAction { view.visibility = View.GONE }
            .start()
    }

    /**
     * Сбрасывает таймер автозакрытия панели — вызывается на любое нажатие пультом,
     * пока панель открыта, чтобы она не закрывалась, пока пользователь ей пользуется.
     */
    private fun resetPanelHideTimer() {
        uiHandler.removeCallbacks(hidePanelRunnable)
        if (currentPanel != Panel.NONE) {
            uiHandler.postDelayed(hidePanelRunnable, PANEL_AUTO_HIDE_MS)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * Человекочитаемая подпись дорожки: язык + студия перевода (если она есть в самом
     * файле — обычно зашита в название дорожки в MKV) + пометка [Forced] для
     * принудительных субтитров (обычно перевод только иностранных вставок в фильме).
     * Какая дорожка сейчас выбрана — показывает отдельная точка "●" перед текстом.
     */
    private fun trackDisplayLabel(format: androidx.media3.common.Format, fallbackName: String): String {
        val lang = format.language?.let { languageDisplayName(it) }
        val studio = format.label?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(format.language, ignoreCase = true) }
        val base = when {
            lang != null && studio != null -> "$lang ($studio)"
            lang != null -> lang
            studio != null -> studio
            else -> fallbackName
        }
        val isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
        return if (isForced) "$base • Forced" else base
    }

    private fun languageDisplayName(code: String): String {
        val normalized = code.lowercase()
        val known = mapOf(
            "ru" to "Русский", "rus" to "Русский",
            "en" to "Английский", "eng" to "Английский",
            "uk" to "Украинский", "ukr" to "Украинский",
            "de" to "Немецкий", "ger" to "Немецкий", "deu" to "Немецкий",
            "fr" to "Французский", "fre" to "Французский", "fra" to "Французский",
            "es" to "Испанский", "spa" to "Испанский",
            "it" to "Итальянский", "ita" to "Итальянский",
            "ja" to "Японский", "jpn" to "Японский",
            "ko" to "Корейский", "kor" to "Корейский",
            "zh" to "Китайский", "chi" to "Китайский", "zho" to "Китайский"
        )
        known[normalized]?.let { return it }
        return try {
            val display = java.util.Locale(normalized).getDisplayLanguage(java.util.Locale("ru"))
            if (display.isNotBlank() && !display.equals(normalized, ignoreCase = true)) {
                display.replaceFirstChar { it.uppercase() }
            } else {
                code
            }
        } catch (e: Exception) {
            code
        }
    }

    private fun populateAudioRow() {
        binding.audioRow.removeAllViews()
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        var first = true
        groups.forEach { group ->
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val selected = group.isTrackSelected(trackIndex)
                val label = trackDisplayLabel(format, "Дорожка")
                val btn = Button(this).apply {
                    text = if (selected) "● $label" else label
                    setOnClickListener {
                        val builder = p.trackSelectionParameters.buildUpon()
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                        p.trackSelectionParameters = builder.build()
                        prefs.preferredAudioLanguage = format.language
                        populateAudioRow()
                    }
                }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (!first) lp.marginStart = dpToPx(8)
                btn.layoutParams = lp
                binding.audioRow.addView(btn)
                first = false
            }
        }
    }

    /**
     * Плейлист серий — отдельная панель в правом углу, столбиком, открывается
     * кнопкой "Плейлист". Показывается, только если у торрента больше одного
     * видеофайла.
     */
    private fun populatePlaylistPanel() {
        binding.playlistList.removeAllViews()
        val currentFileName = TorrServerUrlUtils.fileNameOf(streamUrl)
        var first = true
        episodeFiles.forEach { file ->
            val name = file.path.substringAfterLast('/')
            val isCurrent = file.id == currentEpisodeFileId || name == currentFileName
            val btn = Button(this).apply {
                text = if (isCurrent) "● $name" else name
                setOnClickListener {
                    if (!isCurrent) switchToEpisode(file)
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (!first) lp.topMargin = dpToPx(8)
            btn.layoutParams = lp
            binding.playlistList.addView(btn)
            first = false
        }
    }

    private fun populateSubsRow() {
        binding.subsRow.removeAllViews()
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val anySelected = groups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }

        val offBtn = Button(this).apply {
            text = if (!anySelected) "● Выкл" else "Выкл"
            setOnClickListener {
                val builder = p.trackSelectionParameters.buildUpon()
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                p.trackSelectionParameters = builder.build()
                prefs.subtitlesEnabled = false
                prefs.preferredSubtitleLanguage = null
                populateSubsRow()
            }
        }
        binding.subsRow.addView(offBtn)

        groups.forEach { group ->
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val selected = group.isTrackSelected(trackIndex)
                val label = trackDisplayLabel(format, "Субтитры")
                val btn = Button(this).apply {
                    text = if (selected) "● $label" else label
                    setOnClickListener {
                        val builder = p.trackSelectionParameters.buildUpon()
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                        p.trackSelectionParameters = builder.build()
                        prefs.subtitlesEnabled = true
                        prefs.preferredSubtitleLanguage = format.language
                        populateSubsRow()
                    }
                }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginStart = dpToPx(8)
                btn.layoutParams = lp
                binding.subsRow.addView(btn)
            }
        }
    }

    private fun resolveIncomingVideo(): Pair<String, String?>? {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val url = intent.data.toString()
            val hashFromUrl = TorrServerUrlUtils.hashOf(url)
            return url to hashFromUrl
        }
        val extraUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: return null
        val extraHash = intent.getStringExtra(EXTRA_HASH) ?: TorrServerUrlUtils.hashOf(extraUrl)
        return extraUrl to extraHash
    }

    private fun initPlayer() {
        val seekMs = prefs.seekStepSeconds * 1000L

        val rendererMode = when (prefs.audioDecodeMode) {
            0 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            2 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        }
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(rendererMode)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
        if (externalHeaders.isNotEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(externalHeaders)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(this, httpDataSourceFactory)
        )

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekForwardIncrementMs(seekMs)
            .setSeekBackIncrementMs(seekMs)
            .build()

        player = exoPlayer
        binding.playerView.player = exoPlayer

        exoPlayer.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                audioSessionId: Int
            ) {
                setupLoudnessEnhancer(audioSessionId)
            }
        })

        applyTrackPreferences(exoPlayer)

        speedIndex = closestSpeedIndex(prefs.playbackSpeed)
        val startSpeed = SPEEDS[speedIndex]
        exoPlayer.playbackParameters = PlaybackParameters(startSpeed)
        binding.btnSpeed.text = "${startSpeed}x"

        startPlayback(exoPlayer, streamUrl)

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                binding.textError.text = "Не удалось воспроизвести (обрыв связи с TorrServer?)\n" +
                    "Код ошибки: ${error.errorCodeName}"
                binding.errorBanner.visibility = View.VISIBLE
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (currentPanel == Panel.CONTROLS) {
                    populateAudioRow()
                    populateSubsRow()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    val next = if (prefs.autoNextEpisode) nextEpisode() else null
                    if (next != null) {
                        Toast.makeText(this@PlayerActivity, "Следующая серия…", Toast.LENGTH_SHORT).show()
                        switchToEpisode(next)
                    } else {
                        reportResultAndFinish()
                    }
                }
            }
        })

        uiHandler.post(bufferUpdater)
        uiHandler.post(serverStatsUpdater)
    }

    /**
     * Усиление громкости поверх уже декодированного звука — работает независимо от
     * системной громкости ТВ. Пересоздаётся при смене audio session id (например,
     * при переключении аудиодорожки или при смене серии).
     */
    private fun setupLoudnessEnhancer(audioSessionId: Int) {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        if (audioSessionId == android.media.audiofx.AudioEffect.ERROR_BAD_VALUE) return
        val gainDb = prefs.audioGainDb
        try {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(gainDb * 100) // дБ -> миллибелы
                enabled = gainDb > 0
            }
        } catch (e: Exception) {
            loudnessEnhancer = null
        }
    }

    private fun applyTrackPreferences(exoPlayer: ExoPlayer) {
        val trackParams = exoPlayer.trackSelectionParameters.buildUpon()
        prefs.preferredAudioLanguage?.let { trackParams.setPreferredAudioLanguage(it) }
        if (prefs.subtitlesEnabled) {
            prefs.preferredSubtitleLanguage?.let { trackParams.setPreferredTextLanguage(it) }
            trackParams.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        } else {
            trackParams.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        exoPlayer.trackSelectionParameters = trackParams.build()

        // Tunneling — отдельный параметр, живёт только в DefaultTrackSelector.Parameters,
        // а не в базовом TrackSelectionParameters, поэтому применяется через сам селектор.
        val trackSelector = exoPlayer.trackSelector
        if (trackSelector is DefaultTrackSelector) {
            trackSelector.parameters = trackSelector.buildUponParameters()
                .setTunnelingEnabled(prefs.tunnelingEnabled)
                .build()
        }
    }

    private fun startPlayback(exoPlayer: ExoPlayer, url: String) {
        val mediaItem = buildMediaItem(url)

        val startPos = externalStartPositionMs
            ?: if (prefs.resumePlayback) prefs.loadPosition(TorrServerUrlUtils.fileNameOf(url)) else 0L
        externalStartPositionMs = null
        externalSubtitles = emptyList()

        exoPlayer.setMediaItem(mediaItem, startPos)
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
    }

    private fun buildMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (externalSubtitles.isNotEmpty()) {
            val configs = externalSubtitles.mapIndexed { index, (uri, name) ->
                MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(guessSubtitleMime(uri))
                    .setLabel(name ?: "Субтитры ${index + 1}")
                    .setLanguage("ext$index")
                    .build()
            }
            builder.setSubtitleConfigurations(configs)
        }
        return builder.build()
    }

    private fun guessSubtitleMime(uri: Uri): String {
        val path = uri.toString().lowercase()
        return when {
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun closestSpeedIndex(target: Float): Int {
        var bestIndex = 2
        var bestDiff = Float.MAX_VALUE
        SPEEDS.forEachIndexed { index, speed ->
            val diff = kotlin.math.abs(speed - target)
            if (diff < bestDiff) {
                bestDiff = diff
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun retryPlayback() {
        binding.errorBanner.visibility = View.GONE
        player?.let {
            it.prepare()
            it.playWhenReady = true
        }
    }

    private fun restartFromBeginning() {
        player?.let {
            it.seekTo(0)
            it.playWhenReady = true
            updateSeekBar()
        }
        Toast.makeText(this, "Сначала", Toast.LENGTH_SHORT).show()
    }

    private fun adjustDisplayRefreshRate(contentFrameRate: Float) {
        if (contentFrameRate == lastAppliedFrameRate) return
        lastAppliedFrameRate = contentFrameRate

        val display = window?.decorView?.display ?: return
        val currentMode = display.mode
        var bestMode = currentMode
        var bestDiff = Float.MAX_VALUE

        for (mode in display.supportedModes) {
            if (mode.physicalWidth != currentMode.physicalWidth ||
                mode.physicalHeight != currentMode.physicalHeight
            ) continue

            val multiple = Math.round(mode.refreshRate / contentFrameRate).coerceAtLeast(1)
            val diff = kotlin.math.abs(mode.refreshRate - contentFrameRate * multiple)
            if (diff < bestDiff) {
                bestDiff = diff
                bestMode = mode
            }
        }

        if (bestDiff < 0.3f && bestMode.modeId != currentMode.modeId) {
            val attrs = window.attributes
            attrs.preferredDisplayModeId = bestMode.modeId
            window.attributes = attrs
        }
    }

    private fun loadEpisodesInBackground() {
        val h = hash
        val client = statsClient
        if (h == null || client == null) return

        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { try { client.getTorrent(h) } catch (e: Exception) { null } }
            val files = info?.fileStats.orEmpty()
                .filter { isVideoFile(it.path) }
                .sortedWith(Comparator { a, b -> naturalCompare(a.path, b.path) })
            if (files.size > 1) {
                episodeFiles = files
                binding.btnPlaylist.visibility = View.VISIBLE
                if (currentPanel == Panel.EPISODES) {
                    populatePlaylistPanel()
                }
            }
        }
    }

    private fun isVideoFile(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    private fun naturalCompare(a: String, b: String): Int {
        val chunkRegex = Regex("\\d+|\\D+")
        val partsA = chunkRegex.findAll(a).map { it.value }.toList()
        val partsB = chunkRegex.findAll(b).map { it.value }.toList()
        val len = minOf(partsA.size, partsB.size)
        for (i in 0 until len) {
            val pa = partsA[i]
            val pb = partsB[i]
            val cmp = if (pa.firstOrNull()?.isDigit() == true && pb.firstOrNull()?.isDigit() == true) {
                val na = pa.toLongOrNull()
                val nb = pb.toLongOrNull()
                if (na != null && nb != null) na.compareTo(nb) else pa.compareTo(pb)
            } else {
                pa.compareTo(pb)
            }
            if (cmp != 0) return cmp
        }
        return partsA.size.compareTo(partsB.size)
    }

    private fun nextEpisode(): TorrentFileStat? {
        if (episodeFiles.size < 2) return null
        val currentId = currentEpisodeFileId
        val currentIndex = if (currentId != null) {
            episodeFiles.indexOfFirst { it.id == currentId }
        } else {
            val currentFileName = TorrServerUrlUtils.fileNameOf(streamUrl)
            episodeFiles.indexOfFirst { it.path.substringAfterLast('/') == currentFileName }
        }
        if (currentIndex == -1 || currentIndex + 1 >= episodeFiles.size) return null
        return episodeFiles[currentIndex + 1]
    }

    private fun switchToEpisode(file: TorrentFileStat) {
        val h = hash ?: return
        val host = TorrServerUrlUtils.hostOf(streamUrl) ?: return
        val scheme = TorrServerUrlUtils.schemeOf(streamUrl)
        val fileName = file.path.substringAfterLast('/')
        val encodedName = URLEncoder.encode(fileName, "UTF-8")
        val newUrl = "$scheme://$host/stream/$encodedName?link=$h&index=${file.id}&play"

        player?.let { prefs.savePosition(fileName, it.currentPosition) }

        streamUrl = newUrl
        currentEpisodeFileId = file.id
        hideAllPanels()
        binding.errorBanner.visibility = View.GONE

        player?.let { startPlayback(it, streamUrl) }
    }

    private fun cycleAspect() {
        aspectIndex = (aspectIndex + 1) % RESIZE_MODES.size
        binding.playerView.resizeMode = RESIZE_MODES[aspectIndex]
        binding.btnAspect.text = RESIZE_LABELS[aspectIndex]
        prefs.resizeMode = RESIZE_MODES[aspectIndex]
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.size
        val speed = SPEEDS[speedIndex]
        player?.playbackParameters = PlaybackParameters(speed)
        binding.btnSpeed.text = "${speed}x"
        prefs.playbackSpeed = speed
    }

    override fun onStop() {
        super.onStop()
        player?.let { prefs.savePosition(TorrServerUrlUtils.fileNameOf(streamUrl), it.currentPosition) }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
