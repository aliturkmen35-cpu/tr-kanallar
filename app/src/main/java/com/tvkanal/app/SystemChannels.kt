package com.tvkanal.app

import android.content.ComponentName
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * Alin's kanallarını TV'nin kendi sistemine tanıtır:
 *  1) TV Input Framework: kanallar TV'nin "Canlı TV / Kanallar" uygulamasında ve
 *     giriş kaynakları arasında normal bir yayın kaynağı gibi görünür.
 *  2) Ana ekran satırı: favoriler ve Alin's kanalları TV ana ekranında bir satır olur.
 */
object SystemChannels {
    private const val TAG = "AlinsSystem"
    const val SCHEME = "alins"
    const val HOST = "kanal"

    fun inputId(context: Context): String =
        TvContract.buildInputId(ComponentName(context, AlinsTvInputService::class.java))

    /** Sisteme hangi kanallar girsin: Alin's, Türkiye, eklenenler ve favoriler (13 bin kanalın hepsi değil). */
    fun pick(channels: List<Channel>, favorites: List<String>, hidden: Set<String>): List<Pair<Int, Channel>> {
        val favSet = favorites.toHashSet()
        return channels.withIndex()
            .filter { (_, c) ->
                c.id !in hidden && (c.url.isNotBlank() || c.page.isNotBlank()) &&
                    (c.group == "Alin's" || c.group == ChannelRepository.GROUP_TR ||
                        c.group == UserEdits.GROUP_CUSTOM || c.id in favSet)
            }
            .map { (i, c) -> (i + 1) to c }
    }

