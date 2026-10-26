package com.tvkanal.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var listPanel: View
    private lateinit var categoryList: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var infoBar: View
    private lateinit var qualityPanel: View
    private lateinit var qualityList: RecyclerView
    private lateinit var radioCard: View
    private lateinit var radioName: TextView
    private lateinit var natureView: View
    private lateinit var bgVideo: PlayerView
    private lateinit var fireCredit: View
    private lateinit var infoNumber: TextView
    private lateinit var infoName: TextView
    private lateinit var statusText: TextView

    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("tv", Context.MODE_PRIVATE) }

    private var channels: List<Channel> = emptyList()
    private var categories: List<String> = emptyList()
    private var shownChannels: List<Int> = emptyList() // seçili kategorideki kanalların indeksleri
    private var current = -1
    private var retried = false
    private var lastBackPress = 0L

    private val categoryAdapter = RowAdapter(onClick = { focusChannels() }, onFocus = { showCategory(it) })
    private val channelAdapter = RowAdapter(onClick = { pos ->
        shownChannels.getOrNull(pos)?.let { play(it); hideList() }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        TrustFix.install(this) // eski Android TV'lerde yeni sertifikalar
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        listPanel = findViewById(R.id.listPanel)
        categoryList = findViewById(R.id.categoryList)
        channelList = findViewById(R.id.channelList)
        infoBar = findViewById(R.id.infoBar)
        infoNumber = findViewById(R.id.infoNumber)
        infoName = findViewById(R.id.infoName)
        statusText = findViewById(R.id.statusText)
        qualityPanel = findViewById(R.id.qualityPanel)
        qualityList = findViewById(R.id.qualityList)
        radioCard = findViewById(R.id.radioCard)
        radioName = findViewById(R.id.radioName)
        natureView = findViewById(R.id.natureView)
        watermark = findViewById(R.id.watermark)
        youTube = YouTubeLive(findViewById(R.id.ytView)) {
            watermark.visibility = View.VISIBLE
            channels.getOrNull(current)?.let { prefs.edit().remove("yt_${it.id}").apply() }
            showStatus("${channels.getOrNull(current)?.name ?: "Kanal"} şu an açılamıyor\n(Kanal değiştirmek için ▲ ▼)")
        }
        bgVideo = findViewById(R.id.bgVideo)
        fireCredit = findViewById(R.id.fireCredit)
        qualityList.layoutManager = LinearLayoutManager(this)
        qualityList.adapter = qualityAdapter

        categoryList.layoutManager = LinearLayoutManager(this)
        categoryList.adapter = categoryAdapter
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = channelAdapter

        loadChannels()
        AutoStart.startWakeService(this)
        askOverlayPermissionOnce()
    }

    /** Otomatik açılış için "diğer uygulamaların üzerinde göster" izni ilk açılışta bir kez istenir. */
    private fun askOverlayPermissionOnce() {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) return
        if (prefs.getBoolean("askedOverlay", false)) return
        prefs.edit().putBoolean("askedOverlay", true).apply()
        Toast.makeText(this,
            "TV açılınca Alin's'in kendiliğinden açılması için açılan ekranda Alin's'e izin verin",
            Toast.LENGTH_LONG).show()
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    // ---- Kanal listesi ----

    private fun loadChannels() {
        showStatus("Alin's\nKanallar yükleniyor…")
        io.execute {
            val result = runCatching { ChannelRepository(this).load() }
            main.post {
                if (isFinishing || isDestroyed) return@post
                result.onSuccess { r ->
                    hideStatus()
                    if (r.source != ChannelRepository.Source.REMOTE && BuildConfig.CHANNELS_URL.isNotBlank()) {
                        Toast.makeText(this, "Sunucuya ulaşılamadı, kayıtlı liste kullanılıyor", Toast.LENGTH_LONG).show()
                    }
                    setChannels(r.channels)
                }.onFailure {
                    showStatus("Kanal listesi yüklenemedi")
                }
            }
        }
    }

    private fun setChannels(list: List<Channel>) {
        channels = list
        categories = listOf("Tümü") + list.map { it.category }.distinct()
        categoryAdapter.submit(categories.map { RowAdapter.Row("", it) })
        showCategory(0)
        if (list.isEmpty()) {
            showStatus("Listede kanal yok")
            return
        }
        val lastId = prefs.getString("last", null)
        val start = list.indexOfFirst { it.id == lastId }.takeIf { it >= 0 } ?: 0
        play(start)
    }

    private fun showCategory(pos: Int) {
        val cat = categories.getOrNull(pos) ?: return
        categoryAdapter.selected = pos
        shownChannels = channels.indices.filter { pos == 0 || channels[it].category == cat }
        channelAdapter.submit(shownChannels.map { RowAdapter.Row("${it + 1}", channels[it].name) })
        channelAdapter.selected = shownChannels.indexOf(current)
    }

    // ---- Oynatma ----

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        // TV'nin ekran çözünürlüğünü algıla: 720p ekrana 1080p, 1080p ekrana 4K yayın
        // gönderip boşuna internet harcama; ekranın kaldırabildiği en iyi kaliteyi seç.
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setViewportSizeToPhysicalDisplaySize(this@MainActivity, true)
                    .setForceHighestSupportedBitrate(false)
                    // İnternet yavaşlasa da 480p'nin altına düşme
                    // (yayında 480p ve üstü yoksa en yakın kalite kullanılır)
                    .setMinVideoSize(0, 480)
                    // Ses: birden fazla dil varsa Türkçe izi seç.
                    // Ses kalitesi sınırı aşağıda internet hızına göre ayarlanır.
                    .setPreferredAudioLanguages("tr", "tur")
                    .setMaxAudioChannelCount(Int.MAX_VALUE)
                    .setMaxAudioBitrate(Int.MAX_VALUE)
            )
        }
        audioTier = AUDIO_TIER_HIGH
        lastAudioTierChange = 0L
        // İlk saniyelerde düşük kaliteden başlamasın diye TV'ye uygun bir başlangıç hızı tahmini
        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(8_000_000L)
            .build()
        // İnternet hızı her ölçüldüğünde ses kalitesini hıza göre ayarla
        bandwidthMeter.addEventListener(main) { _, _, bitrateEstimate ->
            adaptAudioToBandwidth(trackSelector, bitrateEstimate)
        }
        // Daha uzun arabellek: kısa internet dalgalanmalarında görüntü/ses kesilmesin
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 2_500, 5_000)
            .build()
        // TV'nin bir görüntü çözücüsü yayını açamazsa diğer çözücüyü dene (eski/ucuz TV'ler için)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK) // Wi-Fi uykuya geçmesin
            // Sesi sisteme "film/medya" olarak bildir ve ses odağını düzgün yönet:
            // başka bir uygulama ses çalarsa üst üste binip cızırtı yapmasın
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val ch = channels.getOrNull(current)
                    // Radyo ve kendi yayınından vazgeçmeyen kanallar (ör. ATV):
                    // bağlantı koparsa yedeğe/YouTube'a geçmeden yeniden bağlan
                    if (ch != null && (ch.isRadio || ch.stayOriginal) && current >= 0) {
                        radioRetries++
                        if (radioRetries >= 3) showStatus("Bağlantı yenileniyor…")
                        val idx = current
                        main.postDelayed({ if (current == idx) play(idx, keepRetryFlag = true) },
                            (2_000L * radioRetries).coerceAtMost(15_000L))
                        return
                    }
                    // TV: bir kez otomatik tekrar dene, olmazsa yedeklere geç
                    if (!retried && current >= 0) {
                        retried = true
                        main.postDelayed({ play(current, keepRetryFlag = true) }, 1500)
                    } else if (ch != null && ch.fallbackUrl.isNotBlank() && !usedFallback) {
                        // Ana yayın açılmadı: yedek yayını dene
                        usedFallback = true
                        startStream(ch, ch.fallbackUrl,
                            ch.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: DEFAULT_UA)
                    } else if (ch != null && ch.youtube.isNotBlank() && !usedYouTube) {
                        // Son yedek: kanalın resmi YouTube canlı yayını
                        usedYouTube = true
                        // Sonraki açılışlarda 6 saat boyunca doğrudan YouTube'dan başla
                        prefs.edit().putLong("yt_${ch.id}", System.currentTimeMillis()).apply()
                        player?.stop()
                        watermark.visibility = View.GONE // YouTube oynatıcısının üstüne logo koymuyoruz
                        youTube.play(ch.youtube)
                    } else {
                        showStatus("${channels.getOrNull(current)?.name ?: "Kanal"} şu an açılamıyor\n(Kanal değiştirmek için ▲ ▼)")
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    // Canlı radyo "bitmez": sunucu bağlantıyı kapattıysa hemen yeniden bağlan
                    val cur = channels.getOrNull(current)
                    if (state == Player.STATE_ENDED && (cur?.isRadio == true || cur?.stayOriginal == true)) {
                        val idx = current
                        main.postDelayed({ if (current == idx) play(idx, keepRetryFlag = true) }, 500)
                    }
                    if (state == Player.STATE_READY) {
                        retried = false
                        radioRetries = 0
                        hideStatus()
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    applyQuality(tracks)
                }
            })
            playerView.player = p
            player = p
        }
    }

    private fun play(index: Int, keepRetryFlag: Boolean = false) {
        val ch = channels.getOrNull(index) ?: return
        if (!keepRetryFlag) { retried = false; usedFallback = false; usedYouTube = false; radioRetries = 0 }
        youTube.stop()
        watermark.visibility = View.VISIBLE
        if (!ch.isRadio) {
            // Radyodan TV'ye geçerken radyo kartı/şömine YouTube kanallarının üstünde kalmasın
            radioCard.visibility = View.GONE
            updateRadioBackground(false)
        }
        current = index
        prefs.edit().putString("last", ch.id).apply()
        channelAdapter.selected = shownChannels.indexOf(index)
        hideStatus()

        showInfo(index)
        val token = ++playToken

        // Bu kanal yakın zamanda yalnızca YouTube'dan açılabildiyse beklemeden oradan aç
        val ytSince = prefs.getLong("yt_${ch.id}", 0L)
        // Yalnızca resmi YouTube canlı yayını olan kanallar (ör. National Geographic) doğrudan oradan açılır
        val youtubeOnly = ch.url.isBlank() && ch.page.isBlank() && ch.youtube.isNotBlank()
        if (youtubeOnly || (!ch.stayOriginal && ch.youtube.isNotBlank() && System.currentTimeMillis() - ytSince < 6 * 3600_000L)) {
            usedYouTube = true
            player?.stop()
            watermark.visibility = View.GONE
            youTube.play(ch.youtube)
            return
        }
        // Radyolar kendini medya oynatıcısı olarak tanıtır: bazı radyo sunucuları
        // (StreamTheWorld) tarayıcı kimliğiyle gelen bağlantıyı 3 sn sonra kesiyor.
        val userAgent = ch.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: if (ch.isRadio) RADIO_UA else DEFAULT_UA

        if (ch.page.isBlank()) {
            startStream(ch, ch.url, userAgent)
        } else {
            // Adres süreli: kanalın sayfasından güncelini oku
            player?.stop()
            io.execute {
                val url = StreamResolver.resolve(ch, DESKTOP_UA)
                main.post {
                    // Bu arada kanal değiştiyse eski sonucu yok say
                    if (token == playToken && !isFinishing) startStream(ch, url, userAgent)
                }
            }
        }
    }

    private var playToken = 0
    private var usedFallback = false
    private var usedYouTube = false
    private var radioRetries = 0
    private lateinit var youTube: YouTubeLive
    private lateinit var watermark: View

    private fun startStream(ch: Channel, url: String, userAgent: String) {
        if (url.isBlank()) {
            showStatus("${ch.name} şu an açılamıyor\n(Kanal değiştirmek için ▲ ▼)")
            return
        }
        val headers = ch.headers.filterKeys { !it.equals("User-Agent", true) }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)

        val p = ensurePlayer()
        // Önceki kanalın kalite seçimi yeni kanala taşınmasın
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            // Radyoda görüntü yok: internet ne olursa olsun hep en yüksek ses kalitesini çal
            .setForceHighestSupportedBitrate(ch.isRadio)
            .build()
        radioCard.visibility = if (ch.isRadio) View.VISIBLE else View.GONE
        // Radyoda TV görüntü yüzeyini kaldır: eski Android'lerde iki video yüzeyi üst üste
        // binince şöminenin yerine önceki kanalın donmuş görüntüsü kalabiliyor
        playerView.visibility = if (ch.isRadio) View.GONE else View.VISIBLE
        updateRadioBackground(ch.isRadio)
        radioName.text = ch.name
        val source = DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
        private const val RADIO_UA = "AlinsTV/${BuildConfig.VERSION_NAME} (Linux; Android TV) ExoPlayerLib/1.3.1"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"
        private const val AUDIO_TIER_LOW = 0
        private const val AUDIO_TIER_MID = 1
        private const val AUDIO_TIER_HIGH = 2
    }

    // ---- Radyo arka planı: şömine videosu ya da doğa manzarası ----

    private var bgPlayer: ExoPlayer? = null
    private var fireUnsupported = false

    private var radioBg: String
        get() = prefs.getString("radioBg", "fire") ?: "fire"
        set(v) = prefs.edit().putString("radioBg", v).apply()

    private fun updateRadioBackground(isRadio: Boolean) {
        val fire = isRadio && radioBg == "fire" && !fireUnsupported
        natureView.visibility = if (isRadio && !fire) View.VISIBLE else View.GONE
        bgVideo.visibility = if (fire) View.VISIBLE else View.GONE
        fireCredit.visibility = bgVideo.visibility
        findViewById<View>(R.id.fireScrim).visibility = bgVideo.visibility
        if (fire) {
            val bp = bgPlayer ?: ExoPlayer.Builder(this).build().also { bp ->
                bp.setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.fireplace)))
                bp.repeatMode = Player.REPEAT_MODE_ONE
                bp.volume = 0f
                // Şömine videosunun ses kanalını hiç açma: radyo sesiyle çakışmasın
                bp.trackSelectionParameters = bp.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                // Eski TV bu videoyu çözemezse şömine yerine doğa manzarasını göster
                bp.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        fireUnsupported = true
                        bgPlayer?.release()
                        bgPlayer = null
                        updateRadioBackground(channels.getOrNull(current)?.isRadio == true)
                    }
                })
                bp.prepare()
                bgVideo.player = bp
                bgPlayer = bp
            }
            bp.playWhenReady = true
        } else {
            bgPlayer?.pause()
        }
    }

    // ---- Görüntü kalitesi (elle seçim) ----
    //  0: otomatik, internet hızına göre değişir ama 480p'nin altına inmez (varsayılan)
    // -1: ekranın kaldırdığı en iyi kalite, sabit
    // >0: seçilen yükseklik (ör. 720), sabit

    private var quality: Int
        get() = 0 // menü kapalı: her zaman otomatik, en az 480p
        set(v) = prefs.edit().putInt("quality2", v).apply()

    private var availableHeights: List<Int> = emptyList()

    // ---- Ses kalitesi: internet hızına göre ----
    // Yavaş internette yüksek bit hızlı / 5.1 ses izi görüntüden pay yer ve takılmaya yol açar.
    // Hız düşünce sesi stereo ve daha düşük bit hızına indir, hız düzelince en iyi kaliteye geri çık.

    private var audioTier = AUDIO_TIER_HIGH
    private var lastAudioTierChange = 0L

    private fun adaptAudioToBandwidth(selector: DefaultTrackSelector, bitrateEstimate: Long) {
        if (bitrateEstimate <= 0) return
        // Eşikler arasında gidip gelmesin: yukarı çıkmak için hızın eşiği %30 aşması gerekir
        // < 1,5 Mbps: düşük · 1,5–4 Mbps: orta · > 4 Mbps: en iyi
        val target = when {
            bitrateEstimate < 1_500_000L -> AUDIO_TIER_LOW
            audioTier == AUDIO_TIER_LOW && bitrateEstimate < 1_950_000L -> AUDIO_TIER_LOW
            bitrateEstimate < 4_000_000L -> AUDIO_TIER_MID
            audioTier != AUDIO_TIER_HIGH && bitrateEstimate < 5_200_000L -> AUDIO_TIER_MID
            else -> AUDIO_TIER_HIGH
        }
        if (target == audioTier) return
        val now = System.currentTimeMillis()
        // Düşürme hemen olur; yükseltme en fazla 20 saniyede bir (ses izi sık sık değişmesin)
        if (target > audioTier && now - lastAudioTierChange < 20_000L) return
        audioTier = target
        lastAudioTierChange = now
        val (maxBitrate, maxChannels) = when (target) {
            AUDIO_TIER_LOW -> 64_000 to 2
            AUDIO_TIER_MID -> 128_000 to 2
            else -> Int.MAX_VALUE to Int.MAX_VALUE
        }
        selector.setParameters(
            selector.buildUponParameters()
                .setMaxAudioBitrate(maxBitrate)
                .setMaxAudioChannelCount(maxChannels)
        )
    }

    private fun screenHeight(): Int {
        val d = if (Build.VERSION.SDK_INT >= 30) display
        else @Suppress("DEPRECATION") windowManager.defaultDisplay
        if (d == null) return 1080
        if (Build.VERSION.SDK_INT < 23) {
            val pt = android.graphics.Point()
            @Suppress("DEPRECATION") d.getRealSize(pt)
            return minOf(pt.x, pt.y).coerceAtLeast(720)
        }
        val m = d.mode
        return minOf(m.physicalWidth, m.physicalHeight).coerceAtLeast(720)
    }

    private fun applyQuality(tracks: Tracks) {
        val p = player ?: return
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: run {
            availableHeights = emptyList(); return
        }
        val tg = group.mediaTrackGroup
        val heights = (0 until tg.length).map { tg.getFormat(it).height }
        availableHeights = heights.filter { it > 0 }.distinct().sortedDescending()

        val q = quality
        if (q == 0) {
            if (p.trackSelectionParameters.overrides.isNotEmpty()) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
            }
            return
        }
        val limit = if (q == -1) screenHeight() else q
        val idx = heights.indices.filter { heights[it] in 1..limit }
            .maxByOrNull { heights[it] * 100_000_000L + tg.getFormat(it).bitrate }
            ?: heights.indices.minByOrNull { heights[it] } ?: return
        val current = p.trackSelectionParameters.overrides[tg]
        if (current != null && current.trackIndices == listOf(idx)) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(tg, idx)).build()
    }

    private var qualityOptions: List<Pair<String, Int>> = emptyList()

    private val qualityAdapter = RowAdapter(onClick = { pos ->
        qualityOptions.getOrNull(pos)?.let { (label, value) ->
            quality = value
            player?.let { applyQuality(it.currentTracks) }
            hideQuality()
            Toast.makeText(this, "Görüntü kalitesi: $label", Toast.LENGTH_SHORT).show()
        }
    })

    private fun showQuality() {
        if (channels.getOrNull(current)?.isRadio == true) return
        qualityOptions = buildList {
            add("Otomatik (en az 480p)" to 0)
            add("En iyi (ekrana göre)" to -1)
            availableHeights.forEach { add("${it}p" to it) }
        }
        qualityAdapter.submit(qualityOptions.map { RowAdapter.Row("", it.first) })
        val sel = qualityOptions.indexOfFirst { it.second == quality }.coerceAtLeast(0)
        qualityAdapter.selected = sel
        qualityPanel.visibility = View.VISIBLE
        qualityList.post {
            qualityList.findViewHolderForAdapterPosition(sel)?.itemView?.requestFocus()
                ?: qualityList.getChildAt(0)?.requestFocus()
        }
    }

    private fun hideQuality() {
        qualityPanel.visibility = View.GONE
    }

    private val qualityVisible get() = qualityPanel.visibility == View.VISIBLE

    private fun step(delta: Int) {
        if (channels.isEmpty()) return
        val next = ((if (current < 0) 0 else current) + delta + channels.size) % channels.size
        play(next)
    }

    // ---- Arayüz yardımcıları ----

    private val hideInfo = Runnable { infoBar.visibility = View.GONE }

    private fun showInfo(index: Int) {
        infoNumber.text = "${index + 1}"
        infoName.text = channels[index].name
        infoBar.visibility = View.VISIBLE
        main.removeCallbacks(hideInfo)
        main.postDelayed(hideInfo, 3500)
    }

    private fun showStatus(msg: String) {
        statusText.text = msg
        statusText.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusText.visibility = View.GONE
    }

    private fun showList() {
        if (channels.isEmpty()) return
        listPanel.visibility = View.VISIBLE
        // Açılışta o anki kanalın kategorisi yerine "Tümü" seçili gelsin, odak oynayan kanalda olsun
        showCategory(0)
        focusChannels()
    }

    private fun focusChannels() {
        val pos = channelAdapter.selected.coerceAtLeast(0)
        channelList.scrollToPosition(pos)
        channelList.post {
            channelList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                ?: channelList.getChildAt(0)?.requestFocus()
        }
    }

    private fun hideList() {
        listPanel.visibility = View.GONE
    }

    private val listVisible get() = listPanel.visibility == View.VISIBLE

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (listVisible || qualityVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hideList(); hideQuality(); return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { step(+1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(-1); return true }
            // OK tuşu: liste tuş bırakılınca açılır. Basılırken açılırsa, bırakma anı
            // listedeki kanala "tıklama" sayılıp listeyi hemen kapatıyordu.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { openOnKeyUp = true; return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE -> { showList(); return true }
            // Radyodayken sağ tuş: şömine ↔ doğa manzarası
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (channels.getOrNull(current)?.isRadio == true) {
                    radioBg = if (radioBg == "fire") "nature" else "fire"
                    updateRadioBackground(true)
                } else if (current >= 0) showInfo(current)
                return true
            }
            // Kalite izleyiciye gösterilmez, arka planda otomatik ayarlanır (en az 480p)
            KeyEvent.KEYCODE_INFO -> {
                if (current >= 0) showInfo(current); return true
            }
            KeyEvent.KEYCODE_BACK -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPress < 2000) finish()
                else {
                    lastBackPress = now
                    Toast.makeText(this, "Çıkmak için tekrar Geri'ye basın", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            numberInput(keyCode - KeyEvent.KEYCODE_0); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private var openOnKeyUp = false

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (openOnKeyUp && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            openOnKeyUp = false
            showList()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // Kumandada rakam tuşu varsa kanal numarasıyla geçiş
    private var typed = ""
    private val commitNumber = Runnable {
        val n = typed.toIntOrNull()
        typed = ""
        if (n != null && n in 1..channels.size) play(n - 1) else if (current >= 0) showInfo(current)
    }

    private fun numberInput(d: Int) {
        typed += d
        infoNumber.text = typed
        infoName.text = ""
        infoBar.visibility = View.VISIBLE
        main.removeCallbacks(commitNumber)
        main.postDelayed(commitNumber, 1500)
    }

    // ---- Yaşam döngüsü ----

    override fun onStart() {
        super.onStart()
        if (player == null && current >= 0) play(current)
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
        playerView.player = null
        bgPlayer?.release()
        bgPlayer = null
        bgVideo.player = null
        youTube.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }
}
