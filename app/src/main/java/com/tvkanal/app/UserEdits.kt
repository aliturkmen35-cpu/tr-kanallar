package com.tvkanal.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * İzleyicinin kendi düzenlemeleri: favoriler (sıralı), gizlenen kanallar,
 * yeniden adlandırmalar ve elle eklenen kanallar. TV'de (SharedPreferences) saklanır.
 */
class UserEdits(private val prefs: SharedPreferences) {

    val favorites: MutableList<String> = readList("favs")
    val hidden: MutableSet<String> = readList("hidden").toMutableSet()
    val names: MutableMap<String, String> = readMap("names")
    val custom: MutableList<Channel> = readCustom()

    fun isFavorite(id: String) = id in favorites

    fun toggleFavorite(id: String) {
        if (!favorites.remove(id)) favorites += id
        save()
    }

    fun moveFavorite(id: String, delta: Int) {
        val i = favorites.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in favorites.indices) return
        favorites[i] = favorites[j].also { favorites[j] = id }
        save()
    }

    fun toggleHidden(id: String) {
        if (!hidden.remove(id)) hidden += id
        save()
    }

    fun rename(id: String, name: String?) {
        if (name.isNullOrBlank()) names.remove(id) else names[id] = name.trim()
        save()
    }

    fun addCustom(name: String, url: String) {
        custom += Channel(id = "u:${System.currentTimeMillis()}", name = name.trim(), category = GROUP_CUSTOM,
            url = url.trim(), group = GROUP_CUSTOM)
        save()
    }

    fun removeCustom(id: String) {
        custom.removeAll { it.id == id }
        favorites.remove(id); hidden.remove(id); names.remove(id)
        save()
    }

    private fun save() {
        prefs.edit()
            .putString("favs", JSONArray(favorites).toString())
            .putString("hidden", JSONArray(hidden.toList()).toString())
            .putString("names", JSONObject(names as Map<*, *>).toString())
            .putString("custom", JSONArray(custom.map {
                JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)
            }).toString())
            .apply()
    }

    private fun readList(key: String): MutableList<String> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]"))
        MutableList(a.length()) { a.getString(it) }
    }.getOrDefault(mutableListOf())

    private fun readMap(key: String): MutableMap<String, String> = runCatching {
        val o = JSONObject(prefs.getString(key, "{}")!!)
        o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun readCustom(): MutableList<Channel> = runCatching {
        val a = JSONArray(prefs.getString("custom", "[]"))
        MutableList(a.length()) {
            val o = a.getJSONObject(it)
            Channel(id = o.getString("id"), name = o.getString("name"), category = GROUP_CUSTOM,
                url = o.getString("url"), group = GROUP_CUSTOM)
        }
    }.getOrDefault(mutableListOf())

    companion object {
        const val GROUP_CUSTOM = "Eklediklerim"

        private val TR = Locale("tr", "TR")

        /** Aramada Türkçe karakter ve büyük/küçük harf farkını yok sayar: "şow" ~ "Show". */
        fun fold(s: String): String = s.lowercase(TR)
            .replace('ı', 'i').replace('ş', 's').replace('ç', 'c')
            .replace('ğ', 'g').replace('ö', 'o').replace('ü', 'u').replace('â', 'a').replace('î', 'i')
    }
}
