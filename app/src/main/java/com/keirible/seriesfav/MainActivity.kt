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
    private val handler      = Handler(Looper.getMainLooper())
    private val APP_URL      = "https://keiriblest.github.io/SeriesFav/mobile.html"
    private val HOME_HOST    = "keiriblest.github.io"
    private var adPatterns: List<String> = emptyList()

    private val adBlockList = listOf(
        "exoclick.com","exosrv.com","exdynsrv.com","exospecial.com",
        "popcash.net","popads.net","popadvert.com","popunder",
        "propellerads.com","propellerclick.com","pphventures.com",
        "trafficjunky.net","trafficfactory.biz","trafficforce.com",
        "adsterra.com","adsterratech.com",
        "juicyads.com","hilltopads.net","hilltopads.com",
        "clickadu.com","clickaine.com","adnium.com","fuckingfast.co",
        "ero-advertising.com","tsyndicate.com",
        "doubleclick.net","googlesyndication.com",
        "adservice.google","pagead2.googlesyndication",
        "revcontent.com","taboola.com","outbrain.com",
        "amazon-adsystem.com","smartadserver.com",
        "openx.net","pubmatic.com","rubiconproject.com","appnexus.com",
        "criteo.com","criteo.net","bidswitch.net","sovrn.com",
        "adsrvr.org","casalemedia.com","indexexchange.com",
        "/pop.js","/popup.js","/pops.js","/ad.js","/ads.js",
        "/banner.js","/popunder.js","adframe","adserver",
        "pop-ads","pop_ads","popads","poptm"
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
                ?.let { raw ->
                    raw.removePrefix("||").removeSuffix("^")
                        .replace("*","").trim().takeIf { it.length > 3 }
                }
        }
    }

    private fun isAdUrl(url: String): Boolean {
        val l = url.lowercase()
        return adPatterns.any { l.contains(it) } || adBlockList.any { l.contains(it) }
    }
    private fun isHomePage(url: String) = url.contains(HOME_HOST)

    // ── Bridge — disponible en todos los frames via addJavascriptInterface ────
    private val bridgeAliasJs = """
        (function(){
          if(window.__adshieldBridge)return; window.__adshieldBridge=true;
          window.adshieldElectron={
            reportBlocked:function(){try{AdShield.contentBlocked();}catch(_){}},
            getCount:     function(){try{return AdShield.getCount();}catch(_){return 0;}},
            resetCount:   function(){try{return AdShield.resetCount();}catch(_){return 0;}},
            onCountUpdate:function(){}
          };
        })();
    """.trimIndent()

    // ── Touch fix: dispara mousedown sintético desde touchstart ───────────────
    // content-electron.js rastrea lastDownTarget con mousedown
    private val touchFixJs = """
        (function(){
          if(window.__adshieldTouchFix)return; window.__adshieldTouchFix=true;
          document.addEventListener('touchstart',function(e){
            try{
              var t=e.touches[0]; if(!t)return;
              // Mismo target que recibirá el click → alimenta lastDownTarget
              e.target.dispatchEvent(new MouseEvent('mousedown',
                {bubbles:true,cancelable:true,clientX:t.clientX,clientY:t.clientY}));
            }catch(_){}
          },{passive:true,capture:true});
          // También alimentar lastClickX/Y cuando termina el toque
          document.addEventListener('touchend',function(e){
            try{
              var t=e.changedTouches[0]; if(!t)return;
              e.target.dispatchEvent(new MouseEvent('click',
                {bubbles:true,cancelable:false,clientX:t.clientX,clientY:t.clientY}));
            }catch(_){}
          },{passive:true,capture:true});
        })();
    """.trimIndent()

    // ── CSS suave para página principal ──────────────────────────────────────
    private val lightCssJs = """
        (function(){
          if(document.__adshieldCssLight)return; document.__adshieldCssLight=true;
          var s=document.createElement('style');
          s.textContent='.voe-blocker,#voe-blocker,div[class*="voe-ad"],'+
            'div[class*="preroll"],iframe[src*="ads."],iframe[src*="pop."]'+
            '{display:none!important;}';
          (document.head||document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    // ── iframeShield: inyectado en iframes vía HTTP intercept ─────────────────
    // Replica lo que content-electron.js hace cuando window.self !== window.top
    private val iframeShieldJs = """
        (function(){
          if(window.__ifs)return; window.__ifs=true;

          // Bloquear window.open
          window.open=function(){
            try{AdShield&&AdShield.contentBlocked();}catch(_){}
            return{closed:false,close:function(){},focus:function(){},blur:function(){},
                   location:{href:'about:blank',assign:function(){},replace:function(){}}};
          };

          // Congelar top y parent
          try{Object.defineProperty(window,'top',{get:function(){return window;},configurable:true});}catch(_){}
          try{Object.defineProperty(window,'parent',{get:function(){return window;},configurable:true});}catch(_){}

          // Bloquear location.assign / replace externos
          try{
            ['assign','replace'].forEach(function(m){
              var orig=window.location[m].bind(window.location);
              window.location[m]=function(u){
                if(typeof u==='string'&&(u.startsWith('http')||u.startsWith('//'))
                   &&u.indexOf(window.location.hostname)===-1)return;
                orig(u);
              };
            });
          }catch(_){}

          // Bloquear meta refresh
          function killMeta(){
            document.querySelectorAll('meta[http-equiv="refresh"],meta[http-equiv="Refresh"]')
              .forEach(function(m){m.remove();});
          }
          killMeta();

          // Bloquear links externos (mismo patrón que content-electron.js)
          document.addEventListener('click',function(e){
            var a=e.target.closest?e.target.closest('a'):null;
            if(!a){var el=e.target;for(var i=0;i<6;i++){if(el&&el.tagName==='A'){a=el;break;}el=el&&el.parentElement;}}
            if(a){
              var href=a.getAttribute('href')||'';
              if(href&&!href.startsWith('#')&&!href.startsWith('javascript')
                 &&href.indexOf(window.location.hostname)===-1
                 &&(href.startsWith('http')||href.startsWith('//')||href.startsWith('www'))){
                e.preventDefault();e.stopImmediatePropagation();
                try{AdShield&&AdShield.contentBlocked();}catch(_){}
                return;
              }
            }
          },true);

          // MutationObserver: eliminar divs de ads (same logic as content-electron.js)
          var SAFE=['player','video','jw','vjs','plyr','fluid','content'];
          function isAdNode(node){
            if(!node||node.nodeType!==1)return false;
            var tag=node.tagName.toLowerCase();
            if(['video','audio','canvas','img','svg','iframe','button','input'].indexOf(tag)!==-1)return false;
            var cls=(typeof node.className==='string'?node.className:'').toLowerCase();
            var id=(node.id||'').toLowerCase();
            if(SAFE.some(function(c){return cls.indexOf(c)!==-1||id.indexOf(c)!==-1;}))return false;
            try{
              var cs=window.getComputedStyle(node);
              var pos=cs.position;
              if(pos!=='fixed'&&pos!=='absolute')return false;
              var z=parseInt(cs.zIndex,10);
              if(isNaN(z)||z<500)return false;
              var r=node.getBoundingClientRect();
              if(r.width<window.innerWidth*0.25||r.height<window.innerHeight*0.25)return false;
              return true;
            }catch(_){return false;}
          }
          function nuke(node){
            try{
              node.style.setProperty('display','none','important');
              node.style.setProperty('pointer-events','none','important');
              setTimeout(function(){try{node.remove();}catch(_){}},50);
              try{AdShield&&AdShield.contentBlocked();}catch(_){}
            }catch(_){}
          }
          try{
            new MutationObserver(function(muts){
              for(var i=0;i<muts.length;i++){
                var m=muts[i];
                for(var j=0;j<m.addedNodes.length;j++){
                  var n=m.addedNodes[j];
                  if(isAdNode(n)){nuke(n);continue;}
                  if(n.querySelectorAll){n.querySelectorAll('*').forEach(function(c){if(isAdNode(c))nuke(c);});}
                }
                if(m.type==='attributes'&&isAdNode(m.target))nuke(m.target);
              }
            }).observe(document.documentElement,{childList:true,subtree:true,
              attributes:true,attributeFilter:['style','class']});
          }catch(_){}

          // Scan inicial
          try{document.querySelectorAll('*').forEach(function(el){if(isAdNode(el))nuke(el);});}catch(_){}
          setInterval(function(){killMeta();try{document.querySelectorAll('*').forEach(function(el){if(isAdNode(el))nuke(el);});}catch(_){}},1200);

          // CSS de respaldo
          var s=document.createElement('style');
          s.textContent='[style*="2147483647"]{display:none!important;pointer-events:none!important;}'+
            '.voe-blocker,#voe-blocker,div[class*="voe-ad"],div[class*="popup"],div[class*="overlay"]:not([class*="player"]),div[class*="interstitial"],div[class*="preroll"]{display:none!important;}';
          (document.head||document.documentElement).appendChild(s);
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
            reqHeaders["Referer"]?.let        { conn.setRequestProperty("Referer", it) }
            reqHeaders["Accept-Language"]?.let{ conn.setRequestProperty("Accept-Language", it) }
            if (conn.responseCode != 200) return null
            val ct      = conn.contentType ?: "text/html"
            val charset = Regex("charset=([\\w-]+)").find(ct)?.groupValues?.get(1) ?: "utf-8"
            val cs      = safeCharset(charset)
            val html    = conn.inputStream.bufferedReader(cs).readText()
            if (!html.trimStart().startsWith("<")) return null
            val bridgeSafe = bridgeAliasJs.replace("</script>","<\\/script>")
            val shieldSafe = iframeShieldJs.replace("</script>","<\\/script>")
            val voeSafe    = (readAsset("voe-ad-cleaner.js") ?: "").replace("</script>","<\\/script>")
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

    // ── Inyección temprana: onPageCommitVisible (API 23+) ─────────────────────
    // Equivale al dom-ready de Electron → antes de que los scripts de la página
    // terminen de ejecutarse
    private fun earlyInject(url: String) {
        if (isHomePage(url)) { webView.evaluateJavascript(lightCssJs, null); return }
        webView.evaluateJavascript(bridgeAliasJs, null)
        webView.evaluateJavascript(touchFixJs, null)
        // Inyectar voe-ad-cleaner temprano (MutationObserver activo antes que los ads)
        readAsset("voe-ad-cleaner.js")?.let {
            webView.evaluateJavascript("(function(){$it})();", null)
        }
    }

    // ── Inyección completa: onPageFinished ────────────────────────────────────
    private fun fullInject(url: String) {
        if (isHomePage(url)) { webView.evaluateJavascript(lightCssJs, null); return }
        webView.evaluateJavascript(bridgeAliasJs, null)
        webView.evaluateJavascript(touchFixJs, null)
        // content-electron.js ya tiene su propio IIFE, no necesita wrapper
        readAsset("content-electron.js")?.let {
            webView.evaluateJavascript(it, null)
        }
        readAsset("voe-ad-cleaner.js")?.let {
            webView.evaluateJavascript("(function(){if(window.__adshieldVoe)return;window.__adshieldVoe=true;$it})();", null)
        }
    }

    // ── Red de seguridad cada 3s ──────────────────────────────────────────────
    private val reInjectRunnable = object : Runnable {
        override fun run() {
            val url = try { webView.url ?: "" } catch (e: Exception) { "" }
            if (!isHomePage(url)) {
                webView.evaluateJavascript(bridgeAliasJs, null)
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

            // CAPA 3a: Bridge lo antes posible
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!isHomePage(url)) view.evaluateJavascript(bridgeAliasJs, null)
            }

            // CAPA 3b: Equivalente a dom-ready de Electron (API 23+)
            // Inyecta scripts ANTES de que la página termine de cargar
            override fun onPageCommitVisible(view: WebView, url: String) {
                super.onPageCommitVisible(view, url)
                earlyInject(url)
            }

            // CAPA 1: Bloqueo de red
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url    = request.url.toString()
                val accept = request.requestHeaders["Accept"] ?: ""

                if (isAdUrl(url)) {
                    blockedCount++
                    return WebResourceResponse("text/plain","utf-8",
                        ByteArrayInputStream(ByteArray(0)))
                }
                if (!request.isForMainFrame && url.contains("favicon.ico")) {
                    return WebResourceResponse("image/x-icon","utf-8",
                        ByteArrayInputStream(ByteArray(0)))
                }
                if (url.startsWith("data:application/pdf") ||
                    url.startsWith("data:application/octet")) {
                    return WebResourceResponse("text/plain","utf-8",
                        ByteArrayInputStream(ByteArray(0)))
                }
                // CAPA 4: Inyección en iframes HTML
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

            // CAPA 5: Inyección completa al terminar la carga
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                fullInject(url)
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame)
                    handler.postDelayed({ view.loadUrl(APP_URL) }, 1500)
            }
        }

        // CAPA 2: Bloquear popups de cualquier frame
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
