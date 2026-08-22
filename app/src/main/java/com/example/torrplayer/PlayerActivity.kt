package com.example.torrplayer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.example.torrplayer.databinding.ActivityPlayerBinding
import com.example.torrplayer.player.DecoderHelper
import com.example.torrplayer.prefs.AppPrefs
import com.example.torrplayer.util.TorrServerUrlUtils

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_HASH = "extra_hash"
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: AppPrefs
    private var player: ExoPlayer? = null
    private var streamUrl: String = ""
    private var hash: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
        hash = intent.getStringExtra(EXTRA_HASH)

        initPlayer()
    }

    private fun initPlayer() {
        // === СОЗДАНИЕ ФАБРИКИ РЕНДЕРЕРОВ С ПОДДЕРЖКОЙ HW/SW ===
        val renderersFactory = DecoderHelper.createRenderersFactory(this, prefs)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30000, 90000, 5000, 10000)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(this, httpDataSourceFactory)
        )

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        player = exoPlayer
        binding.playerView.player = exoPlayer

        // Подготовка воспроизведения
        val mediaItem = androidx.media3.common.MediaItem.fromUri(streamUrl)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        // === ОБРАБОТЧИК ОШИБОК С AUTOMATIC FALLBACK ===
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val errorCode = error.errorCode

                val isHWError = (errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                        errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                        errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)

                if (prefs.decoderMode == 0 && isHWError && !prefs.isDecoderFallback) {
                    prefs.isDecoderFallback = true
                    Toast.makeText(
                        this@PlayerActivity,
                        "Ошибка HW-декодера, переключаю на SW",
                        Toast.LENGTH_LONG
                    ).show()
                    recreatePlayer(forceSW = true)
                    return
                }

                Toast.makeText(
                    this@PlayerActivity,
                    "Ошибка воспроизведения: ${error.errorCodeName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun recreatePlayer(forceSW: Boolean = false) {
        val currentUrl = streamUrl
        val position = player?.currentPosition ?: 0L
        val wasPlaying = player?.playWhenReady ?: true

        player?.release()
        player = null

        if (forceSW) {
            prefs.decoderMode = 1
        }

        initPlayer()

        player?.seekTo(position)
        player?.playWhenReady = wasPlaying
    }

    override fun onStop() {
        super.onStop()
        player?.let {
            val fileName = TorrServerUrlUtils.fileNameOf(streamUrl)
            prefs.savePosition(fileName, it.currentPosition)
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
