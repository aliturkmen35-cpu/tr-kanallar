package com.tvkanal.app

import java.net.HttpURLConnection
import java.net.URL

/** Kanalın kendi canlı yayın sayfasından o anki yayın adresini okur. Ağ çağrısı yapar. */
object StreamResolver {

    private const val DEFAULT_PATTERN = """https?:(?:\\?/){2}[^"'\s<>]+?\.m3u8[^"'\s<>]*"""

    fun resolve(ch: Channel, userAgent: String): String {
        if (ch.page.isBlank()) return ch.url
        return runCatching {
            val conn = URL(ch.page).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", userAgent)
            val html = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val regex = Regex(ch.pattern.ifBlank { DEFAULT_PATTERN })
            regex.find(html)
                ?.let { m -> m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: m.value }
                ?.replace("\\/", "/")
                ?.replace("&amp;", "&")
                ?.replace("\\u0026", "&")
        }.getOrNull() ?: ch.url
    }
}
