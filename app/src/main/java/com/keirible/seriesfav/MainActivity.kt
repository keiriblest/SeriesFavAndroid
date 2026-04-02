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
    private var blockedCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val APP_URL  = "https://keiriblest.github.io/SeriesFav/mobile.html"
    // Dominio principal — aquí NO se inyectan scripts agresivos
    private val HOME_HOST = "keiriblest.github.io"
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

    private fun isAdUrl(url: String)  = adPatterns.any { url.contains(it) }
    private fun isHomePage(url: String) = url.contains(HOME_HOST)

    // ── Scripts ────────────────────────────────────────────────────────────────

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

    // Touch fix: SOLO en páginas de streaming, nunca en la página principal
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

    // CSS: versión suave para la página principal (solo oculta elementos claramente ads)
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

    // CSS completo: solo en páginas de streaming
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

    // iframeShield: SOLO se inyecta dentro de iframes, nunca en el frame principal
    private val iframeShieldJs = """
        (function(){
          'use strict';
          if(window.__iframeShield) return; window.__iframeShield=true;

          // Bloquear window.open
          window.open=function(url){
            return{closed:false,close:function(){},focus:function(){},blur:function(){},
                   postMessage:function(){},location:{href:'about:blank',
                   assign:function(){},replace:function(){}}};
          };

          // Evitar que el iframe navegue el frame padre
          try{
            Object.defineProperty(window,'top',   {get:function(){return window;},configurable:true});
            Object.defineProperty(window,'parent',{get:function(){return window;},configurable:true});
          }catch(_){}

          // Bloquear location.assign/replace hacia dominios externos
          try{
            var _loc=window.location;
            ['assign','replace'].forEach(function(m){
              var orig=_loc[m].bind(_loc);
              _loc[m]=function(url){
                if(typeof url==='string'&&(url.startsWith('http')||url.startsWith('//'))
                   &&url.indexOf(window.location.hostname)===-1){return;}
                orig(url);
              };
            });
          }catch(_){}

          // Bloquear links <a> externos
          document.addEventListener('click',function(e){
            var el=e.target;
            for(var i=0;i<6&&el&&el!==document.body;i++){
              if(el.tagName==='A'){
                var href=el.getAttribute('href')||'';
                if(href&&!href.startsWith('#')&&!href.startsWith('javascript')
                   &&href.indexOf(window.location.hostname)===-1
                   &&(href.startsWith('http')||href.startsWith('//'))){
                  e.preventDefault();e.stopImmediatePropagation();return;
                }
              }
              el=el.parentElement;
            }
          },true);

          // Eliminar overlays de ads dentro del iframe
          var SAFE=['player','jw','video','content','container','wrapper'];
          function nukeOverlays(){
            try{
              document.querySelectorAll('[style*="2147483647"],[style*="position:fixed"],[style*="position: fixed"]')
              .forEach(function(el){
                var tag=el.tagName.toLowerCase();
                if(['video','audio','canvas','iframe'].indexOf(tag)!==-1) return;
                var cls=(typeof el.className==='string'?el.className:'').toLowerCase();
                var id=(el.id||'').toLowerCase();
                if(SAFE.some(function(w){return cls.indexOf(w)!==-1||id.indexOf(w)!==-1;})) return;
                el.style.cssText+=';display:none!important;pointer-events:none!important;';
              });
            }catch(_){}
          }

          try{
            new MutationObserver(function(muts){
              muts.forEach(function(m){
                m.addedNodes.forEach(function(n){if(n.nodeType===1)nukeOverlays();});
                if(m.type==='attributes')nukeOverlays();
              });
            }).observe(document.documentElement,
              {childList:true,subtree:true,attributes:true,attributeFilter:['style','class']});
          }catch(_){}

          setInterval(nukeOverlays,800);
          if(document.readyState==='loading'){
            document.addEventListener('DOMContentLoaded',nukeOverlays);
          }else{nukeOverlays();}
        })();
    """.trimIndent()

    private fun buildIframeInjection(): String {
        val voe        = readAsset("voe-ad-cleaner.js") ?: ""
        val shieldSafe = iframeShieldJs.replace("</script>","<\\/script>")
        val bridgeSafe = bridgeAliasJs.replace("</script>","<\\/script>")
        val voeSafe    = voe.replace("</script>","<\\/script>")
        return "<script>$bridgeSafe\n$shieldSafe\n$voeSafe</script>"
    }

    private fun safeCharset(name: String): Charset = try {
        Charset.forName(name)
    } catch (e: Exception) { Charsets.UTF_8 }

    private fun injectIntoIframeResponse(url: String, reqHeaders: Map<String, String>): WebResourceResponse? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            reqHeaders.forEach { (k,v) ->
                if (!k.equals("Range",ignoreCase=true)) conn.setRequestProperty(k,v)
            }
            if (conn.responseCode != 200) return null
            val ct      = conn.contentType ?: "text/html"
            val charset = Regex("charset=([\\w-]+)").find(ct)?.groupValues?.get(1) ?: "utf-8"
            val cs      = safeCharset(charset)
            var html    = conn.inputStream.bufferedReader(cs).readText()
            val inject  = buildIframeInjection()
            html = when {
                html.contains("<head>",ignoreCase=true) ->
                    html.replaceFirst(Regex("(?i)<head>"),"<head>$inject")
                html.contains("<html>",ignoreCase=true) ->
                    html.replaceFirst(Regex("(?i)<html>"),"<html>$inject")
                else -> "$inject$html"
            }
            WebResourceResponse("text/html", charset, ByteArrayInputStream(html.toByteArray(cs)))
        } catch (e: Exception) { null }
    }

    // Inyección según si estamos en la página principal o en un sitio de streaming
    private fun injectForPage(url: String) {
        if (isHomePage(url)) {
            // Página principal: SOLO CSS suave, sin tocar window.open ni eventos
            webView.evaluateJavascript(lightCssJs, null)
        } else {
            // Sitio de streaming: todo el arsenal
            webView.evaluateJavascript(bridgeAliasJs, null)
            webView.evaluateJavascript(touchFixJs, null)
            webView.evaluateJavascript(fullCssJs, null)
            readAsset("content-electron.js")?.let {
                webView.evaluateJavascript(
                    "(function(){if(window.__adshieldContent)return;window.__adshieldContent=true;$it})();",null)
            }
            readAsset("voe-ad-cleaner.js")?.let {
                webView.evaluateJavascript(
                    "(function(){if(window.__adshieldVoe)return;window.__adshieldVoe=true;$it})();",null)
            }
        }
    }

    private val reInjectRunnable = object : Runnable {
        override fun run() {
            val currentUrl = try { webView.url ?: "" } catch (e: Exception) { "" }
            if (!isHomePage(currentUrl)) {
                webView.evaluateJavascript(bridgeAliasJs, null)
                webView.evaluateJavascript(fullCssJs, null)
                readAsset("voe-ad-cleaner.js")?.let {
                    webView.evaluateJavascript(
                        "(function(){if(!window.__adshieldVoeR){window.__adshieldVoeR=true;$it}})()",null)
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

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Bridge solo en páginas de streaming (no en la principal)
                if (!isHomePage(url)) view.evaluateJavascript(bridgeAliasJs, null)
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url    = request.url.toString()
                val accept = request.requestHeaders["Accept"] ?: ""

                if (isAdUrl(url)) {
                    blockedCount++
                    return WebResourceResponse("text/plain","utf-8",ByteArrayInputStream(ByteArray(0)))
                }
                if (url.startsWith("data:application/pdf")||
                    url.startsWith("data:application/octet")) {
                    return WebResourceResponse("text/plain","utf-8",ByteArrayInputStream(ByteArray(0)))
                }
                // Inyectar iframeShield solo en iframes de sitios de streaming
                if (!request.isForMainFrame &&
                    accept.contains("text/html") &&
                    url.startsWith("http") &&
                    !isAdUrl(url)) {
                    val injected = injectIntoIframeResponse(url, request.requestHeaders)
                    if (injected != null) return injected
                }
                return super.shouldInterceptRequest(view, request)
            }

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