    /** Kanalları TV'nin kanal veritabanına yazar. Değişiklik yoksa dokunmaz. Döndürür: kanal sayısı. */
    fun syncTif(context: Context, list: List<Pair<Int, Channel>>, force: Boolean = false): Int {
        val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
        val sig = list.joinToString("|") { "${it.first}:${it.second.id}:${it.second.name}" }.hashCode()
        if (!force && prefs.getInt("tifSig", 0) == sig) return list.size
        val input = inputId(context)
        val cr = context.contentResolver
        return runCatching {
            cr.delete(TvContract.buildChannelsUriForInput(input), null, null)
            val ops = ArrayList<ContentProviderOperation>(list.size)
            for ((num, c) in list) {
                val v = ContentValues().apply {
                    put(TvContract.Channels.COLUMN_INPUT_ID, input)
                    put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, num.toString())
                    put(TvContract.Channels.COLUMN_DISPLAY_NAME, c.name)
                    put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                    put(TvContract.Channels.COLUMN_SERVICE_TYPE,
                        if (c.isRadio) TvContract.Channels.SERVICE_TYPE_AUDIO
                        else TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
                    put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, c.id.toByteArray())
                    put(TvContract.Channels.COLUMN_SEARCHABLE, 1)
                    if (Build.VERSION.SDK_INT >= 23) {
                        put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, playIntent(context, c.id).toUri(Intent.URI_INTENT_SCHEME))
                        put(TvContract.Channels.COLUMN_APP_LINK_TEXT, "Alin's'te aç")
                    }
                }
                ops += ContentProviderOperation.newInsert(TvContract.Channels.CONTENT_URI).withValues(v).build()
            }
            ops.chunked(100).forEach { cr.applyBatch(TvContract.AUTHORITY, ArrayList(it)) }
            prefs.edit().putInt("tifSig", sig).apply()
            Log.i(TAG, "TV kanal sistemine ${list.size} kanal yazıldı")
            list.size
        }.onFailure { Log.w(TAG, "TIF eşitleme olmadı", it) }.getOrDefault(0)
    }

    /** Ana ekranda "Alin's" satırı (Android 8+): önce favoriler, sonra Alin's kanalları. */
    fun syncHomeRow(context: Context, channels: List<Channel>, favorites: List<String>, hidden: Set<String>) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
            val byId = channels.associateBy { it.id }
            val items = (favorites.mapNotNull { byId[it] } + channels.filter { it.group == "Alin's" })
                .filter { it.id !in hidden }
                .distinctBy { it.id }
                .take(30)
            val sig = items.joinToString("|") { it.id + it.name }.hashCode()
            val helper = PreviewChannelHelper(context)
            var rowId = prefs.getLong("homeRow", -1L)
            if (rowId > 0 && helper.getPreviewChannel(rowId) == null) rowId = -1L
            if (rowId > 0 && prefs.getInt("homeSig", 0) == sig) return
            val logo = Uri.parse("android.resource://${context.packageName}/${R.drawable.banner}")
            if (rowId < 0) {
                val ch = PreviewChannel.Builder()
                    .setDisplayName("Alin's TV")
                    .setDescription("Favori kanallarınız")
                    .setAppLinkIntent(Intent(context, MainActivity::class.java))
                    .setLogo(logo)
                    .build()
                rowId = helper.publishDefaultChannel(ch)
                prefs.edit().putLong("homeRow", rowId).apply()
            }
            // Eski kanal kartlarını sil, yenilerini yaz
            context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(rowId), null, null)
            val favSet = favorites.toHashSet()
            items.forEachIndexed { i, c ->
                val p = PreviewProgram.Builder()
                    .setChannelId(rowId)
                    .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                    .setTitle(c.name)
                    .setDescription(if (c.id in favSet) "♥ Favori" else c.group)
                    .setLive(true)
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                    .setPosterArtUri(logo)
                    .setIntent(playIntent(context, c.id))
                    .setInternalProviderId(c.id)
                    .setWeight(1000 - i)
                    .build()
                helper.publishPreviewProgram(p)
            }
            prefs.edit().putInt("homeSig", sig).apply()
            Log.i(TAG, "Ana ekran satırı ($rowId): ${items.size} kanal")
        }.onFailure { Log.w(TAG, "Ana ekran satırı olmadı", it) }
    }

    /**
     * Uygulamadaki sırayla bütün kanallar (Alin's + dünya + eklenenler, izleyicinin verdiği adlarla).
     * İnternete çıkmaz: son indirilen listeleri kullanır. Kanal numaraları uygulamadakiyle aynıdır.
     */
    fun allChannels(context: Context): List<Channel> {
        val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
        val edits = UserEdits(prefs)
        val repo = ChannelRepository(context)
        val world = if (prefs.getBoolean("showWorld", true)) repo.cachedWorld() else emptyList()
        return (repo.cachedCurated() + world + edits.custom).map { c ->
            edits.names[c.id]?.let { c.copy(name = it) } ?: c
        }
    }

    /** Hem TV kanal sistemini hem ana ekran satırını eşitler. Döndürür: sisteme giren kanal sayısı. */
    fun syncAll(context: Context, force: Boolean = false): Int {
        val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
        val edits = UserEdits(prefs)
        val all = allChannels(context)
        val n = syncTif(context, pick(all, edits.favorites, edits.hidden), force)
        Epg.loadCached(context)
        syncPrograms(context, force)
        syncHomeRow(context, all, edits.favorites, edits.hidden)
        return n
    }

    /** TV'nin yayın rehberine (program akışı ekranı) Alin's kanallarının programlarını yazar. */
    fun syncPrograms(context: Context, force: Boolean = false) {
        if (Epg.updated.isBlank()) return
        val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
        val sig = (Epg.updated + prefs.getInt("tifSig", 0)).hashCode()
        if (!force && prefs.getInt("epgSig", 0) == sig) return
        val cr = context.contentResolver
        runCatching {
            val rows = HashMap<String, Long>()
            cr.query(TvContract.buildChannelsUriForInput(inputId(context)),
                arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA), null, null, null)
                ?.use { c -> while (c.moveToNext()) c.getBlob(1)?.let { rows[String(it)] = c.getLong(0) } }
            var count = 0
            for ((id, rowId) in rows) {
                val progs = Epg.programs(id)
                if (progs.isEmpty()) continue
                cr.delete(TvContract.buildProgramsUriForChannel(rowId), null, null)
                val values = progs.map { p ->
                    ContentValues().apply {
                        put(TvContract.Programs.COLUMN_CHANNEL_ID, rowId)
                        put(TvContract.Programs.COLUMN_TITLE, p.title)
                        put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, p.start)
                        put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, p.end)
                    }
                }.toTypedArray()
                count += cr.bulkInsert(TvContract.Programs.CONTENT_URI, values)
            }
            prefs.edit().putInt("epgSig", sig).apply()
            Log.i(TAG, "TV rehberine $count program yazıldı")
        }.onFailure { Log.w(TAG, "Rehber yazılamadı", it) }
    }

    /** İzleyici TV'nin Canlı TV uygulamasında Alin's kanallarını açtı mı (en az bir kanal listede görünür)? */
    fun userEnabledInLiveTv(context: Context): Boolean = runCatching {
        var total = 0; var on = 0
        context.contentResolver.query(TvContract.buildChannelsUriForInput(inputId(context)),
            arrayOf(TvContract.Channels.COLUMN_BROWSABLE), null, null, null)
            ?.use { c -> while (c.moveToNext()) { total++; if (c.getInt(0) == 1) on++ } }
        Log.i(TAG, "Canlı TV'de açık kanal: $on / $total")
        on > 0
    }.onFailure { Log.w(TAG, "Canlı TV durumu okunamadı", it) }.getOrDefault(false)

    /** Kanalı doğrudan açan bağlantı: alins://kanal/<kimlik> */
    fun playIntent(context: Context, id: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(id).build())
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun idFromIntent(intent: Intent?): String? {
        val d = intent?.data ?: return null
        return if (d.scheme == SCHEME && d.host == HOST) d.lastPathSegment else null
    }

    /** TIF kanal satırından Alin's kanal kimliğini okur. */
    fun idFromChannelUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getBlob(0)?.let { String(it) } else null }
    }.getOrNull()
}
