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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var blockedCount  = 0
    private val handler       = Handler(Looper.getMainLooper())
    private val APP_URL       = "https://keiriblest.github.io/SeriesFav/mobile.html"
    private val HOME_HOST     = "keiriblest.github.io"
    private var adPatterns: List<String> = emptyList()

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

    private fun isAdUrl(url: String)    = adPatterns.any { url.contains(it) }
    private fun isHomePage(url: String) = url.contains(HOME_HOST)

    // ── Bridge ────────────────────────────────────────────────────────────────
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

    // ── CSS página principal (mínimo, no invasivo) ────────────────────────────
    private val lightCssJs = """
        (function(){
          if(document.__adshieldCssLight) return; document.__adshieldCssLight=true;
          var s=document.createElement('style');
          s.textContent=
            '.voe-blocker,#voe-blocker,div[class*="voe-ad"],div[id*="voe-ad"],' +
            'div[class*="preroll"],div[class*="pre-roll"],div[class*="interstitial"],' +
            'iframe[src*="ads."],iframe[src*="pop."],iframe[src*="track."]' +
            '{display:none!important;pointer-events:none!important;}';
          (document.head||document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    // ── CSS streaming completo ────────────────────────────────────────────────
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

    // ── Touch fix (solo streaming) ────────────────────────────────────────────
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

    // ── Inyección según página ────────────────────────────────────────────────
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

    // ── Re-inyección cada 3s solo en streaming ────────────────────────────────
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
        @JavascriptInterface fun resetCount(): Int { blockedCount = 0; return 0 }
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

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!isHomePage(url)) view.evaluateJavascript(bridgeAliasJs, null)
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url    = request.url.toString()
                val accept = request.requestHeaders["Accept"] ?: ""

                // Bloquear recursos de ads (scripts, imágenes, etc.)
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

                // Bloquar iframes de ads puros (los que su URL ya es un dominio de ad)
                if (!request.isForMainFrame && accept.contains("text/html")) {
                    val host = request.url.host ?: ""
                    if (adPatterns.any { host.contains(it) }) {
                        blockedCount++
                        return WebResourceResponse("text/html","utf-8",
                            ByteArrayInputStream("<html><body></body></html>".toByteArray()))
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            // Solo bloquear navegación si la URL es claramente un ad
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = isAdUrl(request.url.toString())

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

        // Bloquear TODOS los popups de cualquier frame
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
