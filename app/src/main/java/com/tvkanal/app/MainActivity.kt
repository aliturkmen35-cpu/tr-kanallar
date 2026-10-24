package com.tvkanal.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

        categoryList.layoutManager = LinearLayoutManager(this)
        categoryList.adapter = categoryAdapter
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = channelAdapter

        loadChannels()
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
        return ExoPlayer.Builder(this).build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Bir kez otomatik tekrar dene, olmazsa mesaj göster
                    if (!retried && current >= 0) {
                        retried = true
                        main.postDelayed({ play(current, keepRetryFlag = true) }, 1500)
                    } else {
                        showStatus("${channels.getOrNull(current)?.name ?: "Kanal"} şu an açılamıyor\n(Kanal değiştirmek için ▲ ▼)")
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        retried = false
                        hideStatus()
                    }
                }
            })
            playerView.player = p
            player = p
        }
    }

    private fun play(index: Int, keepRetryFlag: Boolean = false) {
        val ch = channels.getOrNull(index) ?: return
        if (!keepRetryFlag) retried = false
        current = index
        prefs.edit().putString("last", ch.id).apply()
        channelAdapter.selected = shownChannels.indexOf(index)
        hideStatus()

        showInfo(index)
        val token = ++playToken
        val userAgent = ch.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: DEFAULT_UA

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
        val source = DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"
    }

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
        if (listVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hideList(); return true
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
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
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
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }
}
