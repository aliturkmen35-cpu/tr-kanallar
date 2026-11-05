package com.tvkanal.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Collator
import java.util.Locale

/**
 * Kanal listesini yükler. Sıra: sunucu -> son başarılı indirme (önbellek) -> APK içindeki yedek.
 */
class ChannelRepository(private val context: Context) {

    enum class Source { REMOTE, CACHE, BUNDLED }

    data class Result(val channels: List<Channel>, val source: Source)

    private val cacheFile = File(context.filesDir, "channels_cache.json")

    /** Ağ çağrısı yapar, ana thread'de çağırmayın. */
    fun load(): Result {
        val remoteUrl = BuildConfig.CHANNELS_URL
        if (remoteUrl.isNotBlank()) {
            runCatching {
                val text = download(remoteUrl)
                val list = parse(text)
                if (list.isNotEmpty()) {
                    cacheFile.writeText(text)
                    return Result(list, Source.REMOTE)
                }
            }
        }
        runCatching {
            if (cacheFile.exists()) {
                val list = parse(cacheFile.readText())
                if (list.isNotEmpty()) return Result(list, Source.CACHE)
            }
        }
        val bundled = context.assets.open("channels.json").bufferedReader().use { it.readText() }
        return Result(parse(bundled), Source.BUNDLED)
    }

    private val worldCache = File(context.filesDir, "world_cache.json")

    /**
     * iptv-org'dan derlenen dünya kanalları (world.json, kanal listesinin yanında durur).
     * Ağ çağrısı yapar. Sunucuya ulaşılamazsa son indirilen kullanılır; o da yoksa boş liste.
     */
    fun loadWorld(): List<Channel> {
        val remoteUrl = BuildConfig.CHANNELS_URL
        if (remoteUrl.isNotBlank()) {
            val worldUrl = remoteUrl.substringBeforeLast('/') + "/world.json"
            runCatching {
                val text = download(worldUrl, timeoutMs = 30_000)
                val list = parseWorld(text)
                if (list.isNotEmpty()) {
                    worldCache.writeText(text)
                    return list
                }
            }
        }
        return runCatching { if (worldCache.exists()) parseWorld(worldCache.readText()) else emptyList() }
            .getOrDefault(emptyList())
    }

    private fun parseWorld(text: String): List<Channel> {
        val tr = Locale("tr", "TR")
        val arr = JSONObject(text).getJSONArray("channels")
        val names = HashMap<String, String>()
        val list = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.optString("url")
            if (url.isBlank()) continue
            val cc = o.optString("country").uppercase(Locale.ROOT)
            val country = names.getOrPut(cc) {
                when (cc) {
                    "UK" -> Locale("", "GB").getDisplayCountry(tr)
                    "" -> "Diğer"
                    else -> Locale("", cc).getDisplayCountry(tr).ifBlank { cc }
                }
            }
            val headers = mutableMapOf<String, String>()
            o.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.getString(k) } }
            list += Channel(
                id = o.optString("id", "w$i"),
                name = o.getString("name"),
                category = country,
                url = url,
                headers = headers,
                fallbackUrl = o.optString("fallbackUrl"),
                group = if (cc == "TR") GROUP_TR else country,
            )
        }
        // Önce Türkiye, sonra diğer ülkeler; ülkeler ve kanallar Türkçe alfabe sırasıyla
        val collator = Collator.getInstance(tr)
        return list.sortedWith(compareBy<Channel> { it.group != GROUP_TR }
            .thenComparator { a, b -> collator.compare(a.group, b.group) }
            .thenComparator { a, b -> collator.compare(a.name, b.name) })
    }

    companion object {
        const val GROUP_TR = "★ Türkiye"
    }

    private fun download(url: String, timeoutMs: Int = 8000): String {
        // Önbellek atlatmak için zaman damgası ekle (GitHub raw ~5 dk önbellekler)
        val sep = if (url.contains('?')) '&' else '?'
        val conn = URL("$url${sep}t=${System.currentTimeMillis() / 60000}")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String): List<Channel> {
        val arr = JSONObject(text).getJSONArray("channels")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (!o.optBoolean("enabled", true)) return@mapNotNull null
            val url = o.optString("url")
            val page = o.optString("page")
            if (url.isBlank() && page.isBlank() && o.optString("youtube").isBlank()) return@mapNotNull null
            val headers = mutableMapOf<String, String>()
            o.optJSONObject("headers")?.let { h ->
                h.keys().forEach { k -> headers[k] = h.getString(k) }
            }
            val pageHeaders = mutableMapOf<String, String>()
            o.optJSONObject("pageHeaders")?.let { h ->
                h.keys().forEach { k -> pageHeaders[k] = h.getString(k) }
            }
            Channel(
                id = o.optString("id", "ch$i"),
                name = o.getString("name"),
                category = o.optString("category", "Diğer"),
                url = url,
                headers = headers,
                // Sayfadan anlık okuma yalnızca adresi kısa süreli olan kanallarda yapılır.
                // Diğerlerinin adresini sunucudaki bot günceller.
                page = if (o.optBoolean("resolveOnPlay") || url.isBlank()) page else "",
                pattern = o.optString("pattern"),
                type = o.optString("type", "tv"),
                fallbackUrl = o.optString("fallbackUrl"),
                fallbackPage = o.optString("fallbackPage"),
                fallbackPattern = o.optString("fallbackPattern"),
                pageHeaders = pageHeaders,
                youtube = o.optString("youtube"),
                stayOriginal = o.optBoolean("stayOriginal", false),
            )
        }
    }
}
