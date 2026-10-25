package com.tvkanal.app

import android.content.Context
import android.os.Build
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Eski Android TV'ler (7.1 öncesi) bazı yeni kök sertifikaları tanımaz
 * (ör. Let's Encrypt "ISRG Root X1"). Bu yüzden kanal listesi ve bazı yayınlar
 * o TV'lerde açılmaz. Burada sistemin sertifikalarına ek olarak uygulamayla gelen
 * kök sertifikalar da kabul edilir. Yeni Android sürümlerinde hiçbir şey değişmez.
 */
object TrustFix {
    fun install(context: Context) {
        if (Build.VERSION.SDK_INT >= 25) return
        runCatching {
            val system = trustManager(null)
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            val cf = CertificateFactory.getInstance("X.509")
            context.resources.openRawResource(R.raw.extra_roots).use { input ->
                cf.generateCertificates(input).forEachIndexed { i, c -> ks.setCertificateEntry("extra$i", c) }
            }
            val extra = trustManager(ks)
            val merged = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    system.checkClientTrusted(chain, authType)

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (e: Exception) {
                        extra.checkServerTrusted(chain, authType)
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    system.acceptedIssuers + extra.acceptedIssuers
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(merged), null) }
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
        }
    }

    private fun trustManager(ks: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
