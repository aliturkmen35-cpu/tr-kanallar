package com.tvkanal.app

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Kanalın resmi YouTube canlı yayınını YouTube'un kendi IFrame oynatıcısıyla gösterir.
 * Oynatıcı ve YouTube markası olduğu gibi kalır; reklam ya da logo gizlenmez.
 */
class YouTubeLive(private val web: WebView, private val onFail: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    fun play(videoId: String) {
        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.setBackgroundColor(0xFF000000.toInt())
        web.addJavascriptInterface(Bridge(), "Alins")
        web.visibility = View.VISIBLE
        val html = """
            <!doctype html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:fixed;inset:0;width:100%;height:100%}</style>
            </head><body><div id="p"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
              function onYouTubeIframeAPIReady(){
                new YT.Player('p',{width:'100%',height:'100%',videoId:'$videoId',
                  playerVars:{autoplay:1,controls:0,rel:0,playsinline:1,origin:'https://alins.tv'},
                  events:{
                    onReady:function(e){e.target.unMute();e.target.setVolume(100);e.target.playVideo();},
                    onError:function(e){Alins.fail(String(e.data));}
                  }});
              }
            </script></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL("https://alins.tv/", html, "text/html", "utf-8", null)
    }

    fun stop() {
        if (web.visibility != View.VISIBLE) return
        web.loadUrl("about:blank")
        web.visibility = View.GONE
    }

    private inner class Bridge {
        @JavascriptInterface
        fun fail(@Suppress("UNUSED_PARAMETER") code: String) {
            main.post { stop(); onFail() }
        }
    }
}
