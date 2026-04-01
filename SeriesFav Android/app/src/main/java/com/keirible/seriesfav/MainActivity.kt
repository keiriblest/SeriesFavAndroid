package com.tuapp.seriesfav

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var blockedCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val APP_URL = "https://keiriblest.github.io/SeriesFav/desktop.html"

    // ── Leer assets locales (equivalente a fs.readFileSync) ──────────────────
    private fun readAsset(name: String): String? = try {
        assets.open("adshield/$name").bufferedReader().readText()
    } catch (e: Exception) { null }

    // ── Cargar adPatterns desde rules.json ───────────────────────────────────
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

    private val antiPopupCss = """
        div[class*="pop-up"], div[class*="popup"], div[id*="popup"],
        div[class*="overlay"][style*="z-index"],
        div[class*="ad-layer"], div[class*="ad-overlay"],
        div[class*="interstitial"], div[class*="advertisement"],
        .voe-blocker, #voe-blocker, div[class*="voe-ad"],
        iframe[src*="ads."], iframe[src*="pop."], iframe[src*="track."],
        [style*="2147483647"] {
          display: none !important; visibility: hidden !important;
          pointer-events: none !important; opacity: 0 !important;
          height: 0 !important; width: 0 !important;
        }
    """.trimIndent()

    // ── JS que inyecta el CSS (equivalente a INJECT_CSS_JS) ─────────────────
    private fun buildCssInjectJs(css: String) = """
        (function(){
          if(document.__adshieldCss) return; document.__adshieldCss = true;
          var s = document.createElement('style');
          s.textContent = ${org.json.JSONObject.quote(css)};
          (document.head || document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    // ── Inyectar CSS + scripts en el WebView ─────────────────────────────────
    private fun inject() {
        val contentJs   = readAsset("content-electron.js")
        val voeJs       = readAsset("voe-ad-cleaner.js")
        val guard       = "if(window.__adshieldInjected) return; window.__adshieldInjected = true;"
        val cssJs       = buildCssInjectJs(antiPopupCss)

        webView.evaluateJavascript(cssJs, null)
        contentJs?.let {
            webView.evaluateJavascript("(function(){$guard$it})();", null)
        }
        voeJs?.let {
            webView.evaluateJavascript("(function(){$guard$it})();", null)
        }
    }

    // ── Runnable de re-inyección cada 4 s (equivalente a setInterval) ────────
    private val reInjectRunnable = object : Runnable {
        override fun run() {
            val voeJs = readAsset("voe-ad-cleaner.js") ?: return
            webView.evaluateJavascript(
                "(function(){ if(!window.__adshieldVoeActive){ window.__adshieldVoeActive=true; $voeJs } })()", null
            )
            handler.postDelayed(this, 4000)
        }
    }

    // ── Bridge IPC → equivalente a ipcMain ───────────────────────────────────
    inner class AdShieldBridge {
        @JavascriptInterface
        fun getCount(): Int = blockedCount

        @JavascriptInterface
        fun resetCount(): Int { blockedCount = 0; return 0 }

        @JavascriptInterface
        fun contentBlocked() {
            blockedCount++
            // Notificar al renderer si lo necesitas
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled      = true
            domStorageEnabled      = true
            allowFileAccess        = false
            mixedContentMode       = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        // Debug (equivalente a F12/DevTools)
        WebView.setWebContentsDebuggingEnabled(true)

        // Bridge IPC
        webView.addJavascriptInterface(AdShieldBridge(), "AdShield")

        // ── CAPA 2+3: Bloqueo de URLs + inyección al cargar ──────────────────
        webView.webViewClient = object : WebViewClient() {

            // Equivalente a setWindowOpenHandler + adPatterns URL blocking
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()

                // Bloquear data: frames (equivalente al bloqueo de PDF popunder)
                if (url.startsWith("data:application/pdf") ||
                    url.startsWith("data:application/octet")) {
                    return WebResourceResponse("text/plain","utf-8",
                        ByteArrayInputStream(ByteArray(0)))
                }

                // Bloquear URLs de ads según adPatterns
                if (adPatterns.any { url.contains(it) }) {
                    blockedCount++
                    return WebResourceResponse("text/plain","utf-8",
                        ByteArrayInputStream(ByteArray(0)))   // [web:36]
                }
                return super.shouldInterceptRequest(view, request)
            }

            // Equivalente a dom-ready
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                inject()
            }

            // Equivalente a did-fail-load
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    handler.postDelayed({ view.loadUrl(APP_URL) }, 1500)
                }
            }
        }

        // ── CAPA 2: Bloquear window.open / popups ────────────────────────────
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean,
                isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean = false  // deny all — equivalente a setWindowOpenHandler
        }

        webView.loadUrl(APP_URL)
        handler.postDelayed(reInjectRunnable, 4000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(reInjectRunnable)
    }

    // Botón atrás navega historial (como en un browser)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}