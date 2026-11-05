package com.tvkanal.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Yayın akışı (şu an / sırada ne var). Sunucudaki epg.json'dan gelir (bot 6 saatte bir günceller),
 * son indirilen TV'de saklanır.
 */
object Epg {
    data class Prog(val start: Long, val end: Long, val title: String)

    @Volatile private var data: Map<String, List<Prog>> = emptyMap()
    @Volatile var updated: String = ""
        private set

    private fun cache(context: Context) = File(context.filesDir, "epg_cache.json")

    /** İnternetten indir (olmazsa önbellekten oku). Ana thread'de çağırmayın. */
    fun load(context: Context) {
        val base = BuildConfig.CHANNELS_URL.substringBeforeLast('/', "")
        if (base.isNotBlank()) runCatching {
            val conn = URL("$base/epg.json?t=${System.currentTimeMillis() / 600_000}").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            val text = try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally { conn.disconnect() }
            parse(text)
            cache(context).writeText(text)
            return
        }
        loadCached(context)
    }

    fun loadCached(context: Context) {
        if (data.isNotEmpty()) return
        runCatching { cache(context).takeIf { it.exists() }?.readText()?.let { parse(it) } }
    }

    private fun parse(text: String) {
        val root = JSONObject(text)
        val o = root.getJSONObject("epg")
        val m = HashMap<String, List<Prog>>()
        o.keys().forEach { id ->
            val a = o.getJSONArray(id)
            m[id] = (0 until a.length()).map {
                val p = a.getJSONArray(it)
                Prog(p.getLong(0) * 1000, p.getLong(1) * 1000, p.getString(2))
            }
        }
        data = m
        updated = root.optString("updated")
    }

    fun programs(id: String): List<Prog> = data[id].orEmpty()

    /** Şu anki ve sıradaki program. */
    fun nowNext(id: String, at: Long = System.currentTimeMillis()): Pair<Prog?, Prog?> {
        val list = data[id] ?: return null to null
        val i = list.indexOfFirst { at < it.end }
        if (i < 0) return null to null
        val cur = list[i].takeIf { it.start <= at }
        val next = if (cur != null) list.getOrNull(i + 1) else list[i]
        return cur to next
    }

    private val hm = SimpleDateFormat("HH:mm", Locale("tr", "TR"))
    fun time(ms: Long): String = hm.format(Date(ms))

    /** Kanal bandı için: "Şimdi: Haberler (19:00–20:00) · Sonra: Dizi 20:00" */
    fun line(id: String): String? {
        val (cur, next) = nowNext(id)
        if (cur == null && next == null) return null
        return buildString {
            if (cur != null) append("Şimdi: ${cur.title}  (${time(cur.start)}–${time(cur.end)})")
            if (next != null) {
                if (isNotEmpty()) append("\n")
                append("Sonra: ${next.title}  ${time(next.start)}")
            }
        }
    }
}
