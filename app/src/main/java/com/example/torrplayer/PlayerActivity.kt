package com.example.app

import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.app.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private lateinit var streamUrl: String
    private var hash: String? = null
    private var title: String? = null

    private lateinit var prefs: AppPreferences
    private var statsClient: TorrServiceClient? = null

    private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 2

    private val RESIZE_MODES = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val RESIZE_LABELS = arrayOf("Fit", "Fill", "Zoom")
    private var aspectIndex = 0

    private var episodeFiles = listOf<TorrentFileStat>()
    private var lastAppliedFrameRate = 0f
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Предотвращаем гашение экрана во время просмотра
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = AppPreferences(this)
        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: run {
            finish()
            return
        }
        hash = intent.getStringExtra(EXTRA_HASH)
        title = intent.getStringExtra(EXTRA_TITLE)

        setupUI()
        initPlayer()
        loadEpisodesInBackground()
    }

    private fun setupUI() {
        binding.textTitle.text = title ?: ""
        binding.textTitle.visibility = if (title.isNull_or_empty()) View.GONE else View.VISIBLE

        binding.btnSpeed.setOnClickListener { cycleSpeed() }
        binding.btnAspect.setOnClickListener { cycleAspect() }
        binding.btnAudio.setOnClickListener { showAudioTracksDialog() }
        binding.btnSubtitles.setOnClickListener { showSubtitleTracksDialog() }
        binding.btnEpisodes.setOnClickListener { showEpisodesDialog() }
        binding.btnRetry.setOnClickListener { retryPlayback() }

        // Инициализация отображения скорости и аспектов из настроек
        speedIndex = closestSpeedIndex(prefs.playbackSpeed)
        aspectIndex = prefs.resizeMode.coerceIn(0, RESIZE_MODES.size - 1)
        binding.btnAspect.text = RESIZE_LABELS[aspectIndex]
        binding.playerView.resizeMode = RESIZE_MODES[aspectIndex]
    }

    private fun initPlayer() {
        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            playbackParameters = PlaybackParameters(SPEEDS[speedIndex])
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.pixelWidthHeightRatio > 0f) {
                        // Примерная оценка кадров в секунду для AFR
                        adjustDisplayRefreshRate(24f)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    binding.errorBanner.visibility = View.VISIBLE
                    binding.errorText.text = error.localizedMessage ?: "Ошибка воспроизведения"
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        val next = nextEpisode()
                        if (next != null) {
                            switchToEpisode(next)
                        } else {
                            finish()
                        }
                    }
                }
            })
        }

        player = exoPlayer
        binding.playerView.player = exoPlayer

        startPlayback(exoPlayer, streamUrl)
    }

    private fun startPlayback(exoPlayer: ExoPlayer, url: String) {
        val mediaItem = MediaItem.fromUri(Uri.parse(url))
        exoPlayer.setMediaItem(mediaItem)

        // Восстановление позиции
        if (prefs.resumePlayback) {
            val savedPos = prefs.getPosition(url)
            if (savedPos > 0) {
                exoPlayer.seekTo(savedPos)
            }
        }

        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun showAudioTracksDialog() {
        val p = player ?: return
        val currentTracks = p.currentTracks
        val audioGroups = currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

        if (audioGroups.isEmpty()) return

        val trackLabels = mutableListOf<String>()
        val trackOverrides = mutableListOf<TrackSelectionOverride?>()

        trackLabels.add("По умолчанию")
        trackOverrides.add(null)

        for (group in audioGroups) {
            val mediaTrackGroup = group.mediaTrackGroup
            for (i in 0 until mediaTrackGroup.length) {
                val format = mediaTrackGroup.getFormat(i)
                val label = trackDisplayLabel(format.language, format.label, "Аудио дорожка #${i + 1}")
                trackLabels.add(label)
                trackOverrides.add(TrackSelectionOverride(mediaTrackGroup, i))
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Выберите аудиодорожку")
            .setItems(trackLabels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                val override = trackOverrides[which]
                p.trackSelectionParameters = if (override != null) {
                    p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(override)
                        .build()
                } else {
                    p.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .build()
                }
            }
            .show()
    }

    private fun showSubtitleTracksDialog() {
        val p = player ?: return
        val currentTracks = p.currentTracks
        val subGroups = currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

        val trackLabels = mutableListOf<String>()
        val trackOverrides = mutableListOf<TrackSelectionOverride?>()

        trackLabels.add("Отключить субтитры")
        trackOverrides.add(null)

        for (group in subGroups) {
            val mediaTrackGroup = group.mediaTrackGroup
            for (i in 0 until mediaTrackGroup.length) {
                val format = mediaTrackGroup.getFormat(i)
                val label = trackDisplayLabel(format.language, format.label, "Субтитры #${i + 1}")
                trackLabels.add(label)
                trackOverrides.add(TrackSelectionOverride(mediaTrackGroup, i))
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Выберите субтитры")
            .setItems(trackLabels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                val override = trackOverrides[which]
                if (override == null) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                } else {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(override)
                        .build()
                }
            }
            .show()
    }

    private fun trackDisplayLabel(lang: String?, label: String?, fallback: String): String {
        if (!label.isNull_or_empty()) return label
        if (!lang.isNull_or_empty()) {
            val loc = Locale.forLanguageTag(lang)
            return loc.getDisplayLanguage(Locale("ru"))
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
        }
        return fallback
    }

    private fun guessSubtitleMime(uri: Uri): String {
        val path = uri.path?.lowercase() ?: return MimeTypes.APPLICATION_SUBRIP
        return when {
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.size
        val targetSpeed = SPEEDS[speedIndex]
        player?.playbackParameters = PlaybackParameters(targetSpeed)
        prefs.playbackSpeed = targetSpeed
        binding.btnSpeed.text = "${targetSpeed}x"
    }

    private fun cycleAspect() {
        aspectIndex = (aspectIndex + 1) % RESIZE_MODES.size
        val mode = RESIZE_MODES[aspectIndex]
        binding.playerView.resizeMode = mode
        binding.btnAspect.text = RESIZE_LABELS[aspectIndex]
        prefs.resizeMode = mode
    }

    private fun closestSpeedIndex(speed: Float): Int {
        var closest = 2 // 1.0f по умолчанию
        var minDiff = Float.MAX_VALUE
        for (i in SPEEDS.indices) {
            val diff = Math.abs(SPEEDS[i] - speed)
            if (diff < minDiff) {
                minDiff = diff
                closest = i
            }
        }
        return closest
    }

    /**
     * Автоматическая подстройка частоты обновления экрана (AFR / Auto Frame Rate)
     * под частоту кадров воспроизводимого видео.
     */
    private fun adjustDisplayRefreshRate(videoFrameRate: Float) {
        if (!prefs.autoFrameRateEnabled || Math.abs(lastAppliedFrameRate - videoFrameRate) < 0.1f) return
        lastAppliedFrameRate = videoFrameRate

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val window = window ?: return
            val display = window.windowManager.defaultDisplay ?: return
            val supportedModes = display.supportedModes

            var bestMode: Display.Mode? = null
            var minDiff = Float.MAX_VALUE

            for (mode in supportedModes) {
                val diff = Math.abs(mode.refreshRate - videoFrameRate)
                if (diff < minDiff) {
                    minDiff = diff
                    bestMode = mode
                }
            }

            bestMode?.let {
                val lp = window.attributes
                lp.preferredDisplayModeId = it.modeId
                window.attributes = lp
            }
        }
    }

    private fun loadEpisodesInBackground() {
        val h = hash ?: return
        val client = statsClient ?: return
        lifecycleScope.launch {
            try {
                val torrent = client.getTorrent(h)
                val files = torrent?.fileStats.orEmpty().filter { file ->
                    val ext = file.path.substringAfterLast('.', "").lowercase()
                    VIDEO_EXTENSIONS.contains(ext)
                }
                episodeFiles = files
                binding.btnEpisodes.visibility = if (files.size > 1) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                binding.btnEpisodes.visibility = View.GONE
            }
        }
    }

    private fun currentFileIndex(): Int {
        if (episodeFiles.isEmpty()) return -1
        return episodeFiles.indexOfFirst { file ->
            val encodedPath = URLEncoder.encode(file.path, "UTF-8").replace("+", "%20")
            streamUrl.contains(encodedPath) || streamUrl.endsWith(file.id.toString())
        }
    }

    private fun nextEpisode(): TorrentFileStat? {
        val index = currentFileIndex()
        if (index != -1 && index + 1 < episodeFiles.size) {
            return episodeFiles[index + 1]
        }
        return null
    }

    private fun switchToEpisode(file: TorrentFileStat) {
        saveCurrentPosition()
        val baseHost = TorrServerUrlUtils.baseUrlOf(streamUrl) ?: return
        val encodedPath = URLEncoder.encode(file.path, "UTF-8").replace("+", "%20")
        val newUrl = "$baseHost/stream/$encodedPath?link=$hash&index=${file.id}&play"

        streamUrl = newUrl
        binding.textTitle.text = file.path.substringAfterLast('/')
        binding.textTitle.visibility = View.VISIBLE
        uiHandler.postDelayed({ binding.textTitle.visibility = View.GONE }, 5000)

        player?.let { startPlayback(it, newUrl) }
    }

    private fun showEpisodesDialog() {
        if (episodeFiles.isEmpty()) return
        val names = episodeFiles.map { it.path.substringAfterLast('/') }.toTypedArray()
        val currentIndex = currentFileIndex()

        AlertDialog.Builder(this)
            .setTitle("Выберите серию")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                dialog.dismiss()
                if (which != currentIndex) {
                    switchToEpisode(episodeFiles[which])
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun retryPlayback() {
        binding.errorBanner.visibility = View.GONE
        player?.let {
            it.prepare()
            it.playWhenReady = true
        }
    }

    private fun saveCurrentPosition() {
        val p = player ?: return
        if (prefs.resumePlayback && p.duration > 0) {
            val pos = p.currentPosition
            // Не сохраняем позицию, если осталась менее 10 секунд до конца
            if (pos < p.duration - 10_000) {
                prefs.savePosition(streamUrl, pos)
            } else {
                prefs.clearPosition(streamUrl)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentPosition()
        uiHandler.removeCallbacksAndMessages(null)
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_HASH = "extra_hash"
        const val EXTRA_TITLE = "extra_title"
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v")

        fun start(context: Context, url: String, hash: String? = null, title: String? = null) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_STREAM_URL, url)
                putExtra(EXTRA_HASH, hash)
                putExtra(EXTRA_TITLE, title)
            }
            context.startActivity(intent)
        }
    }
}
