package com.tvkanal.app

import android.app.Activity
import android.content.Context
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.Executors

/**
 * Alin's'i TV'nin kendi canlı yayın altyapısına (TV Input Framework) bağlar.
 * TV'nin "Canlı TV / Kanallar" uygulaması bir Alin's kanalını açınca görüntü buradan gelir.
 */
class AlinsTvInputService : TvInputService() {
    override fun onCreateSession(inputId: String): Session = AlinsSession(this)
}

private class AlinsSession(private val ctx: Context) : TvInputService.Session(ctx) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var tuneToken = 0
    private var volume = 1f

    override fun onSetSurface(s: Surface?): Boolean {
        surface = s
        player?.setVideoSurface(s)
        return true
    }

    override fun onSetStreamVolume(v: Float) {
        volume = v
        player?.volume = v
    }

    override fun onSetCaptionEnabled(enabled: Boolean) {}

    override fun onTune(channelUri: Uri): Boolean {
        val token = ++tuneToken
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
        io.execute {
            val id = SystemChannels.idFromChannelUri(ctx, channelUri)
            val repo = ChannelRepository(ctx)
            val ch = id?.let { cid -> SystemChannels.allChannels(ctx).firstOrNull { it.id == cid } }
            val url = ch?.let { StreamResolver.resolve(it, UA) }
            main.post {
                if (token != tuneToken) return@post
                if (ch == null || url.isNullOrBlank()) {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                } else start(ch, url, token, triedFallback = false)
            }
        }
        return true
    }

    private fun start(ch: Channel, url: String, token: Int, triedFallback: Boolean) {
        val p = player ?: ExoPlayer.Builder(ctx).build().also { pl ->
            pl.setVideoSurface(surface)
            pl.volume = volume
            player = pl
        }
        p.clearMediaItems()
        val ua = ch.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: UA
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setDefaultRequestProperties(ch.headers.filterKeys { !it.equals("User-Agent", true) })
            .setAllowCrossProtocolRedirects(true)
        p.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(url)))
        listener?.let { p.removeListener(it) }
        listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (token != tuneToken) return
                if (state == Player.STATE_READY) {
                    if (ch.isRadio) notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY)
                    else notifyVideoAvailable()
                }
                if (state == Player.STATE_BUFFERING) notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (token != tuneToken) return
                if (!triedFallback && ch.fallbackUrl.isNotBlank()) start(ch, ch.fallbackUrl, token, true)
                else {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
                    // Pes etme: kısa süre sonra yeniden dene
                    main.postDelayed({ if (token == tuneToken) start(ch, url, token, false) }, 5000)
                }
            }
        }.also { p.addListener(it) }
        p.prepare()
        p.playWhenReady = true
    }

    private var listener: Player.Listener? = null

    override fun onRelease() {
        tuneToken++
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        io.shutdownNow()
    }

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
    }
}

/**
 * TV'nin "kaynak kurulumu" ekranı: Alin's kanallarını sisteme ekler ve geri döner.
 * (TV'nin Canlı TV uygulaması yeni kaynağı kurarken bunu açar.)
 */
class TvSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF0E1116.toInt())
            text = "Alin's kanalları TV'ye ekleniyor…"
        }
        setContentView(text)
        Executors.newSingleThreadExecutor().execute {
            val n = SystemChannels.syncAll(this, force = true)
            runOnUiThread {
                text.text = "Alin's: $n kanal TV'ye eklendi"
                text.postDelayed({ setResult(RESULT_OK); finish() }, 1500)
            }
        }
    }
}
