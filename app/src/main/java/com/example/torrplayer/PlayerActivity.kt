package com.example.torrplayer

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.torrplayer.databinding.ActivityPlayerBinding
import com.example.torrplayer.prefs.AppPrefs
import com.example.torrplayer.torrserver.TorrServerClient
import com.example.torrplayer.util.Formatting
import com.example.torrplayer.util.TorrServerUrlUtils
import com.example.torrplayer.util.UpdateChecker
import com.example.torrplayer.util.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_HASH = "extra_hash"

        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        private const val PANEL_AUTO_HIDE_MS = 6000L

        private val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        private val RESIZE_LABELS = arrayOf("По размеру", "Обрезать", "Растянуть")

        private const val SEEK_TICK_MS = 300L
        private const val SEEK_ACCEL_STAGE1_MS = 1500L
        private const val SEEK_ACCEL_STAGE2_MS = 4000L

        private const val MIN_BUFFER_MS = 30_000
        private const val MAX_BUFFER_MS = 90_000
        private const val BUFFER_FOR_PLAYBACK_MS = 5_000
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 10_000

        private const val HTTP_CONNECT_TIMEOUT_MS = 15_000
        private const val HTTP_READ_TIMEOUT_MS = 20_000
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: AppPrefs
    private var statsClient: TorrServerClient? = null
    private var player: ExoPlayer? = null

    private var streamUrl: String = ""
    private var hash: String? = null
    private var speedIndex = 2
    private var aspectIndex = 0
    private var panelVisible = false
    private var lastAppliedFrameRate = 0f
    private var pendingUpdate: UpdateInfo? = null

    private val uiHandler = Handler(Looper.getMainLooper())

    private val hidePanelRunnable = Runnable { hidePanel() }

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

    private val bufferUpdater = object : Runnable {
        override fun run() {
            player?.let {
                val bufferedPct = it.bufferedPercentage
                val posMs = it.currentPosition
                val durMs = if (it.duration > 0) it.duration else 0
                binding.textBuffer.text = "Буфер: $bufferedPct%\n" +
                    "${Formatting.time(posMs)} / ${Formatting.time(durMs)}"

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
            android.widget.Toast.makeText(this, R.string.no_video_link, android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        streamUrl = resolved.first
        hash = resolved.second

        TorrServerUrlUtils.hostOf(streamUrl)?.let { host ->
            statsClient = TorrServerClient(host, TorrServerUrlUtils.schemeOf(streamUrl))
        }

        binding.infoPanel.visibility = View.GONE
        binding.textBuffer.visibility = if (prefs.showBufferOverlay) View.VISIBLE else View.GONE
        binding.textVideoInfo.visibility = if (prefs.showBufferOverlay) View.VISIBLE else View.GONE

        binding.btnAudio.setOnClickListener { showTrackPicker(C.TRACK_TYPE_AUDIO, "Аудио дорожка") }
        binding.btnSubs.setOnClickListener { showTrackPicker(C.TRACK_TYPE_TEXT, "Субтитры") }
        binding.btnSpeed.setOnClickListener { cycleSpeed() }
        binding.btnRetry.setOnClickListener { retryPlayback() }
        binding.btnAspect.setOnClickListener { cycleAspect() }
        binding.btnUpdate.setOnClickListener { pendingUpdate?.let { u -> installUpdate(u) } }

        aspectIndex = RESIZE_MODES.indexOf(prefs.resizeMode).coerceAtLeast(0)
        binding.playerView.resizeMode = RESIZE_MODES[aspectIndex]
        binding.btnAspect.text = RESIZE_LABELS[aspectIndex]

        checkForUpdateInBackground()

        initPlayer()
    }

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

        return super.dispatchKeyEvent(event)
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

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

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

        val trackParams = exoPlayer.trackSelectionParameters.buildUpon()
        prefs.preferredAudioLanguage?.let { trackParams.setPreferredAudioLanguage(it) }
        if (prefs.subtitlesEnabled) {
            prefs.preferredSubtitleLanguage?.let { trackParams.setPreferredTextLanguage(it) }
            trackParams.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        } else {
            trackParams.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        exoPlayer.trackSelectionParameters = trackParams.build()

        speedIndex = closestSpeedIndex(prefs.playbackSpeed)
        val startSpeed = SPEEDS[speedIndex]
        exoPlayer.playbackParameters = PlaybackParameters(startSpeed)
        binding.btnSpeed.text = "${startSpeed}x"

        val mediaItem = MediaItem.fromUri(streamUrl)
        val startPos = if (prefs.resumePlayback) prefs.loadPosition(streamUrl) else 0L

        exoPlayer.setMediaItem(mediaItem, startPos)
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                binding.textBuffer.text = "Не удалось воспроизвести (обрыв связи с TorrServer?)\n" +
                    "Код ошибки: ${error.errorCodeName}"
                binding.textBuffer.visibility = View.VISIBLE
                binding.btnRetry.visibility = View.VISIBLE
                showPanel()
            }
        })

        uiHandler.post(bufferUpdater)
        uiHandler.post(serverStatsUpdater)
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
        binding.btnRetry.visibility = View.GONE
        binding.textBuffer.text = "Повторное подключение…"
        player?.let {
            it.prepare()
            it.playWhenReady = true
        }
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
