package com.tuapp.seriesfav

import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val APP_URL = "https://keiriblest.github.io/SeriesFav/desktop.html"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        configurarWebView()
        webView.loadUrl(APP_URL)
    }

    private fun configurarWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(false)
        }

        webView.webViewClient = object : WebViewClient() {
            // Bloqueo de red eficiente
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                val adDomains = listOf("doubleclick.net", "exoclick.com", "popads.net", "propellerads.com")
                
                if (adDomains.any { url.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Inyectar el script de limpieza
                val js = assets.open("adshield_android.js").bufferedReader().readText()
                view.evaluateJavascript(js, null)
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack() // Regresa en la navegación del WebView
        } else {
            super.onBackPressed() // Cierra la app si no hay historial
        }
    }
}
