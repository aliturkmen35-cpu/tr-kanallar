package com.tvkanal.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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

    private fun download(url: String): String {
        // Önbellek atlatmak için zaman damgası ekle (GitHub raw ~5 dk önbellekler)
        val sep = if (url.contains('?')) '&' else '?'
        val conn = URL("$url${sep}t=${System.currentTimeMillis() / 60000}")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
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
