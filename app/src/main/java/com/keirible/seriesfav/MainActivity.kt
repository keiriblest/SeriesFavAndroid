package com.keirible.seriesfav

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.keirible.seriesfav.R
import org.json.JSONArray
import org.json.JSONObject
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
        "adsterra.com","adsterratech.com","juicyads.com",
        "hilltopads.net","hilltopads.com","clickadu.com",
        "clickaine.com","adnium.com","fuckingfast.co",
        "ero-advertising.com","tsyndicate.com",
        "doubleclick.net","googlesyndication.com",
        "adservice.google","pagead2.googlesyndication",
        "revcontent.com","taboola.com","outbrain.com",
        "amazon-adsystem.com","smartadserver.com","openx.net",
        "pubmatic.com","rubiconproject.com","appnexus.com",
        "criteo.com","criteo.net","bidswitch.net","sovrn.com",
        "adsrvr.org","casalemedia.com","indexexchange.com",
        "/pop.js","/popup.js","/pops.js","/ad.js","/ads.js",
        "/banner.js","/popunder.js","adframe","adserver",
        "pop-ads","pop_ads","popads","poptm"
    )

    // Palabras clave de URLs que son redirecciones de ad
    private val adRedirectKeywords = listOf(
        "click.","track.","goto.","redirect.","exit.","refer.",
        "aff.","redir.","go.php","click.php","out.php",
        "bounce","adclick","adredirect","adtarget"
    )

    private fun readAsset(name: String): String? = try {
        assets.open("adshield/$name").bufferedReader().readText()
    } catch (e: Exception) { null }

    private fun loadAdPatterns() {
        val json = readAsset("rules.json") ?: return
        val arr  = JSONArray(json)
        adPatterns = (0 until arr.length()).mapNotNull { i ->
            arr.getJSONObject(i).optJSONObject("condition")
                ?.optString("urlFilter")
                ?.let { r -> r.removePrefix("||").removeSuffix("^")
                    .replace("*","").trim().takeIf { it.length > 3 } }
        }
    }

    private fun isAdUrl(url: String): Boolean {
        val l = url.lowercase()
        return adPatterns.any { l.contains(it) } || adBlockList.any { l.contains(it) }
    }
    private fun isHomePage(url: String) = url.contains(HOME_HOST)

    // ── Bridge ────────────────────────────────────────────────────────────────
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

    // ── Touch fix: alimenta lastDownTarget y lastClickX/Y de content-electron ─
    private val touchFixJs = """
        (function(){
          if(window.__adshieldTouchFix)return; window.__adshieldTouchFix=true;
          document.addEventListener('touchstart',function(e){
            try{
              var t=e.touches[0]; if(!t)return;
              e.target.dispatchEvent(new MouseEvent('mousedown',
                {bubbles:true,cancelable:true,clientX:t.clientX,clientY:t.clientY}));
            }catch(_){}
          },{passive:true,capture:true});
          document.addEventListener('touchend',function(e){
            try{
              var t=e.changedTouches[0]; if(!t)return;
              e.target.dispatchEvent(new MouseEvent('click',
                {bubbles:true,cancelable:false,clientX:t.clientX,clientY:t.clientY}));
            }catch(_){}
          },{passive:true,capture:true});
        })();
    """.trimIndent()

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

    // ── CASO 2+3: iframeShield — elimina divs dinámicos + bloquea redirects ───
    private val iframeShieldJs = """
        (function(){
          if(window.__ifs)return; window.__ifs=true;

          // CASO 3a: Bloquear window.open dentro del iframe
          var _lastTarget=null;
          document.addEventListener('touchstart',function(e){_lastTarget=e.target;},{passive:true,capture:true});
          document.addEventListener('mousedown',function(e){_lastTarget=e.target;},true);

          window.open=function(url){
            // Eliminar el div interceptor que disparó este window.open
            var el=_lastTarget;
            for(var i=0;i<8&&el&&el!==document.body;i++){
              try{
                var cs=getComputedStyle(el);
                var z=parseInt(cs.zIndex,10);
                if(!isNaN(z)&&z>=500&&(cs.position==='fixed'||cs.position==='absolute')){
                  el.style.setProperty('display','none','important');
                  el.style.setProperty('pointer-events','none','important');
                  setTimeout(function(e){return function(){try{e.remove();}catch(_){}};}(el),30);
                  break;
                }
              }catch(_){}
              el=el.parentElement;
            }
            try{AdShield&&AdShield.contentBlocked();}catch(_){}
            return{closed:false,close:function(){},focus:function(){},blur:function(){},
                   location:{href:'about:blank',assign:function(){},replace:function(){}}};
          };

          // CASO 3b: Evitar que el iframe redirecte al frame padre
          try{Object.defineProperty(window,'top',{get:function(){return window;},configurable:true});}catch(_){}
          try{Object.defineProperty(window,'parent',{get:function(){return window;},configurable:true});}catch(_){}

          // CASO 3c: Bloquear location.assign/replace hacia dominios externos
          try{
            var _host=window.location.hostname;
            ['assign','replace'].forEach(function(m){
              var orig=window.location[m].bind(window.location);
              window.location[m]=function(u){
                if(typeof u==='string'&&(u.startsWith('http')||u.startsWith('//'))&&u.indexOf(_host)===-1)return;
                orig(u);
              };
            });
          }catch(_){}

          // Eliminar meta refresh
          function killMeta(){
            document.querySelectorAll('meta[http-equiv="refresh"],meta[http-equiv="Refresh"]')
              .forEach(function(m){m.remove();});
          }
          killMeta();

          // Bloquear links externos (<a href>)
          document.addEventListener('click',function(e){
            var el=e.target;
            for(var i=0;i<8&&el&&el!==document.body;i++){
              if(el.tagName==='A'){
                var href=el.getAttribute('href')||'';
                if(href&&!href.startsWith('#')&&!href.startsWith('javascript')
                   &&href.indexOf(window.location.hostname)===-1
                   &&(href.startsWith('http')||href.startsWith('//'))){
                  e.preventDefault();e.stopImmediatePropagation();
                  try{AdShield&&AdShield.contentBlocked();}catch(_){}return;
                }
              }
              el=el.parentElement;
            }
          },true);

          // CASO 1+2: MutationObserver — eliminar divs de ads dinámicos
          var SAFE=['player','video','jw','vjs','plyr','fluid','content','wrapper'];
          function isAdEl(el){
            if(!el||el.nodeType!==1)return false;
            var tag=el.tagName.toLowerCase();
            if(['video','audio','canvas','img','svg','iframe','button','input'].indexOf(tag)!==-1)return false;
            var cls=(typeof el.className==='string'?el.className:'').toLowerCase();
            var id=(el.id||'').toLowerCase();
            if(SAFE.some(function(c){return cls.indexOf(c)!==-1||id.indexOf(c)!==-1;}))return false;
            try{
              var cs=getComputedStyle(el);
              var z=parseInt(cs.zIndex,10);
              if(isNaN(z)||z<500)return false;
              if(cs.position!=='fixed'&&cs.position!=='absolute')return false;
              var r=el.getBoundingClientRect();
              if(r.width<window.innerWidth*0.2||r.height<window.innerHeight*0.2)return false;
              return true;
            }catch(_){return false;}
          }
          function nuke(el){
            try{
              el.style.setProperty('display','none','important');
              el.style.setProperty('pointer-events','none','important');
              setTimeout(function(e){return function(){try{e.remove();}catch(_){};};}(el),30);
              try{AdShield&&AdShield.contentBlocked();}catch(_){}
            }catch(_){}
          }
          try{
            new MutationObserver(function(muts){
              for(var i=0;i<muts.length;i++){
                var m=muts[i];
                for(var j=0;j<m.addedNodes.length;j++){
                  var n=m.addedNodes[j];
                  if(isAdEl(n)){nuke(n);continue;}
                  if(n.querySelectorAll)n.querySelectorAll('*').forEach(function(c){if(isAdEl(c))nuke(c);});
                }
                if(m.type==='attributes'&&isAdEl(m.target))nuke(m.target);
              }
            }).observe(document.documentElement,{childList:true,subtree:true,
              attributes:true,attributeFilter:['style','class']});
          }catch(_){}
          setInterval(function(){
            killMeta();
            try{document.querySelectorAll('*').forEach(function(el){if(isAdEl(el))nuke(el);});}catch(_){}
          },800);
          try{document.querySelectorAll('*').forEach(function(el){if(isAdEl(el))nuke(el);});}catch(_){}

          // CSS de respaldo
          var s=document.createElement('style');
          s.textContent='[style*="2147483647"]{display:none!important;pointer-events:none!important;}'+
            '.voe-blocker,#voe-blocker,div[class*="voe-ad"],div[class*="popup"]:not([class*="player"]),'+
            'div[class*="overlay"]:not([class*="player"]):not([class*="video"]),'+
            'div[class*="interstitial"],div[class*="preroll"]{display:none!important;}';
          (document.head||document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════════════════════
    // FRAME INJECTOR — equivalente a frame-created de Electron
    // Inyecta en iframes del MISMO ORIGEN vía contentDocument (acceso directo)
    // Para iframes cross-origin: el try/catch falla silenciosamente
    // Incluye content-electron.js + iframeShield + voe-ad-cleaner
    // ═══════════════════════════════════════════════════════════════════════════
    private fun buildFrameInjectorJs(contentJs: String, voeJs: String): String {
        val fullPayload = "$bridgeAliasJs\n$touchFixJs\n$iframeShieldJs\n$voeJs\n$contentJs"
        val payloadJson = JSONObject.quote(fullPayload)
        return """
        (function(){
          if(window.__frameInjector)return; window.__frameInjector=true;
          var P=$payloadJson;
          function injectFrame(iframe){
            try{
              var win=iframe.contentWindow;
              var doc=win&&(iframe.contentDocument||win.document);
              if(!doc||win.__ifs)return;
              win.__ifs=true;
              var s=doc.createElement('script');
              s.textContent=P;
              (doc.head||doc.documentElement).appendChild(s);
            }catch(_){} // cross-origin SecurityError: ignorado
          }
          function injectAll(){
            try{document.querySelectorAll('iframe').forEach(function(f){injectFrame(f);});}catch(_){}
          }
          injectAll();
          setInterval(injectAll,800);
          try{
            new MutationObserver(function(muts){
              muts.forEach(function(m){
                m.addedNodes.forEach(function(n){
                  if(!n||n.nodeType!==1)return;
                  if(n.tagName==='IFRAME')injectFrame(n);
                  else if(n.querySelectorAll)n.querySelectorAll('iframe').forEach(injectFrame);
                });
              });
            }).observe(document.documentElement,{childList:true,subtree:true});
          }catch(_){}
        })();
        """.trimIndent()
    }

    private fun safeCharset(name: String): Charset = try {
        Charset.forName(name)
    } catch (e: Exception) { Charsets.UTF_8 }

    // HTTP injection para iframes cross-origin
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
            val ct = conn.contentType ?: "text/html"
            if (!ct.contains("html")) return null
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

    private fun earlyInject(url: String) {
        if (isHomePage(url)) { webView.evaluateJavascript(lightCssJs, null); return }
        webView.evaluateJavascript(bridgeAliasJs, null)
        webView.evaluateJavascript(touchFixJs, null)
        readAsset("voe-ad-cleaner.js")?.let {
            webView.evaluateJavascript("(function(){$it})();", null)
        }
    }

    private fun fullInject(url: String) {
        if (isHomePage(url)) { webView.evaluateJavascript(lightCssJs, null); return }
        val contentJs = readAsset("content-electron.js") ?: return
        val voeJs     = readAsset("voe-ad-cleaner.js") ?: ""
        webView.evaluateJavascript(bridgeAliasJs, null)
        webView.evaluateJavascript(touchFixJs, null)
        // content-electron.js directo (ya tiene su propio IIFE)
        webView.evaluateJavascript(contentJs, null)
        // voe-ad-cleaner con guard
        webView.evaluateJavascript(
            "(function(){if(window.__adshieldVoe)return;window.__adshieldVoe=true;$voeJs})();",null)
        // Frame injector: replica frame-created de Electron para iframes same-origin
        webView.evaluateJavascript(buildFrameInjectorJs(contentJs, voeJs), null)
    }

    private val reInjectRunnable = object : Runnable {
        override fun run() {
            val url = try { webView.url ?: "" } catch (e: Exception) { "" }
            if (!isHomePage(url)) {
                webView.evaluateJavascript(bridgeAliasJs, null)
                val voe = readAsset("voe-ad-cleaner.js")
                voe?.let {
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

            override fun onPageCommitVisible(view: WebView, url: String) {
                super.onPageCommitVisible(view, url)
                earlyInject(url)
            }

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
                // HTTP injection para iframes HTML
                if (!request.isForMainFrame &&
                    (accept.contains("text/html") || accept.contains("*/*") || accept.isEmpty()) &&
                    url.startsWith("https") && !isHomePage(url)) {
                    val injected = injectIntoIframeResponse(url, request.requestHeaders)
                    if (injected != null) return injected
                }
                return super.shouldInterceptRequest(view, request)
            }

            // CASO 3: Bloquear redirecciones que parecen ads
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url         = request.url.toString()
                val currentUrl  = view.url ?: ""
                val currentHost = try { Uri.parse(currentUrl).host ?: "" } catch (e: Exception) { "" }
                val targetHost  = request.url.host ?: ""

                if (isAdUrl(url)) { blockedCount++; return true }

                // Si estamos en un sitio de streaming y la navegación va a
                // un dominio completamente distinto con patrón de redirect de ad → bloquear
                if (!isHomePage(currentUrl) && currentHost.isNotEmpty() &&
                    targetHost.isNotEmpty() && currentHost != targetHost &&
                    !targetHost.contains(currentHost.takeLast(15)) &&
                    !currentHost.contains(targetHost.takeLast(15))) {
                    val l = url.lowercase()
                    if (adRedirectKeywords.any { l.contains(it) }) {
                        blockedCount++; return true
                    }
                }
                return false
            }

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
