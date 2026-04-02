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

    private val hardcodedAdHosts = listOf(
        "exoclick.com","exosrv.com","exdynsrv.com",
        "popcash.net","popads.net","popadvert.com",
        "propellerads.com","propellerclick.com",
        "trafficjunky.net","trafficfactory.biz",
        "adsterra.com","juicyads.com","hilltopads.net",
        "clickadu.com","clickaine.com","adnium.com",
        "ero-advertising.com","tsyndicate.com",
        "doubleclick.net","googlesyndication.com",
        "adservice.google.com","pagead2.googlesyndication.com"
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
                ?.replace("*","")
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun isAdUrl(url: String): Boolean {
        val l = url.lowercase()
        return adPatterns.any { l.contains(it) } || hardcodedAdHosts.any { l.contains(it) }
    }
    private fun isHomePage(url: String) = url.contains(HOME_HOST)

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

    private val lightCssJs = """
        (function(){
          if(document.__adshieldCssLight) return; document.__adshieldCssLight=true;
          var s=document.createElement('style');
          s.textContent='.voe-blocker,#voe-blocker,div[class*="voe-ad"],'+
            'div[class*="preroll"],div[class*="pre-roll"],iframe[src*="ads."],'+
            'iframe[src*="pop."],iframe[src*="track."]{display:none!important;}';
          (document.head||document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    private val fullCssJs = """
        (function(){
          if(document.__adshieldCssContent) return; document.__adshieldCssContent=true;
          var s=document.createElement('style');
          s.textContent=
            'div[style*="position: fixed"][style*="top: 0"],'+
            'div[style*="position:fixed"][style*="top:0"],'+
            'div[style*="z-index: 2147483647"],div[style*="z-index:2147483647"],'+
            'div[style*="z-index: 999999"],div[style*="z-index:999999"],'+
            '[style*="2147483647"],.voe-blocker,#voe-blocker,'+
            'div[class*="voe-ad"],div[id*="voe-ad"],'+
            'div[class*="pop-up"],div[class*="popup"],div[id*="popup"],'+
            'div[id*="overlay-ad"],div[class*="ad-overlay"],div[class*="ad-layer"],'+
            'div[class*="preroll"],div[class*="pre-roll"],div[class*="interstitial"],'+
            '.jw-overlays > div:not([class*="jw-"]),'+
            'iframe[src*="ads."],iframe[src*="pop."],iframe[src*="track."],'+
            'iframe[src*="click."],iframe[id*="ad"],iframe[class*="ad"]'+
            '{display:none!important;visibility:hidden!important;'+
            'pointer-events:none!important;opacity:0!important;'+
            'height:0!important;width:0!important;}';
          (document.head||document.documentElement).appendChild(s);
        })();
    """.trimIndent()

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

    // ── Script inyectado en iframes ───────────────────────────────────────────
    // 1. Bloquea window.open
    // 2. Bloquea redirecciones (top, parent, location, meta refresh)
    // 3. MutationObserver ligero para divs dinámicos de ads
    // 4. CSS para overlays con z-index alto
    private val iframeShieldJs = """
        (function(){
          if(window.__ifs) return; window.__ifs=true;

          // ── 1. Bloquear window.open ──────────────────────────────────────────
          window.open=function(){
            try{AdShield&&AdShield.contentBlocked();}catch(_){}
            return{closed:false,close:function(){},focus:function(){},blur:function(){},
                   location:{href:'about:blank',assign:function(){},replace:function(){}}};
          };

          // ── 2. Bloquear navegación a páginas externas ────────────────────────
          // Congelar top y parent para que el iframe no pueda redirigir el padre
          try{Object.defineProperty(window,'top',   {get:function(){return window;},configurable:true});}catch(_){}
          try{Object.defineProperty(window,'parent',{get:function(){return window;},configurable:true});}catch(_){}

          // Interceptar location.assign y location.replace
          try{
            var _loc=window.location;
            ['assign','replace'].forEach(function(m){
              var orig=_loc[m].bind(_loc);
              _loc[m]=function(u){
                if(typeof u==='string'&&(u.startsWith('http')||u.startsWith('//'))
                   &&u.indexOf(window.location.hostname)===-1)return;
                orig(u);
              };
            });
          }catch(_){}

          // Bloquear <meta http-equiv="refresh"> que ya existen o se añadan
          function removeMetaRefresh(){
            document.querySelectorAll('meta[http-equiv="refresh"],meta[http-equiv="Refresh"]')
              .forEach(function(m){m.remove();});
          }
          removeMetaRefresh();

          // Bloquear clicks en <a> que naveguen fuera
          document.addEventListener('click',function(e){
            var el=e.target;
            for(var i=0;i<8&&el&&el!==document.body;i++){
              if(el.tagName==='A'){
                var href=el.getAttribute('href')||'';
                if(href&&!href.startsWith('#')&&!href.startsWith('javascript')
                   &&(href.startsWith('http')||href.startsWith('//')||href.startsWith('www'))
                   &&href.indexOf(window.location.hostname)===-1){
                  e.preventDefault();e.stopImmediatePropagation();
                  try{AdShield&&AdShield.contentBlocked();}catch(_){}
                  return;
                }
              }
              el=el.parentElement;
            }
          },true);

          // ── 3. Eliminar divs contenedor de anuncios dinámicos ────────────────
          // Ligero: solo comprueba los nodos RECIÉN AÑADIDOS, no todos
          var SAFE_CLASSES=['player','jw','video','content','vjs','plyr','fluid'];
          function isAdNode(node){
            if(!node||node.nodeType!==1) return false;
            var tag=node.tagName.toLowerCase();
            if(['video','audio','canvas','img'].indexOf(tag)!==-1) return false;
            var s=node.getAttribute('style')||'';
            var cls=(typeof node.className==='string'?node.className:'').toLowerCase();
            var id=(node.id||'').toLowerCase();
            if(SAFE_CLASSES.some(function(c){return cls.indexOf(c)!==-1||id.indexOf(c)!==-1;})) return false;
            // Div fijo con z-index máximo que contiene iframes = contenedor de ad
            if(s.indexOf('2147483647')!==-1) return true;
            if(s.indexOf('fixed')!==-1&&node.querySelector('iframe')!==null) return true;
            // Clases/ids típicos de ads
            var adWords=['popup','pop-up','overlay','interstitial','preroll','pre-roll','ad-container'];
            if(adWords.some(function(w){return cls.indexOf(w)!==-1||id.indexOf(w)!==-1;})) return true;
            return false;
          }

          function nukeNode(node){
            try{
              node.style.cssText='display:none!important;pointer-events:none!important;';
              setTimeout(function(){try{node.remove();}catch(_){}},50);
              try{AdShield&&AdShield.contentBlocked();}catch(_){}
            }catch(_){}
          }

          try{
            new MutationObserver(function(mutations){
              for(var i=0;i<mutations.length;i++){
                var m=mutations[i];
                // Nodos nuevos añadidos
                for(var j=0;j<m.addedNodes.length;j++){
                  var node=m.addedNodes[j];
                  if(isAdNode(node)){nukeNode(node);continue;}
                  // Buscar dentro del subárbol añadido
                  if(node.querySelectorAll){
                    var kids=node.querySelectorAll('[style*="2147483647"],[class*="popup"],[class*="overlay"],[class*="interstitial"]');
                    for(var k=0;k<kids.length;k++){if(isAdNode(kids[k]))nukeNode(kids[k]);}
                  }
                }
                // Cambio de atributo style (VOE modifica el style en caliente)
                if(m.type==='attributes'&&isAdNode(m.target))nukeNode(m.target);
              }
            }).observe(document.documentElement,{
              childList:true,subtree:true,
              attributes:true,attributeFilter:['style','class']
            });
          }catch(_){}

          // Eliminación inicial de lo que ya exista
          document.querySelectorAll('[style*="2147483647"]').forEach(function(el){
            if(isAdNode(el))nukeNode(el);
          });

          // ── 4. CSS de seguridad dentro del iframe ────────────────────────────
          var s=document.createElement('style');
          s.textContent=
            '[style*="2147483647"]{display:none!important;pointer-events:none!important;}'+
            '.voe-blocker,#voe-blocker,div[class*="voe-ad"],div[id*="voe-ad"],'+
            'div[class*="popup"],div[class*="interstitial"],div[class*="preroll"]'+
            '{display:none!important;}';
          (document.head||document.documentElement).appendChild(s);

          console.log('[iframeShield] activo en:',window.location.href);
        })();
    """.trimIndent()

    private fun safeCharset(name: String): Charset = try {
        Charset.forName(name)
    } catch (e: Exception) { Charsets.UTF_8 }

    private fun injectIntoIframeResponse(url: String, reqHeaders: Map<String,String>): WebResourceResponse? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000; conn.readTimeout = 6000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", webView.settings.userAgentString)
            conn.setRequestProperty("Accept","text/html,application/xhtml+xml,*/*;q=0.8")
            reqHeaders["Referer"]?.let { conn.setRequestProperty("Referer", it) }
            if (conn.responseCode != 200) return null
            val ct      = conn.contentType ?: "text/html"
            val charset = Regex("charset=([\\w-]+)").find(ct)?.groupValues?.get(1) ?: "utf-8"
            val cs      = safeCharset(charset)
            val html    = conn.inputStream.bufferedReader(cs).readText()
            if (!html.trimStart().startsWith("<")) return null
            val shieldSafe = iframeShieldJs.replace("</script>","<\\/script>")
            val voeSafe    = (readAsset("voe-ad-cleaner.js") ?: "").replace("</script>","<\\/script>")
            val bridgeSafe = bridgeAliasJs.replace("</script>","<\\/script>")
            val inject     = "<script>$bridgeSafe\n$shieldSafe\n$voeSafe</script>"
            val modified = when {
                html.contains("<head>",ignoreCase=true) ->
                    html.replaceFirst(Regex("(?i)<head>"),"<head>$inject")
                html.contains("<body>",ignoreCase=true) ->
                    html.replaceFirst(Regex("(?i)<body>"),"<body>$inject")
                else -> "$inject$html"
            }
            WebResourceResponse("text/html", charset, ByteArrayInputStream(modified.toByteArray(cs)))
        } catch (e: Exception) { null }
    }

    private fun injectForPage(url: String) {
        if (isHomePage(url)) {
            webView.evaluateJavascript(lightCssJs, null)
        } else {
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
            val url = try { webView.url ?: "" } catch (e: Exception) { "" }
            if (!isHomePage(url)) {
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
                if (!isHomePage(url)) view.evaluateJavascript(bridgeAliasJs, null)
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url    = request.url.toString()
                val accept = request.requestHeaders["Accept"] ?: ""

                // ── Bloquear recursos de ads ───────────────────────────────────
                if (isAdUrl(url)) {
                    blockedCount++
                    return WebResourceResponse("text/plain","utf-8",
                        ByteArrayInputStream(ByteArray(0)))
                }

                // ── Bloquear favicon.ico desde iframes (tracking) ──────────────
                if (!request.isForMainFrame && url.contains("favicon.ico")) {
                    return WebResourceResponse("image/x-icon","utf-8",
                        ByteArrayInputStream(ByteArray(0)))
                }

                // ── Bloquear PDF popunder ──────────────────────────────────────
                if (url.startsWith("data:application/pdf") ||
                    url.startsWith("data:application/octet")) {
                    return WebResourceResponse("text/plain","utf-8",
                        ByteArrayInputStream(ByteArray(0)))
                }

                // ── Inyectar iframeShield en HTML de iframes de streaming ──────
                if (!request.isForMainFrame &&
                    accept.contains("text/html") &&
                    url.startsWith("https") &&
                    !isHomePage(url)) {
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
