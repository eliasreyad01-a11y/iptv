package com.lite.iptv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * One tuned player + the logic every IPTV app needs: tolerant MPEG-TS parsing, HLS without waiting for a full
 * playlist, no live-speed tricks (they cause stutter), and silent retries that rotate User-Agent and fall back
 * to HLS when a link has no extension. Used by both the preview pane and the full-screen player.
 */
@OptIn(UnstableApi::class)
class Streamer(ctx: Context) {
    companion object {
        private val UAS = listOf(Net.UA, "okhttp/4.12.0", "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36", "ExoPlayer")
        private const val TS_FLAGS = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
    }

    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(UAS[0]).setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000).setReadTimeoutMs(12_000)

    private val hls = HlsMediaSource.Factory(http)
        .setAllowChunklessPreparation(true)
        .setExtractorFactory(DefaultHlsExtractorFactory(TS_FLAGS, false))

    private val progressive = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(http,
        DefaultExtractorsFactory().setTsExtractorFlags(TS_FLAGS).setConstantBitrateSeekingEnabled(true))

    val p: ExoPlayer

    private val h = Handler(Looper.getMainLooper())
    private var retryRun: Runnable? = null
    private var cur = ""
    private var startMs = C.TIME_UNSET
    private var sub: String? = null
    private var attempt = 0
    private var asHls = false

    init {
        val b = when (Store.buffer) {            // min, max, start, after-rebuffer (ms)
            0 -> intArrayOf(3_000, 10_000, 500, 1_000)
            2 -> intArrayOf(20_000, 60_000, 2_000, 5_000)
            else -> intArrayOf(15_000, 50_000, 1_500, 4_000)
        }
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(b[0], b[1], b[2], b[3])
            .setTargetBufferBytes(24 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val ex = DefaultExtractorsFactory().setTsExtractorFlags(TS_FLAGS).setConstantBitrateSeekingEnabled(true)
        p = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http, ex))
            .setLoadControl(lc)
            .build()
    }

    fun play(raw: String, start: Long = C.TIME_UNSET, subUrl: String? = null) {
        cancel()
        cur = raw; startMs = start; sub = subUrl; attempt = 0; asHls = false
        load()
    }

    private fun load() {
        val (u, hd) = splitHeaders(cur)
        http.setUserAgent(hd["User-Agent"] ?: UAS[attempt % UAS.size])
        http.setDefaultRequestProperties(hd.filterKeys { it != "User-Agent" })
        val isHls = asHls || u.contains(".m3u8", true)
        val mi = MediaItem.Builder().setUri(u)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
        sub?.let { s ->
            val mime = if (s.contains(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            mi.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(s)).setMimeType(mime).setLanguage("und")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        }
        if (isHls) {
            mi.setMimeType(MimeTypes.APPLICATION_M3U8)
            p.setMediaSource(hls.createMediaSource(mi.build()), startMs)
        } else try {
            p.setMediaItem(mi.build(), startMs)
        } catch (e: IllegalStateException) {            // link type the player can't tell: read it as a plain stream
            p.setMediaSource(progressive.createMediaSource(mi.setMimeType(MimeTypes.VIDEO_MP2T).build()), startMs)
        }
        p.prepare()
        p.playWhenReady = true
    }

    /** Returns true when it has already scheduled a silent retry; false means give up (show the error). */
    fun onError(e: PlaybackException): Boolean {
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { p.seekToDefaultPosition(); p.prepare(); return true }
        if (cur.isEmpty() || attempt >= 5) return false
        val parsing = e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        if (parsing && !asHls) asHls = true
        attempt++
        val r = Runnable { load() }
        retryRun = r
        h.postDelayed(r, 600L * attempt)
        return true
    }

    fun onReady() { attempt = 0 }

    fun cancel() { retryRun?.let { h.removeCallbacks(it) }; retryRun = null }

    fun release() { cancel(); p.release() }
}
