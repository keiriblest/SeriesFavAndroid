package com.keirible.seriesfav

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.keirible.seriesfav.R
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var blockedCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val APP_URL = "https://keiriblest.github.io/SeriesFav/mobile.html"

    // Pre-inicializado en onCreate para evitar problemas de threading
    private var adPatterns: List<String> = emptyList()

    private fun readAsset(name: String): String? = try {
        assets.open("adshield/$name").bufferedReader().readText()
    } catch (e: Exception) { null }

    private fun loadAdPatterns() {
        val json = readAsset("rules.json") ?: return
        val arr = JSONArray(json)
        adPatterns = (0 until arr.length()).mapNotNull { i ->
            arr.getJSONObject(i)
                .optJSONObject("condition")
                ?.optString("urlFilter")
                ?.replace("*", "")
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun isAdUrl(url: String) = adPatterns.any { url.contains(it) }

    // Bridge alias: conecta window.adshieldElectron → window.AdShield (Kotlin)
    private val bridgeAliasJs = """
        (function(){
          if(window.__adshieldBridge) return;
          window.__adshieldBridge = true;
          window.adshieldElectron = {
            reportBlocked: function(){ try{ AdShield.contentBlocked(); }catch(_){} },
            getCount:      function(){ try{ return AdShield.getCount(); }catch(_){ return 0; } },
            resetCount:    function(){ try{ return AdShield.resetCount(); }catch(_){ return 0; } },
            onCountUpdate: function(){}
          };
        })();
    """.trimIndent()

    // Fix de touch para Android: content-electron.js rastrea mousedown/click
    // pero en móvil los eventos son touchstart/touchend.
    // Inyectamos listeners ADICIONALES sin monkey-patching para no romper nada.
    private val touchFixJs = """
        (function(){
          if(window.__adshieldTouchFix) return;
          window.__adshieldTouchFix = true;
          document.addEventListener('touchstart', function(e){
            try {
              var t = e.touches[0];
              if(!t) return;
              // Alimentar las mismas variables que usa content-electron.js
              window.__lastTouchTarget = e.target;
              // Disparar mousedown sintético para activar el tracker del script
              var fake = new MouseEvent('mousedown',{bubbles:true,cancelable:true,
                clientX:t.clientX,clientY:t.clientY});
              e.target.dispatchEvent(fake);
            } catch(_){}
          }, true);
        })();
    """.trimIndent()

    // CSS completo extraído directamente de content-electron.js original
    private val adCssJs = """
        (function(){
          if(document.__adshieldCssContent) return;
          document.__adshieldCssContent = true;
          var s = document.createElement('style');
          s.textContent = '\n' +
            'div[style*="position: fixed"][style*="top: 0"],\n' +
            'div[style*="position:fixed"][style*="top:0"],\n' +
            'div[style*="z-index: 2147483647"], div[style*="z-index:2147483647"],\n' +
            'div[style*="z-index: 999999"], div[style*="z-index:999999"],\n' +
            'div[style*="z-index: 99999"], div[style*="z-index:99999"],\n' +
            '[style*="2147483647"],\n' +
            '.voe-blocker, #voe-blocker,\n' +
            'div[class*="voe-ad"], div[id*="voe-ad"],\n' +
            'div[class*="pop-up"], div[class*="popup"], div[id*="popup"],\n' +
            'div[id*="overlay-ad"], div[class*="ad-overlay"], div[class*="ad-layer"],\n' +
            'div[class*="preroll"], div[class*="pre-roll"], div[class*="interstitial"],\n' +
            '.jw-overlays > div:not([class*="jw-"]),\n' +
            'iframe[src*="ads."], iframe[src*="pop."], iframe[src*="track."],\n' +
            'iframe[src*="click."], iframe[id*="ad"], iframe[class*="ad"] {\n' +
            'display: none !important; visibility: hidden !important;\n' +
            'pointer-events: none !important; opacity: 0 !important;\n' +
            'height: 0 !important; width: 0 !important; }\n';
          (document.head || document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    // Inyectar scripts HTML en iframes interceptados para bloquear ads dentro
    private fun buildIframeInjection(): String {
        val voe = readAsset("voe-ad-cleaner.js") ?: ""
        val bridge = bridgeAliasJs.replace("</script>","<\\/script>")
        val voeSafe = voe.replace("</script>","<\\/script>")
        return "<script>$bridge\n$voeSafe</script>"
    }

    private fun injectIntoIframeResponse(url: String, reqHeaders: Map<String, String>): WebResourceResponse? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout    = 8000
            conn.instanceFollowRedirects = true
            // Reenviar headers originales excepto los problemáticos
            reqHeaders.forEach { (k, v) ->
                if (!k.equals("Range", ignoreCase = true)) conn.setRequestProperty(k, v)
            }
            val code = conn.responseCode
            if (code != 200) return null
            val ct      = conn.contentType ?: "text/html"
            val charset = Regex("charset=([\\w-]+)").find(ct)?.groupValues?.get(1) ?: "utf-8"
            var html    = conn.inputStream.bufferedReader(charset(charset)).readText()
            val inject  = buildIframeInjection()
            // Inyectar justo después de <head> o al inicio de <html>
            html = when {
                html.contains("<head>",  ignoreCase = true) ->
                    html.replaceFirst(Regex("(?i)<head>"), "<head>$inject")
                html.contains("<html>",  ignoreCase = true) ->
                    html.replaceFirst(Regex("(?i)<html>"), "<html>$inject")
                else -> "$inject$html"
            }
            WebResourceResponse("text/html", charset,
                ByteArrayInputStream(html.toByteArray(charset(charset))))
        } catch (e: Exception) { null }
    }

    private fun injectAll() {
        webView.evaluateJavascript(bridgeAliasJs, null)
        webView.evaluateJavascript(touchFixJs, null)
        webView.evaluateJavascript(adCssJs, null)
        val contentJs = readAsset("content-electron.js")
        val voeJs     = readAsset("voe-ad-cleaner.js")
        contentJs?.let {
            webView.evaluateJavascript(
                "(function(){ if(window.__adshieldContent) return; window.__adshieldContent=true; $it })();", null)
        }
        voeJs?.let {
            webView.evaluateJavascript(
                "(function(){ if(window.__adshieldVoe) return; window.__adshieldVoe=true; $it })();", null)
        }
    }

    private val reInjectRunnable = object : Runnable {
        override fun run() {
            webView.evaluateJavascript(bridgeAliasJs, null)
            webView.evaluateJavascript(adCssJs, null)
            val voeJs = readAsset("voe-ad-cleaner.js")
            voeJs?.let {
                webView.evaluateJavascript(
                    "(function(){ if(!window.__adshieldVoeReactive){ window.__adshieldVoeReactive=true; $it } })()", null)
            }
            handler.postDelayed(this, 3000)
        }
    }

    inner class AdShieldBridge {
        @JavascriptInterface fun getCount(): Int = blockedCount
        @JavascriptInterface fun resetCount(): Int { blockedCount = 0; return 0 }
        @JavascriptInterface fun contentBlocked() { blockedCount++ }
    }

    private fun charset(name: String) = try {
        Charsets.forName(name)
    } catch (e: Exception) { Charsets.UTF_8 }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)

        // Pre-cargar patrones en hilo principal
        loadAdPatterns()

        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort                  = true
            loadWithOverviewMode             = true
            // User agent móvil real para que los sitios sirvan versión mobile
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        WebView.setWebContentsDebuggingEnabled(true)
        webView.addJavascriptInterface(AdShieldBridge(), "AdShield")

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Inyectar bridge lo antes posible
                view.evaluateJavascript(bridgeAliasJs, null)
                view.evaluateJavascript(touchFixJs, null)
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url  = request.url.toString()
                val accept = request.requestHeaders["Accept"] ?: ""

                // Bloquear recursos de ads
                if (isAdUrl(url)) {
                    blockedCount++
                    return WebResourceResponse("text/plain", "utf-8",
                        ByteArrayInputStream(ByteArray(0)))
                }

                // Bloquear iframes data: (PDF popunder trick)
                if (url.startsWith("data:application/pdf") ||
                    url.startsWith("data:application/octet")) {
                    return WebResourceResponse("text/plain", "utf-8",
                        ByteArrayInputStream(ByteArray(0)))
                }

                // Inyectar scripts AdShield en iframes HTML de otros dominios
                if (!request.isForMainFrame &&
                    accept.contains("text/html") &&
                    url.startsWith("http")) {
                    val injected = injectIntoIframeResponse(url, request.requestHeaders)
                    if (injected != null) return injected
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                // Solo bloquear si la URL es claramente un ad
                return isAdUrl(request.url.toString())
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectAll()
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame)
                    handler.postDelayed({ view.loadUrl(APP_URL) }, 1500)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean,
                isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean = false
        }

        webView.loadUrl(APP_URL)
        handler.postDelayed(reInjectRunnable, 3000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(reInjectRunnable)
        webView.destroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
