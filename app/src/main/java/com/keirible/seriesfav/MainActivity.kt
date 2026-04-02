package com.keirible.seriesfav

import android.annotation.SuppressLint
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
    private var blockedCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val APP_URL = "https://keiriblest.github.io/SeriesFav/mobile.html"

    private fun readAsset(name: String): String? = try {
        assets.open("adshield/$name").bufferedReader().readText()
    } catch (e: Exception) { null }

    private val adPatterns: List<String> by lazy {
        val json = readAsset("rules.json") ?: return@lazy emptyList()
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            arr.getJSONObject(i)
                .optJSONObject("condition")
                ?.optString("urlFilter")
                ?.replace("*", "")
                ?.takeIf { it.isNotBlank() }
        }
    }

    // PROBLEMA 1: content-electron.js usa mousedown/click para trackear el último
    // elemento tocado. En Android son touch events, no mouse events.
    // Solución: inyectar listeners de touchstart que alimentan las mismas variables.
    private val touchFixJs = """
        (function(){
          if(window.__adshieldTouchFix) return;
          window.__adshieldTouchFix = true;
          document.addEventListener('touchstart', function(e){
            if(e.touches && e.touches[0]){
              window.__lastTouchX = e.touches[0].clientX;
              window.__lastTouchY = e.touches[0].clientY;
              window.__lastTouchTarget = e.target;
            }
          }, true);
          // Monkey-patch para que los scripts lean touch coords si no hay mouse
          var _origAdd = document.addEventListener.bind(document);
          document.addEventListener = function(type, fn, capture){
            if(type === 'mousedown'){
              _origAdd('touchstart', function(e){
                var fake = {target: e.target};
                fn(fake);
              }, capture);
            }
            if(type === 'click'){
              _origAdd('touchend', function(e){
                if(e.changedTouches && e.changedTouches[0]){
                  var fake = {clientX: e.changedTouches[0].clientX, clientY: e.changedTouches[0].clientY};
                  fn(fake);
                }
              }, capture);
            }
            return _origAdd(type, fn, capture);
          };
        })();
    """.trimIndent()

    // PROBLEMA 2: el bridge window.adshieldElectron debe estar disponible
    // ANTES de que corran los scripts. Lo inyectamos en onPageStarted.
    private val bridgeAliasJs = """
        (function(){
          if(window.adshieldElectron) return;
          window.adshieldElectron = {
            reportBlocked: function() {
              try { if(window.AdShield) window.AdShield.contentBlocked(); } catch(_) {}
            }
          };
        })();
    """.trimIndent()

    private fun injectAll(phase: String) {
        val contentJs = readAsset("content-electron.js")
        val voeJs     = readAsset("voe-ad-cleaner.js")
        // En onPageStarted solo inyectamos el bridge (el DOM no existe aún)
        webView.evaluateJavascript(bridgeAliasJs, null)
        webView.evaluateJavascript(touchFixJs, null)
        if (phase == "finished") {
            // Scripts completos solo cuando el DOM está listo
            contentJs?.let {
                val guard = "if(window.__adshieldContent) return; window.__adshieldContent=true;"
                webView.evaluateJavascript("(function(){$guard$it})();", null)
            }
            voeJs?.let {
                val guard = "if(window.__adshieldVoe) return; window.__adshieldVoe=true;"
                webView.evaluateJavascript("(function(){$guard$it})();", null)
            }
        }
    }

    private val reInjectRunnable = object : Runnable {
        override fun run() {
            val voeJs = readAsset("voe-ad-cleaner.js")
            if (voeJs != null) {
                webView.evaluateJavascript(bridgeAliasJs, null)
                webView.evaluateJavascript(
                    "(function(){ if(!window.__adshieldVoeActive){ window.__adshieldVoeActive=true; $voeJs } })()", null
                )
            }
            handler.postDelayed(this, 4000)
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

        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort                  = true
            loadWithOverviewMode             = true
        }

        WebView.setWebContentsDebuggingEnabled(true)
        webView.addJavascriptInterface(AdShieldBridge(), "AdShield")

        webView.webViewClient = object : WebViewClient() {

            // PROBLEMA 3: algunos ads navegan la página entera (no usan window.open).
            // shouldOverrideUrlLoading bloquea esas navegaciones.
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // Dejar pasar la URL principal y sus mismos dominios
                if (url.contains("keiriblest.github.io") ||
                    url.contains("github.io")) return false
                // Bloquear si coincide con patrones de ads
                if (adPatterns.any { url.contains(it) }) {
                    blockedCount++
                    return true // bloqueado
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (url.startsWith("data:application/pdf")) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (adPatterns.any { url.contains(it) }) {
                    blockedCount++
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            // PROBLEMA 4: inyectar el bridge lo antes posible (onPageStarted)
            // para que esté disponible cuando los scripts de la página corran.
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectAll("started")
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectAll("finished")
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    handler.postDelayed({ view.loadUrl(APP_URL) }, 1500)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean,
                isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean = false
        }

        webView.loadUrl(APP_URL)
        handler.postDelayed(reInjectRunnable, 4000)
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
