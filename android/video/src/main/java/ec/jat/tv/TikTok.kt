package ec.jat.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * TikTok dentro de JTV Video: la página web de TikTok (versión de computadora, que se ve bien en la pantalla
 * ancha del carro) en un navegador propio. La sesión (cookies) se comparte entre el carro y la pantalla
 * "Iniciar sesión de TikTok" del celular, porque las dos están dentro de JTV Video.
 */
object TikTok {
    const val INICIO = "https://www.tiktok.com/foryou"
    const val LOGIN = "https://www.tiktok.com/login"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun crearWeb(c: Context, inicio: String = INICIO): WebView = WebView(c).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false     // que el video arranque solo
        settings.userAgentString = UA
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            // TikTok intenta abrir su app (intent://, snssdk://…): eso no existe en el carro; se queda en la web
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme ?: return true
                return s != "http" && s != "https"
            }
        }
        isFocusable = true
        isFocusableInTouchMode = true
        loadUrl(inicio)
    }

    /** Video siguiente (abajo = true) o anterior: como la flecha del teclado; si la página no se mueve, se desliza. */
    fun mover(w: WebView, abajo: Boolean) {
        w.evaluateJavascript(JS_MARCAR, null)
        w.requestFocus()
        val tecla = if (abajo) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, tecla))
        w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, tecla))
        w.postDelayed({ w.evaluateJavascript(jsRespaldo(abajo), null) }, 1200)
    }

    /** Pausa o sigue el video que se está viendo. */
    fun pausa(w: WebView) = w.evaluateJavascript(JS_PAUSA, null)

    fun guardarSesion() { try { CookieManager.getInstance().flush() } catch (e: Exception) {} }

    private const val JS_MARCAR =
        "window.__jtvV=[].slice.call(document.querySelectorAll('video')).filter(function(v){return !v.paused})[0]||null;"

    private const val JS_PAUSA = """(function(){
        var l=[].slice.call(document.querySelectorAll('video'));
        var v=l.filter(function(x){return !x.paused})[0];
        if(v){v.pause();window.__jtvP=v;return;}
        var p=window.__jtvP||l.filter(function(x){var r=x.getBoundingClientRect();return r.top<innerHeight/2&&r.bottom>innerHeight/2})[0]||l[0];
        if(p)p.play();
    })();"""

    private fun jsRespaldo(abajo: Boolean) = """(function(){
        var l=[].slice.call(document.querySelectorAll('video'));
        var a=l.filter(function(v){return !v.paused})[0]||null;
        if(a&&a!==window.__jtvV)return;
        var v=window.__jtvV||l.filter(function(x){var r=x.getBoundingClientRect();return r.top<innerHeight/2&&r.bottom>innerHeight/2})[0];
        var el=v;
        while(el&&el!==document.body){var s=getComputedStyle(el);
          if((s.overflowY=='auto'||s.overflowY=='scroll')&&el.scrollHeight>el.clientHeight)break;el=el.parentElement;}
        if(!el||el===document.body)el=document.scrollingElement;
        el.scrollBy({top:${if (abajo) 1 else -1}*innerHeight*0.9,behavior:'smooth'});
    })();"""
}
