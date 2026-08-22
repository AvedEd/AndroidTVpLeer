package com.example.torrplayer.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderFactory
import androidx.media3.decoder.DefaultDecoderFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.video.VideoSink
import com.example.torrplayer.prefs.AppPrefs

@UnstableApi
object DecoderHelper {

    fun createRenderersFactory(
        context: Context,
        prefs: AppPrefs,
        forceMode: Int? = null
    ): DefaultRenderersFactory {
        val mode = forceMode ?: prefs.decoderMode
        val isHW = mode == 0

        val factory = DefaultRenderersFactory(context)

        if (isHW) {
            factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            factory.setEnableDecoderFallback(true)

            factory.setVideoSink(
                VideoSink.Builder()
                    .setDecoderFactory(
                        DefaultDecoderFactory(
                            listOf(
                                DecoderFactory.VIDEO_MEDIA_CODEC,
                                DecoderFactory.VIDEO_SOFTWARE
                            )
                        )
                    )
                    .build()
            )
        } else {
            factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            factory.setEnableDecoderFallback(false)

            factory.setVideoSink(
                VideoSink.Builder()
                    .setDecoderFactory(
                        DefaultDecoderFactory(
                            listOf(
                                DecoderFactory.VIDEO_SOFTWARE
                            )
                        )
                    )
                    .build()
            )
        }

        return factory
    }

    fun getDecoderModeName(mode: Int): String {
        return when (mode) {
            0 -> "Аппаратный (HW)"
            1 -> "Программный (SW)"
            else -> "Неизвестно"
        }
    }

    fun getDecoderModeShort(mode: Int): String {
        return when (mode) {
            0 -> "HW"
            1 -> "SW"
            else -> "?"
        }
    }
}
