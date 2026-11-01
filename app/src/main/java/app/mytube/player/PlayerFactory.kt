package app.mytube.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

object PlayerFactory {
    /**
     * [shortForm] tunes the buffer for Shorts: start after half a second of video instead of a
     * full second, and don't hold minutes of a clip that will be swiped past.
     */
    fun create(context: Context, shortForm: Boolean = false): ExoPlayer =
        ExoPlayer.Builder(context)
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(
                // Generous buffer: a fast connection means we can stay well ahead of playback.
                DefaultLoadControl.Builder()
                    .apply {
                        if (shortForm) setBufferDurationsMs(15_000, 60_000, 500, 1_000)
                        else setBufferDurationsMs(30_000, 120_000, 1_000, 2_000)
                    }
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
}
