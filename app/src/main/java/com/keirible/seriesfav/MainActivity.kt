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
    private val APP_URL = "https://keiriblest.github.io/SeriesFav/desktop.html"

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

    private fun buildCssInjectJs(css: String) = """
        (function(){
          if(document.__adshieldCss) return; document.__adshieldCss = true;
          var s = document.createElement('style');
          s.textContent = ${org.json.JSONObject.quote("CSS_PLACEHOLDER")};
          (document.head || document.documentElement).appendChild(s);
        })();
    """.trimIndent().replace(""CSS_PLACEHOLDER"", org.json.JSONObject.quote(
        """
        div[class*="pop-up"], div[class*="popup"], div[id*="popup"],
        .voe-blocker, #voe-blocker, div[class*="voe-ad"],
        iframe[src*="ads."], iframe[src*="pop."], iframe[src*="track."],
        [style*="2147483647"] {
          display: none !important; visibility: hidden !important;
          pointer-events: none !important; opacity: 0 !important;
        }
        """.trimIndent()
    ))

    private fun inject() {
        val contentJs = readAsset("content-electron.js")
        val voeJs = readAsset("voe-ad-cleaner.js")
        val guard = "if(window.__adshieldInjected) return; window.__adshieldInjected = true;"
        val cssJs = buildCssInjectJs(antiPopupCss)

        webView.evaluateJavascript(cssJs, null)
        contentJs?.let { webView.evaluateJavascript("(function(){$guard$it})();", null) }
        voeJs?.let { webView.evaluateJavascript("(function(){$guard$it})();", null) }
    }

    private val reInjectRunnable = object : Runnable {
        override fun run() {
            val voeJs = readAsset("voe-ad-cleaner.js") ?: run {
                handler.postDelayed(this, 4000); return
            }
            webView.evaluateJavascript(
                "(function(){ if(!window.__adshieldVoeActive){ window.__adshieldVoeActive=true; $voeJs } })()", null
            )
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
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        WebView.setWebContentsDebuggingEnabled(true)
        webView.addJavascriptInterface(AdShieldBridge(), "AdShield")

        webView.webViewClient = object : WebViewClient() {
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

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                inject()
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
