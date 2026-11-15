package com.rokid.spatialhost.video

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Low-latency oriented video player.
 *
 * Phase 1: ExoPlayer with a tight load-control window (small buffers → short
 * glass-to-glass latency for live streams) rendering straight into a Surface
 * that the renderer wraps as an external texture (zero-copy).
 *
 * Latency tuning notes:
 *  - RTSP/TS sources: [prepare] with [lowLatency]=true keeps buffer targets
 *    at 0.5–1.5 s.
 *  - LL-HLS: enable chunkless preparation + preload control in a later pass.
 */
class StreamPlayer(context: Context) {

    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null

    var playing: Boolean = false
        private set

    var onError: ((PlaybackException) -> Unit)? = null
    var onFirstFrame: (() -> Unit)? = null

    fun setSurface(surface: Surface?) {
        ensurePlayer().setVideoSurface(surface)
    }

    fun prepare(url: String, lowLatency: Boolean = true) {
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        playing = true
        Log.i(TAG, "prepare($url, lowLatency=$lowLatency)")
    }

    fun pause() {
        player?.playWhenReady = false
        playing = false
    }

    fun resume() {
        player?.playWhenReady = true
        playing = true
    }

    fun release() {
        player?.release()
        player = null
        playing = false
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        check(Looper.myLooper() != null) { "StreamPlayer must be created on a looper thread" }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 500,
                /* maxBufferMs = */ 1_500,
                /* bufferForPlaybackMs = */ 250,
                /* bufferForPlaybackAfterRebufferMs = */ 500,
            )
            .build()
        val p = ExoPlayer.Builder(appContext)
            .setLoadControl(loadControl)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "player error: ${error.errorCodeName}")
                onError?.invoke(error)
            }

            override fun onRenderedFirstFrame() {
                onFirstFrame?.invoke()
            }
        })
        player = p
        return p
    }

    companion object {
        private const val TAG = "StreamPlayer"
    }
}
