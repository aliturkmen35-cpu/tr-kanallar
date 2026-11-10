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
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
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
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
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
    private lateinit var infoMeta: TextView
    private lateinit var statusText: TextView

    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("tv", Context.MODE_PRIVATE) }

    private var channels: List<Channel> = emptyList()
    private var baseCurated: List<Channel> = emptyList() // Alin's listesi (sunucudan)
    private var baseWorld: List<Channel> = emptyList()   // dünya kanalları (world.json)
    private val edits by lazy { UserEdits(prefs) }

    /** Soldaki sütunun bir satırı. key: "search", "fav", "add", "hidden" ya da "g:<grup>". */
    private data class Cat(val key: String, val title: String)
    private var categories: List<Cat> = emptyList()
    private var shownChannels: List<Int> = emptyList() // seçili kategorideki kanalların indeksleri
    private var shownKey = ""                          // ekrandaki kategorinin anahtarı
    private var searchQuery = ""
    private var playContext: List<Int> = emptyList()   // ▲ ▼ ile gezilen liste (kanalın açıldığı kategori)
    private var previous = -1                          // önceki kanal ("0" ya da LAST tuşu)
    private var pendingId: String? = null              // ana ekran / Canlı TV'den istenen kanal
    private var current = -1
    private var retried = false
    private var lastBackPress = 0L

    // Odak değişimi liste kayarken gelebilir; o sırada listeyi güncellemek çöker, bir sonraki ana ertele
    private val categoryAdapter = RowAdapter(onClick = { onCategoryClick(it) }, onFocus = { onCategoryFocus(it) })

    private fun onCategoryFocus(pos: Int) {
        main.post { if (categoryAdapter.selected != pos) showCategory(pos) }
    }
    private val channelAdapter = RowAdapter(
        onClick = { pos ->
            shownChannels.getOrNull(pos)?.let { playContext = shownChannels; play(it); hideList() }
        },
        onLongClick = { pos -> shownChannels.getOrNull(pos)?.let { showChannelOptions(it) } },
    )

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
        infoMeta = findViewById(R.id.infoMeta)
        statusText = findViewById(R.id.statusText)
        qualityPanel = findViewById(R.id.qualityPanel)
        qualityList = findViewById(R.id.qualityList)
        radioCard = findViewById(R.id.radioCard)
        radioName = findViewById(R.id.radioName)
        natureView = findViewById(R.id.natureView)
        watermark = findViewById(R.id.watermark)
        youTube = YouTubeLive(findViewById(R.id.ytView)) {
            watermark.visibility = View.VISIBLE
            // YouTube yedeği de gelmedi: 6 saatlik YouTube kısayolunu kaldır ki
            // yeniden denemede önce kanalın kendi yayını denensin, sonra pes etmeden sürdür.
            channels.getOrNull(current)?.let { prefs.edit().remove("yt_${it.id}").apply() }
            scheduleReconnect()
        }
        bgVideo = findViewById(R.id.bgVideo)
        fireCredit = findViewById(R.id.fireCredit)
        qualityList.layoutManager = LinearLayoutManager(this)
        qualityList.adapter = qualityAdapter

        categoryList.layoutManager = LinearLayoutManager(this)
        categoryList.adapter = categoryAdapter
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = channelAdapter

        pendingId = SystemChannels.idFromIntent(intent)
        autoHideLauncherIfReady()
        loadChannels()
        if (prefs.getBoolean("autoStart", true)) AutoStart.startWakeService(this)
        askOverlayPermissionOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = SystemChannels.idFromIntent(intent) ?: return
        val idx = channels.indexOfFirst { it.id == id }
        if (idx >= 0) { hideList(); play(idx) } else pendingId = id
    }

    /** Otomatik açılış için "diğer uygulamaların üzerinde göster" izni ilk açılışta bir kez istenir. */
    private fun askOverlayPermissionOnce() {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) return
        if (prefs.getBoolean("askedOverlay", false) || !prefs.getBoolean("autoStart", true)) return
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
        baseCurated = list
        rebuild()
        if (channels.isEmpty()) {
            showStatus("Listede kanal yok")
            return
        }
        val lastId = pendingId ?: prefs.getString("last", null)
        val start = channels.indexOfFirst { it.id == lastId }.takeIf { it >= 0 } ?: 0
        if (channels.getOrNull(start)?.id == pendingId) pendingId = null
        play(start)
        loadWorld(lastId)
        loadEpg()
    }

    /** Dünya kanallarını (iptv-org) arka planda yükleyip Alin's listesinin arkasına ekler. */
    private fun loadWorld(lastId: String?) {
        io.execute {
            val world = runCatching { ChannelRepository(this).loadWorld() }.getOrDefault(emptyList())
            if (world.isEmpty()) return@execute
            main.post {
                if (isFinishing || isDestroyed) return@post
                baseWorld = world
                rebuild()
                // Son izlenen bir dünya kanalıysa ona geç
                if (lastId != null && channels.getOrNull(current)?.id != lastId) {
                    val idx = channels.indexOfFirst { it.id == lastId }
                    if (idx >= 0) play(idx)
                }
            }
        }
    }

    /**
     * Kanal listesini yeniden kurar: Alin's + dünya + izleyicinin eklediği kanallar,
     * izleyicinin verdiği adlarla. Oynayan kanal kimliğiyle korunur.
     */
    private fun rebuild() {
        val curId = channels.getOrNull(current)?.id
        val ctxIds = playContext.mapNotNull { channels.getOrNull(it)?.id }
        val world = if (prefs.getBoolean("showWorld", true)) baseWorld else emptyList()
        channels = (baseCurated + world + edits.custom).map { c ->
            edits.names[c.id]?.let { c.copy(name = it) } ?: c
        }
        val index = HashMap<String, Int>(channels.size * 2)
        channels.forEachIndexed { i, c -> index.putIfAbsent(c.id, i) }
        if (curId != null) current = index[curId] ?: -1
        playContext = ctxIds.mapNotNull { index[it] }
        rebuildCategories()
        val keep = categories.indexOfFirst { it.key == shownKey }
        showCategory(if (keep >= 0) keep else categoryOf(current))
        scheduleSystemSync()
    }

    /** Yayın akışını indir; sonra bandı ve TV rehberini güncelle. 3 saatte bir yenilenir. */
    private val epgRefresh = Runnable { loadEpg() }
    private fun loadEpg() {
        main.removeCallbacks(epgRefresh)
        io.execute {
            Epg.load(this)
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (infoBar.visibility == View.VISIBLE && current >= 0 && typed.isEmpty()) showInfo(current)
                scheduleSystemSync()
                main.postDelayed(epgRefresh, 3 * 3600_000L)
            }
        }
    }

    /** TV'nin kanal sistemine ve ana ekran satırına yaz (birkaç saniye bekleyip tek seferde). */
    private val systemSync = Runnable { io.execute { SystemChannels.syncAll(this) } }
    private fun scheduleSystemSync() {
        main.removeCallbacks(systemSync)
        main.postDelayed(systemSync, 4000)
    }

    private fun idIndex(): Map<String, Int> {
        val m = HashMap<String, Int>(channels.size * 2)
        channels.forEachIndexed { i, c -> m.putIfAbsent(c.id, i) }
        return m
    }

    private fun rebuildCategories() {
        val visible = channels.filter { it.id !in edits.hidden }
        val counts = visible.groupingBy { it.group }.eachCount()
        val groups = visible.map { it.group }.distinct()
        categories = buildList {
            add(Cat("search", if (searchQuery.isBlank()) "🔍 Kanal ara" else "🔍 Ara: $searchQuery"))
            add(Cat("fav", "♥ Favoriler (${edits.favorites.size})"))
            add(Cat("settings", "⚙ Ayarlar"))
            add(Cat("add", "✚ Kanal ekle"))
            groups.forEach { add(Cat("g:$it", "$it (${counts[it]})")) }
            if (edits.hidden.isNotEmpty()) add(Cat("hidden", "Gizlenenler (${edits.hidden.size})"))
        }
        categoryAdapter.submit(categories.map { RowAdapter.Row("", it.title) })
    }

    /** Kanalın kendi grubunun sütundaki sırası. */
    private fun categoryOf(index: Int): Int {
        val g = channels.getOrNull(index)?.group ?: return 0
        return categories.indexOfFirst { it.key == "g:$g" }.coerceAtLeast(0)
    }

    private fun channelsFor(key: String): List<Int> = when {
        key == "fav" -> idIndex().let { m -> edits.favorites.mapNotNull { m[it] } }
        key == "hidden" -> channels.indices.filter { channels[it].id in edits.hidden }
        key == "search" -> if (searchQuery.isBlank()) emptyList() else {
            val q = UserEdits.fold(searchQuery)
            channels.indices.filter { channels[it].id !in edits.hidden && q in UserEdits.fold(channels[it].name) }
        }
        key.startsWith("g:") -> key.removePrefix("g:").let { g ->
            channels.indices.filter { channels[it].group == g && channels[it].id !in edits.hidden }
        }
        else -> emptyList()
    }

    private fun showCategory(pos: Int) {
        val cat = categories.getOrNull(pos) ?: return
        categoryAdapter.selected = pos
        shownKey = cat.key
        shownChannels = channelsFor(cat.key)
        val favs = edits.favorites.toHashSet()
        channelAdapter.submit(shownChannels.map {
            val c = channels[it]
            RowAdapter.Row("${it + 1}", if (cat.key != "fav" && c.id in favs) "♥ ${c.name}" else c.name)
        })
        channelAdapter.selected = shownChannels.indexOf(current)
    }

    private fun onCategoryClick(pos: Int) {
        when (categories.getOrNull(pos)?.key) {
            "search" -> askSearch()
            "add" -> askAddChannel()
            "settings" -> showSettings()
            else -> focusChannels()
        }
    }

    // ---- Arama, favoriler, kanal düzenleme ----

    private fun askSearch() {
        val input = EditText(this).apply {
            setText(searchQuery); setSelection(text.length)
            hint = "Kanal adı (ör. trt, haber, kanal 7)"
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Kanal ara")
            .setView(padded(input))
            .setPositiveButton("Ara") { _, _ ->
                searchQuery = input.text.toString().trim()
                rebuildCategories()
                showCategory(0)
                if (shownChannels.isEmpty()) Toast.makeText(this, "Sonuç yok", Toast.LENGTH_SHORT).show()
                else focusChannels()
            }
            .setNeutralButton("Temizle") { _, _ -> searchQuery = ""; rebuildCategories(); showCategory(0) }
            .setNegativeButton("Vazgeç", null)
            .show()
        // Ekran klavyesindeki ✓ / Ara tuşu doğrudan arar
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        input.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); true
        }
        input.requestFocus()
    }

    private fun askAddChannel() {
        val name = EditText(this).apply { hint = "Kanal adı"; setSingleLine() }
        val url = EditText(this).apply { hint = "Yayın adresi (http://… .m3u8)"; setSingleLine() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name); addView(url)
        }
        AlertDialog.Builder(this)
            .setTitle("Kanal ekle")
            .setView(padded(box))
            .setPositiveButton("Ekle") { _, _ ->
                val u = url.text.toString().trim()
                if (!u.startsWith("http")) {
                    Toast.makeText(this, "Adres http ile başlamalı", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                edits.addCustom(name.text.toString().ifBlank { "Kanal" }, u)
                shownKey = "g:${UserEdits.GROUP_CUSTOM}"
                rebuild()
                focusChannels()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun showSettings() {
        val auto = prefs.getBoolean("autoStart", true)
        val world = prefs.getBoolean("showWorld", true)
        val items = arrayOf(
            "TV açılınca Alin's açılsın: " + if (auto) "AÇIK" else "KAPALI",
            "Türkiye ve bölgesel kanallar: " + if (world) "GÖSTER" else "GİZLE",
            "Kanalları TV'nin kanal listesine ve ana ekrana ekle",
            "Uygulamalar listesinde simge: " + if (launcherVisible()) "GÖRÜNÜR" else "GİZLİ (yalnızca TV kanalı)",
            "Alin's sürüm ${BuildConfig.VERSION_NAME}",
        )
        AlertDialog.Builder(this)
            .setTitle("Ayarlar")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        prefs.edit().putBoolean("autoStart", !auto).apply()
                        if (!auto) { AutoStart.startWakeService(this); askOverlayPermissionOnce() }
                        else stopService(Intent(this, WakeService::class.java))
                        Toast.makeText(this, if (!auto) "TV açılınca Alin's açılacak" else "Otomatik açılış kapatıldı", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        prefs.edit().putBoolean("showWorld", !world).apply()
                        shownKey = ""
                        rebuild()
                    }
                    2 -> io.execute {
                        val n = SystemChannels.syncAll(this, force = true)
                        main.post {
                            Toast.makeText(this,
                                if (n > 0) "$n kanal TV'nin kanal listesine eklendi\nAna ekranda \"Alin's TV\" satırı" else "TV bu özelliği desteklemiyor",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                    3 -> if (launcherVisible()) confirmHideLauncher() else {
                        setLauncherVisible(true)
                        prefs.edit().putBoolean("iconManual", true).apply() // bir daha kendiliğinden gizleme
                        Toast.makeText(this, "Alin's simgesi uygulamalar listesine geri geldi", Toast.LENGTH_LONG).show()
                        main.post { showSettings() }
                    }
                }
                if (which <= 1) main.post { showSettings() }
            }
            .show()
    }

    // ---- Uygulamalar listesindeki simge (gizlenince Alin's yalnızca TV kanal sistemi gibi durur) ----

    private val launcherAlias get() = android.content.ComponentName(this, "$packageName.Launcher")

    private fun launcherVisible(): Boolean =
        packageManager.getComponentEnabledSetting(launcherAlias) !=
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    /**
     * Simgeyi kendiliğinden gizle: TV'de Canlı TV uygulaması varsa ve izleyici orada Alin's
     * kanallarını açtıysa (kanallar TV listesinde görünür), Alin's artık TV'nin kendi kanalı
     * gibi oradan açılır; uygulamalar listesinde ayrıca durmasına gerek yok.
     * İzleyici simgeyi Ayarlar'dan geri getirdiyse bir daha dokunulmaz.
     */
    private fun autoHideLauncherIfReady() {
        if (!launcherVisible() || prefs.getBoolean("iconManual", false)) return
        io.execute {
            // Kanallar bir Canlı TV uygulamasında (TV markası fark etmez) açıldıysa Alin's oradan girilebilir
            val ready = SystemChannels.userEnabledInLiveTv(this)
            if (!ready) return@execute
            main.post {
                if (isFinishing || isDestroyed || !launcherVisible()) return@post
                setLauncherVisible(false)
                Toast.makeText(this,
                    "Alin's artık TV'nin kendi kanalları arasında: Canlı TV'den ve ana ekrandan açılır\n(Simge için: MENU > Ayarlar)",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setLauncherVisible(on: Boolean) {
        packageManager.setComponentEnabledSetting(launcherAlias,
            if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP)
    }

    private fun confirmHideLauncher() {
        AlertDialog.Builder(this)
            .setTitle("Simge gizlensin mi?")
            .setMessage("Alin's uygulamalar listesinde görünmez; TV'nin kendi kanalları gibi kullanılır:\n\n" +
                "• TV'nin Canlı TV / Kanallar uygulaması (kaynak: Alin's)\n" +
                "• Ana ekrandaki \"Alin's TV\" satırı\n" +
                "• TV açılınca kendiliğinden açılış\n\n" +
                "Simgeyi geri getirmek için: Alin's açıkken Ayarlar > Uygulamalar listesinde simge.")
            .setPositiveButton("Gizle") { _, _ ->
                setLauncherVisible(false)
                prefs.edit().putBoolean("autoStart", true).apply()
                AutoStart.startWakeService(this)
                Toast.makeText(this, "Alin's artık TV'nin kanal sisteminden açılır", Toast.LENGTH_LONG).show()
                // TV kanal sistemi ve ana ekran satırı hazır olsun
                io.execute { SystemChannels.syncAll(this, force = true) }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    /** Kanal satırında OK'a uzun basınca açılan seçenekler. */
    private fun showChannelOptions(index: Int) {
        val ch = channels.getOrNull(index) ?: return
        val inFav = shownKey == "fav"
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        opts += (if (edits.isFavorite(ch.id)) "♥ Favorilerden çıkar" else "♥ Favorilere ekle") to {
            edits.toggleFavorite(ch.id); refreshList()
        }
        if (inFav) {
            opts += "▲ Yukarı taşı" to { edits.moveFavorite(ch.id, -1); refreshList(ch.id) }
            opts += "▼ Aşağı taşı" to { edits.moveFavorite(ch.id, +1); refreshList(ch.id) }
        }
        opts += "✎ Adını değiştir" to { askRename(ch) }
        opts += (if (ch.id in edits.hidden) "Göster" else "Gizle") to { edits.toggleHidden(ch.id); refreshList() }
        if (ch.group == UserEdits.GROUP_CUSTOM) opts += "✖ Kanalı sil" to { edits.removeCustom(ch.id); rebuild() }
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .show()
    }

    private fun askRename(ch: Channel) {
        val input = EditText(this).apply { setText(ch.name); setSelection(text.length); setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Kanalın adı")
            .setView(padded(input))
            .setPositiveButton("Kaydet") { _, _ -> edits.rename(ch.id, input.text.toString()); rebuild() }
            .setNeutralButton("Eski adı") { _, _ -> edits.rename(ch.id, null); rebuild() }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    /** Listeyi yeniler; odak verilen kanalda (yoksa aynı satırda) kalır. */
    private fun refreshList(focusId: String? = null) {
        scheduleSystemSync()
        val row = channelAdapter.selected
        rebuildCategories()
        val keep = categories.indexOfFirst { it.key == shownKey }
        showCategory(if (keep >= 0) keep else 0)
        val pos = focusId?.let { id -> shownChannels.indexOfFirst { channels[it].id == id } } ?: -1
        focusRow(if (pos >= 0) pos else focusedChannelRow().coerceAtMost(shownChannels.size - 1).takeIf { it >= 0 } ?: row)
    }

    private fun focusedChannelRow(): Int =
        channelList.focusedChild?.let { channelList.getChildAdapterPosition(it) } ?: channelAdapter.selected

    private fun focusRow(pos: Int) {
        if (pos < 0) return
        channelList.scrollToPosition(pos)
        channelList.post { channelList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    private fun padded(v: View) = android.widget.FrameLayout(this).apply {
        val p = (20 * resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, 0)
        addView(v)
    }

    // ---- Oynatma ----

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        // TV'nin ekran çözünürlüğünü algıla: 720p ekrana 1080p, 1080p ekrana 4K yayın
        // gönderip boşuna internet harcama; ekranın kaldırabildiği en iyi kaliteyi seç.
        // Kaliteyi yükseltmeden önce 15 sn iyi hız görsün ve ölçülen hızın %60'ını kullansın:
        // uzak sunucularda (Bali → Türkiye) hız çok dalgalanıyor, bir iyi parça yüzünden
        // 1080p'ye zıplayıp takılmasın.
        val abr = AdaptiveTrackSelection.Factory(15_000, 25_000, 25_000, 0.6f)
        val trackSelector = DefaultTrackSelector(this, abr).apply {
            setParameters(
                buildUponParameters()
                    .setViewportSizeToPhysicalDisplaySize(this@MainActivity, true)
                    .setForceHighestSupportedBitrate(false)
                    // İnternet zayıfsa kaliteyi düşürmesine izin ver: donup beklemektense
                    // 360p'ye (hatta daha aşağı) in, akıcı oynasın. İnternet açılınca kendi yükselir.
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
        // Açılışta interneti abartmayıp orta bir hızdan başla: zayıf Wi-Fi'de
        // ilk saniyelerde yüksek kaliteye zıplayıp donmasın.
        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(3_000_000L)
            .build()
        // İnternet hızı her ölçüldüğünde ses kalitesini hıza göre ayarla
        bandwidthMeter.addEventListener(main) { _, _, bitrateEstimate ->
            adaptAudioToBandwidth(trackSelector, bitrateEstimate)
        }
        // Daha uzun arabellek: kısa internet dalgalanmalarında görüntü/ses kesilmesin
        // Oynatmaya başlamadan önce ve donma sonrası biraz daha fazla görüntü biriktir:
        // kısa internet dalgalanmaları tamponu boşaltıp görüntüyü dondurmasın.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 60_000, 5_000, 15_000)
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
                        // Aynı kanalın ikinci resmi kaynağı varsa (ör. ATV'nin kendi sitesindeki yayın)
                        // kopan kaynaktan diğerine geç; ikisi arasında gidip gel
                        if (!ch.isRadio && ch.hasFallback) useAlt = !useAlt
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
                    } else if (ch != null && ch.hasFallback && !usedFallback) {
                        // Ana yayın açılmadı: yedek yayını dene
                        usedFallback = true
                        useAlt = true
                        play(current, keepRetryFlag = true)
                    } else if (ch != null && backupIdx + 1 < ch.backups.size) {
                        // Yedek de açılmadı: listedeki sıradaki adresi dene
                        backupIdx++
                        useAlt = false
                        play(current, keepRetryFlag = true)
                    } else if (ch != null && ch.youtube.isNotBlank() && !usedYouTube) {
                        // Son yedek: kanalın resmi YouTube canlı yayını
                        usedYouTube = true
                        // Sonraki açılışlarda 6 saat boyunca doğrudan YouTube'dan başla
                        prefs.edit().putLong("yt_${ch.id}", System.currentTimeMillis()).apply()
                        player?.stop()
                        watermark.visibility = View.GONE // YouTube oynatıcısının üstüne logo koymuyoruz
                        youTube.play(ch.youtube)
                    } else {
                        // Ana yayın, yedek ve YouTube de gelmedi: pes etme.
                        // Kısa aralıklarla en iyi kaynaktan yeniden bağlanmayı sürdür;
                        // internet düzelince kanal kendiliğinden geri gelir.
                        scheduleReconnect()
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    // Canlı yayın "bitmez": sunucu bağlantıyı kapattıysa yeniden bağlan
                    val cur = channels.getOrNull(current)
                    if (state == Player.STATE_ENDED && current >= 0) {
                        val idx = current
                        if (cur?.isRadio == true || cur?.stayOriginal == true) {
                            main.postDelayed({ if (current == idx) play(idx, keepRetryFlag = true) }, 500)
                        } else {
                            // Normal kanal: en iyi kaynaktan yeniden bağlanmayı sürdür
                            scheduleReconnect()
                        }
                    }
                    // Oynarken görüntü durup beklemeye geçtiyse: yayın internete yetişemiyor
                    if (state == Player.STATE_BUFFERING && streamWasReady && p.playWhenReady && cur != null) {
                        onStall(cur)
                    }
                    if (state == Player.STATE_READY) {
                        streamWasReady = true
                        scheduleQualityUp()
                        retried = false
                        radioRetries = 0
                        reconnectRetries = 0
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

    private fun play(index: Int, keepRetryFlag: Boolean = false, fromReconnect: Boolean = false) {
        val ch = channels.getOrNull(index) ?: return
        if (!keepRetryFlag) { retried = false; usedFallback = false; usedYouTube = false; radioRetries = 0; useAlt = false; backupIdx = -1 }
        // Kullanıcı kanalı kendi değiştirdiyse yeniden-bağlanma sayacını sıfırla
        // (otomatik yeniden bağlanmada sayaç korunur ki aralık giderek açılsın).
        if (!keepRetryFlag && !fromReconnect) reconnectRetries = 0
        youTube.stop()
        watermark.visibility = View.VISIBLE
        if (!ch.isRadio) {
            // Radyodan TV'ye geçerken radyo kartı/şömine YouTube kanallarının üstünde kalmasın
            radioCard.visibility = View.GONE
            updateRadioBackground(false)
        }
        if (index != current && current >= 0) previous = current
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

        if (backupIdx in ch.backups.indices) {
            // Sıradaki yedek adres (liste botu yaşayan adresleri sıralar)
            startStream(ch, ch.backups[backupIdx], userAgent)
        } else if (useAlt && ch.hasFallback) {
            // Yedek kaynak: adresi (gerekirse kanalın token servisinden) o an oku
            player?.stop()
            io.execute {
                val url = StreamResolver.resolveFallback(ch, DESKTOP_UA)
                main.post { if (token == playToken && !isFinishing) startStream(ch, url, userAgent) }
            }
        } else if (ch.page.isBlank()) {
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

    /**
     * Kanal kopup tüm kaynaklar (ana yayın, yedek, YouTube) gelmediğinde pes etmez;
     * giderek açılan aralıklarla (3sn, 6sn, … en çok 20sn) en iyi kaynaktan yeniden
     * bağlanmayı sürdürür. İnternet düzelince kanal kendiliğinden geri gelir.
     */
    private fun scheduleReconnect() {
        if (current < 0) return
        reconnectRetries++
        val name = channels.getOrNull(current)?.name ?: "Kanal"
        showStatus(
            if (reconnectRetries >= 4)
                "$name şu an açılamıyor, yeniden deneniyor…\n(Kanal değiştirmek için ▲ ▼)"
            else "Bağlantı yenileniyor…"
        )
        val idx = current
        main.postDelayed(
            { if (current == idx && !isFinishing) play(idx, fromReconnect = true) },
            (3_000L * reconnectRetries).coerceAtMost(20_000L)
        )
    }

    private var playToken = 0
    private var usedFallback = false
    private var useAlt = false
    /** Denenen yedek adresin sırası (Channel.backups); -1 = henüz yok. */
    private var backupIdx = -1
    private var reconnectRetries = 0
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
        streamWasReady = false
        main.removeCallbacks(qualityUp)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            // Bu kanal yakın zamanda takıldıysa düşürülen kalite sınırıyla başla
            .setMaxVideoSize(Int.MAX_VALUE, videoCap(ch))
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
        // ▲ ▼ kanalın açıldığı listede (favoriler, arama ya da ülke) gezinir
        val inGroup = playContext.takeIf { current in it }
            ?: channelsFor("g:" + (channels.getOrNull(current)?.group ?: channels[0].group)).ifEmpty { listOf(current.coerceAtLeast(0)) }
        val pos = inGroup.indexOf(current).coerceAtLeast(0)
        play(inGroup[(pos + delta + inGroup.size) % inGroup.size])
    }

    // ---- Arayüz yardımcıları ----

    private val hideInfo = Runnable { infoBar.visibility = View.GONE }

    private fun showInfo(index: Int) {
        val ch = channels[index]
        infoNumber.text = "${index + 1}"
        infoName.text = (if (edits.isFavorite(ch.id)) "♥ " else "") + ch.name
        val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr", "TR")).format(java.util.Date())
        val now = Epg.line(ch.id)
        infoMeta.text = "${if (ch.isRadio) "Radyo" else ch.group}  ·  $clock" + (now?.let { "\n$it" } ?: "")
        infoMeta.visibility = View.VISIBLE
        infoBar.visibility = View.VISIBLE
        main.removeCallbacks(hideInfo)
        main.postDelayed(hideInfo, if (Epg.programs(channels[index].id).isEmpty()) 3500 else 6000)
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
        // Kanalın açıldığı liste (favoriler/arama/ülke) seçili gelsin, odak oynayan kanalda olsun
        val ctx = categories.indexOfFirst { it.key == shownKey }
        val cat = if (ctx >= 0 && current in channelsFor(shownKey)) ctx else categoryOf(current)
        if (prefs.getInt("hintShown", 0) < 3) {
            prefs.edit().putInt("hintShown", prefs.getInt("hintShown", 0) + 1).apply()
            Toast.makeText(this, "Arama, favoriler: sola 2 kez bas\nFavori/ad/gizle: OK'a basılı tut", Toast.LENGTH_LONG).show()
        }
        showCategory(cat)
        categoryList.scrollToPosition(cat)
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
        if (keyCode == KeyEvent.KEYCODE_SEARCH && channels.isNotEmpty()) {
            if (!listVisible) showList()
            askSearch(); return true
        }
        if (listVisible || qualityVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hideList(); hideQuality(); return true
            }
            // Ülke sütunundayken sol tuş: en üste (Kanal ara / Favoriler) atla
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && listVisible && categoryList.hasFocus()) {
                categoryList.scrollToPosition(0)
                categoryList.post { categoryList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { step(+1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(-1); return true }
            // OK tuşu: liste tuş bırakılınca açılır. Basılırken açılırsa, bırakma anı
            // listedeki kanala "tıklama" sayılıp listeyi hemen kapatıyordu.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { openOnKeyUp = true; return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE -> { showList(); return true }
            // Kumandanın Menü / Ayarlar tuşu: Alin's ayarları
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { showSettings(); return true }
            // Radyodayken sağ tuş: şömine ↔ doğa manzarası
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (channels.getOrNull(current)?.isRadio == true) {
                    radioBg = if (radioBg == "fire") "nature" else "fire"
                    updateRadioBackground(true)
                } else if (current >= 0) showInfo(current)
                return true
            }
            // Kalite izleyiciye gösterilmez, arka planda otomatik ayarlanır (en az 480p)
            KeyEvent.KEYCODE_LAST_CHANNEL -> { if (previous >= 0) play(previous); return true }
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
        // Tek başına "0": önceki kanala dön (TV kumandalarındaki gibi)
        if (n == 0 && previous >= 0) play(previous)
        else if (n != null && n in 1..channels.size) play(n - 1) else if (current >= 0) showInfo(current)
    }

    private fun numberInput(d: Int) {
        typed += d
        infoNumber.text = typed
        infoName.text = ""
        infoMeta.visibility = View.GONE
        infoBar.visibility = View.VISIBLE
        main.removeCallbacks(commitNumber)
        main.postDelayed(commitNumber, 1500)
    }

    // ---- Yaşam döngüsü ----

    override fun onStart() {
        super.onStart()
        if (player == null && current >= 0) play(current)
    }

    // ---- Takılma olursa alternatif: kaliteyi bir basamak düşür ----
    // Yayın internete yetişemeyip takılınca (ör. Bali'den ATV) o kanalın en yüksek
    // kalitesini bir basamak indir (1080p → 720p → 480p → 360p). Sınır kanal başına
    // 6 saat hatırlanır; 20 dk takılmadan oynarsa bir basamak geri yükselir.

    private var streamWasReady = false
    private var lastStall = 0L

    private fun videoCap(ch: Channel): Int {
        if (ch.isRadio) return Int.MAX_VALUE
        val since = prefs.getLong("capT_${ch.id}", 0L)
        if (System.currentTimeMillis() - since > 6 * 3600_000L) return Int.MAX_VALUE
        return prefs.getInt("cap_${ch.id}", Int.MAX_VALUE)
    }

    private fun setVideoCap(ch: Channel, cap: Int) {
        prefs.edit().putInt("cap_${ch.id}", cap).putLong("capT_${ch.id}", System.currentTimeMillis()).apply()
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, cap).build()
    }

    private fun onStall(ch: Channel) {
        if (ch.isRadio) return
        main.removeCallbacks(qualityUp)
        val now = System.currentTimeMillis()
        // Aynı takılmayı iki kez sayma: düşürmeden sonra yeni kalitenin oturmasını bekle
        if (now - lastStall < 20_000L) return
        lastStall = now
        val playing = player?.videoFormat?.height ?: return
        val lower = availableHeights.firstOrNull { it < playing } ?: return // zaten en düşükte
        setVideoCap(ch, lower)
    }

    private val qualityUp = Runnable {
        val ch = channels.getOrNull(current) ?: return@Runnable
        val cap = videoCap(ch)
        if (cap == Int.MAX_VALUE) return@Runnable
        val higher = availableHeights.lastOrNull { it > cap }
        setVideoCap(ch, higher ?: Int.MAX_VALUE)
        if (higher != null) scheduleQualityUp()
    }

    private fun scheduleQualityUp() {
        main.removeCallbacks(qualityUp)
        val ch = channels.getOrNull(current) ?: return
        if (videoCap(ch) != Int.MAX_VALUE) main.postDelayed(qualityUp, 20 * 60_000L)
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(qualityUp)
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
