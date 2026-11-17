package com.tvkanal.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Çevrim içi güncelleme: kanal listesinin yanındaki version.json yeni sürümü haber verir,
 * APK indirilir, SHA-256 özeti doğrulanır ve Android'in kendi kurucusuna verilir.
 * Kurulumu izleyici TV'nin onay ekranında kabul eder; Android ayrıca imzası farklı
 * bir APK'yı mevcut uygulamanın üzerine kurmaz.
 */
object Updater {

    data class Info(val code: Int, val name: String, val url: String, val sha256: String, val notes: String)

    private const val ACTION = "com.tvkanal.app.UPDATE_STATUS"

    /** Sunucuda bu sürümden yenisi varsa bilgisini döndürür. Ağ çağrısı yapar. */
    fun check(): Info? {
        val base = BuildConfig.CHANNELS_URL
        if (base.isBlank()) return null
        val url = base.substringBeforeLast('/') + "/version.json?t=" + System.currentTimeMillis() / 60000
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (conn.responseCode != 200) return null
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val info = Info(
                code = o.getInt("versionCode"),
                name = o.getString("versionName"),
                url = o.getString("url"),
                sha256 = o.getString("sha256").lowercase(),
                notes = o.optString("notes"),
            )
            if (info.code <= BuildConfig.VERSION_CODE) return null
            if (!info.url.startsWith("https://") || info.sha256.length != 64) return null
            if (Build.VERSION.SDK_INT < o.optInt("minSdk", 21)) return null
            return info
        } finally {
            conn.disconnect()
        }
    }

    /** APK'yı indirir ve özetini doğrular. İlerleme 0-100. Hata olursa istisna atar. */
    fun download(context: Context, info: Info, progress: (Int) -> Unit): File {
        val file = File(context.cacheDir, "alins-guncelleme.apk")
        file.delete()
        var conn = URL(info.url).openConnection() as HttpURLConnection
        // GitHub sürüm dosyaları başka bir alan adına yönlendirir
        var hops = 0
        while (true) {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 301..308 && hops++ < 5) {
                val next = URL(conn.url, conn.getHeaderField("Location"))
                conn.disconnect()
                if (next.protocol != "https") error("güvenli olmayan yönlendirme")
                conn = next.openConnection() as HttpURLConnection
                continue
            }
            if (code != 200) { conn.disconnect(); error("HTTP $code") }
            break
        }
        val md = MessageDigest.getInstance("SHA-256")
        try {
            val total = conn.contentLength.toLong()
            var done = 0L
            var last = -1
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; progress(p) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (hex != info.sha256) {
            file.delete()
            error("indirilen dosya doğrulanamadı")
        }
        return file
    }

    /** İndirilen APK'yı Android'in kurucusuna verir; onay ekranını UpdateReceiver açar. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("alins.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }
}

/** Kurucunun yanıtı: onay gerekiyorsa TV'nin onay ekranını açar, hata olursa bildirir. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // uygulama yeni sürümle yeniden başlar
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, "Güncelleme kurulamadı\n$msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
