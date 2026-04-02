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
import java.nio.charset.Charset

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var blockedCount  = 0
    private val handler       = Handler(Looper.getMainLooper())
    private val APP_URL       = "https://keiriblest.github.io/SeriesFav/mobile.html"
    private val HOME_HOST     = "keiriblest.github.io"
    private var adPatterns: List<String> = emptyList()

    // ── CAPA 1: Dominios de ad hardcodeados (equivalente a Cliqz) ─────────────
    // Cubre los ad-networks más comunes en sitios de streaming
    private val hardcodedAdHosts = listOf(
        "exoclick.com", "exosrv.com", "exdynsrv.com",
        "popcash.net", "popads.net", "popadvert.com",
        "propellerads.com", "propellerclick.com",
        "trafficjunky.net", "trafficfactory.biz",
        "adsterra.com", "adstera.com",
        "juicyads.com", "hilltopads.net",
        "clickadu.com", "clickaine.com",
        "adnium.com", "adspyglass.com",
        "ero-advertising.com", "tsyndicate.com",
        "fuckingfast.co", "javhd.com",
        "ads.php", "pop.php", "popunder",
        "track.php", "click.php", "redirect.php",
        "doubleclick.net", "googlesyndication.com",
        "adservice.google.com", "pagead2.googlesyndication.com"
    )

    private fun readAsset(name: String): String? = try {
        assets.open("adshield/$name").bufferedReader().readText()
    } catch (e: Exception) { null }

    private fun loadAdPatterns() {
        val json = readAsset("rules.json") ?: return
        val arr  = JSONArray(json)
        adPatterns = (0 until arr.length()).mapNotNull { i ->
            arr.getJSONObject(i)
                .optJSONObject("condition")
                ?.optString("urlFilter")
                ?.replace("*", "")
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun isAdUrl(url: String): Boolean {
        val lUrl = url.lowercase()
        return adPatterns.any { lUrl.contains(it) } ||
               hardcodedAdHosts.any { lUrl.contains(it) }
    }
    private fun isHomePage(url: String) = url.contains(HOME_HOST)

    // ── Bridge (equivalente al preload.js de Electron) ────────────────────────
    private val bridgeAliasJs = """
        (function(){
          if(window.__adshieldBridge) return; window.__adshieldBridge=true;
          window.adshieldElectron={
            reportBlocked:function(){try{AdShield.contentBlocked();}catch(_){}},
            getCount:     function(){try{return AdShield.getCount();}catch(_){return 0;}},
            resetCount:   function(){try{return AdShield.resetCount();}catch(_){return 0;}},
            onCountUpdate:function(){}
          };
        })();
    """.trimIndent()

    // ── CSS suave para página principal ───────────────────────────────────────
    private val lightCssJs = """
        (function(){
          if(document.__adshieldCssLight) return; document.__adshieldCssLight=true;
          var s=document.createElement('style');
          s.textContent=
            '.voe-blocker,#voe-blocker,div[class*="voe-ad"],div[id*="voe-ad"],' +
            'div[class*="preroll"],div[class*="pre-roll"],iframe[src*="ads."],' +
            'iframe[src*="pop."],iframe[src*="track."]{display:none!important;}';
          (document.head||document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    // ── CSS completo para páginas de streaming ────────────────────────────────
    private val fullCssJs = """
        (function(){
          if(document.__adshieldCssContent) return; document.__adshieldCssContent=true;
          var s=document.createElement('style');
          s.textContent='\n'+
            'div[style*="position: fixed"][style*="top: 0"],\n'+
            'div[style*="position:fixed"][style*="top:0"],\n'+
            'div[style*="z-index: 2147483647"],div[style*="z-index:2147483647"],\n'+
            'div[style*="z-index: 999999"],div[style*="z-index:999999"],\n'+
            '[style*="2147483647"],\n'+
            '.voe-blocker,#voe-blocker,div[class*="voe-ad"],div[id*="voe-ad"],\n'+
            'div[class*="pop-up"],div[class*="popup"],div[id*="popup"],\n'+
            'div[id*="overlay-ad"],div[class*="ad-overlay"],div[class*="ad-layer"],\n'+
            'div[class*="preroll"],div[class*="pre-roll"],div[class*="interstitial"],\n'+
            '.jw-overlays > div:not([class*="jw-"]),\n'+
            'iframe[src*="ads."],iframe[src*="pop."],iframe[src*="track."],\n'+
            'iframe[src*="click."],iframe[id*="ad"],iframe[class*="ad"]{\n'+
            'display:none!important;visibility:hidden!important;\n'+
            'pointer-events:none!important;opacity:0!important;\n'+
            'height:0!important;width:0!important;}\n';
          (document.head||document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    // ── Touch fix ─────────────────────────────────────────────────────────────
    private val touchFixJs = """
        (function(){
          if(window.__adshieldTouchFix) return; window.__adshieldTouchFix=true;
          document.addEventListener('touchstart',function(e){
            try{
              var t=e.touches[0]; if(!t) return;
              var fake=new MouseEvent('mousedown',{bubbles:true,cancelable:true,
                clientX:t.clientX,clientY:t.clientY});
              e.target.dispatchEvent(fake);
            }catch(_){}
          },{passive:true,capture:true});
        })();
    """.trimIndent()

    // ── CAPA 4: Script mínimo que se inyecta en iframes (equivalente a frame-created)
    // Solo bloquea window.open y top.location — sin loops pesados que freezeen
    private val iframeMinimalJs = """
        (function(){
          if(window.__ifs) return; window.__ifs=true;
          // Bloquear window.open dentro del iframe
          window.open=function(u){
            try{if(window.AdShield)AdShield.contentBlocked();}catch(_){}
            return{closed:false,close:function(){},focus:function(){},
                   location:{href:'about:blank',assign:function(){},replace:function(){}}};
          };
          // Evitar que el iframe redirecte el frame padre
          try{Object.defineProperty(window,'top',{get:function(){return window;},configurable:true});}catch(_){}
          try{Object.defineProperty(window,'parent',{get:function(){return window;},configurable:true});}catch(_){}
          // CSS anti-overlay dentro del iframe
          var s=document.createElement('style');
          s.textContent='[style*="2147483647"]{display:none!important;pointer-events:none!important;}'+
            '.voe-blocker,#voe-blocker,div[class*="voe-ad"]{display:none!important;}';
          (document.head||document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    // ── CAPA 4: Inyección en iframe via HTTP intercept (ligero, solo CSS+bloqueo)
    private fun injectIntoIframeResponse(url: String, reqHeaders: Map<String,String>): WebResourceResponse? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout    = 6000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", webView.settings.userAgentString)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            // Pasar Referer si existe
            reqHeaders["Referer"]?.let { conn.setRequestProperty("Referer", it) }
            if (conn.responseCode != 200) return null
            val ct      = conn.contentType ?: "text/html"
            val charset = Regex("charset=([\\w-]+)").find(ct)?.groupValues?.get(1) ?: "utf-8"
            val cs      = safeCharset(charset)
            val html    = conn.inputStream.bufferedReader(cs).readText()
            // Solo inyectar si es una página HTML real (no JSON, binario, etc.)
            if (!html.trimStart().startsWith("<")) return null
            val scriptSafe = iframeMinimalJs.replace("</script>","<\\/script>")
            val voeSafe    = (readAsset("voe-ad-cleaner.js") ?: "").replace("</script>","<\\/script>")
            val inject     = "<script>$scriptSafe\n$voeSafe</script>"
            val modified = when {
                html.contains("<head>",ignoreCase=true) ->
                    html.replaceFirst(Regex("(?i)<head>"), "<head>$inject")
                html.contains("<body>",ignoreCase=true) ->
                    html.replaceFirst(Regex("(?i)<body>"), "<body>$inject")
                else -> "$inject$html"
            }
            WebResourceResponse("text/html", charset, ByteArrayInputStream(modified.toByteArray(cs)))
        } catch (e: Exception) { null }
    }

    private fun safeCharset(name: String): Charset = try {
        Charset.forName(name)
    } catch (e: Exception) { Charsets.UTF_8 }

    // ── CAPAS 3+5: Inyección en frame principal ───────────────────────────────
    private fun injectForPage(url: String) {
        if (isHomePage(url)) {
            webView.evaluateJavascript(lightCssJs, null)
        } else {
            webView.evaluateJavascript(bridgeAliasJs, null)
            webView.evaluateJavascript(touchFixJs, null)
            webView.evaluateJavascript(fullCssJs, null)
            readAsset("content-electron.js")?.let {
                webView.evaluateJavascript(
                    "(function(){if(window.__adshieldContent)return;window.__adshieldContent=true;$it})();", null)
            }
            readAsset("voe-ad-cleaner.js")?.let {
                webView.evaluateJavascript(
                    "(function(){if(window.__adshieldVoe)return;window.__adshieldVoe=true;$it})();", null)
            }
        }
    }

    // ── Red de seguridad: re-inyección cada 3s (solo en streaming) ────────────
    private val reInjectRunnable = object : Runnable {
        override fun run() {
            val url = try { webView.url ?: "" } catch (e: Exception) { "" }
            if (!isHomePage(url)) {
                webView.evaluateJavascript(bridgeAliasJs, null)
                webView.evaluateJavascript(fullCssJs, null)
                readAsset("voe-ad-cleaner.js")?.let {
                    webView.evaluateJavascript(
                        "(function(){if(!window.__adshieldVoeR){window.__adshieldVoeR=true;$it}})()", null)
                }
            }
            handler.postDelayed(this, 3000)
        }
    }

    inner class AdShieldBridge {
        @JavascriptInterface fun getCount(): Int = blockedCount
        @JavascriptInterface fun resetCount(): Int { blockedCount=0; return 0 }
        @JavascriptInterface fun contentBlocked() { blockedCount++ }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        loadAdPatterns()

        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort                  = true
            loadWithOverviewMode             = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        WebView.setWebContentsDebuggingEnabled(true)
        webView.addJavascriptInterface(AdShieldBridge(), "AdShield")

        webView.webViewClient = object : WebViewClient() {

            // CAPA 3: Inyectar bridge lo antes posible
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!isHomePage(url)) view.evaluateJavascript(bridgeAliasJs, null)
            }

            // CAPA 1: Bloqueo de red + CAPA 4: Inyección en iframes
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url    = request.url.toString()
                val accept = request.requestHeaders["Accept"] ?: ""

                // Bloquear recursos de ads (scripts, imágenes, iframes de ad)
                if (isAdUrl(url)) {
                    blockedCount++
                    return WebResourceResponse("text/plain","utf-8",
                        ByteArrayInputStream(ByteArray(0)))
                }

                // Bloquear PDF popunder trick
                if (url.startsWith("data:application/pdf") ||
                    url.startsWith("data:application/octet")) {
                    return WebResourceResponse("text/plain","utf-8",
                        ByteArrayInputStream(ByteArray(0)))
                }

                // CAPA 4: Inyectar en iframes HTML de streaming
                // Solo para iframes que NO son ads (ya bloqueados arriba)
                // y NO son la página principal
                if (!request.isForMainFrame &&
                    accept.contains("text/html") &&
                    url.startsWith("https") &&
                    !isHomePage(url)) {
                    val injected = injectIntoIframeResponse(url, request.requestHeaders)
                    if (injected != null) return injected
                }

                return super.shouldInterceptRequest(view, request)
            }

            // CAPA 2: Bloquear navegación a URLs de ads
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = isAdUrl(request.url.toString())

            // CAPAS 3+5: Inyección completa al terminar la carga
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectForPage(url)
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame)
                    handler.postDelayed({ view.loadUrl(APP_URL) }, 1500)
            }
        }

        // CAPA 2: Bloquar TODOS los popups de cualquier frame
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
