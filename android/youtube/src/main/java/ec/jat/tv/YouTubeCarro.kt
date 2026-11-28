package ec.jat.tv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import ec.jat.tv.youtube.R

/**
 * JTV YouTube: YouTube (la versión para celular, m.youtube.com) en la pantalla del carro.
 *
 * Igual que JTV Video: Android Auto le presta a las apps de "navegación" una pantalla libre y aquí se dibuja
 * un navegador con YouTube. La sesión se inicia desde el celular (JTV → ⋮ → Iniciar sesión de YouTube).
 *
 * Seguridad: si en JTV está apagado el "modo pasajero", con el GPS se tapa la imagen mientras el carro avanza
 * (el sonido sigue). La opción se cambia desde JTV Video, igual para las dos apps.
 */
class YouTubeServicio : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCreateSession(): Session = SesionYouTube()
}

class SesionYouTube : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val yt = YouTubeCarro(carContext)
        YouTubeWeb.anotar(carContext, "▶ JTV YouTube abierto en el carro")
        yt.arrancar()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = yt.liberar()
        })
        return PantallaYouTube(carContext, yt)
    }
}

/** Navegador y ajustes de YouTube (los usa el carro y la pantalla de inicio de sesión del celular). */
object YouTubeWeb {
    const val INICIO = "https://m.youtube.com/"
    const val LOGIN = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F"

    /**
     * enCarro = true: en el carro no hay teclado del celular, así que (1) al tocar un cuadro de texto se abre el
     * teclado del carro (alEscribir) y (2) la página de inicio de sesión de Google no se abre (alLogin): la sesión
     * se inicia desde el celular.
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun crear(c: Context, inicio: String, alPantallaCompleta: ((View?, WebChromeClient.CustomViewCallback?) -> Unit)? = null,
              enCarro: Boolean = false, alEscribir: ((String, Boolean) -> Unit)? = null, alLogin: (() -> Unit)? = null): WebView = WebView(c).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webChromeClient = object : WebChromeClient() {
            // botón de pantalla completa del video de YouTube
            override fun onShowCustomView(view: View, callback: CustomViewCallback) { alPantallaCompleta?.invoke(view, callback) }
            override fun onHideCustomView() { alPantallaCompleta?.invoke(null, null) }
        }
        webViewClient = object : WebViewClient() {
            // YouTube intenta abrir su app (intent://, vnd.youtube://): en el carro se queda en la web
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme ?: return true
                if (s != "http" && s != "https") return true
                if (enCarro && (r.url.host ?: "").contains("accounts.google.")) { alLogin?.invoke(); return true }
                return false
            }
            override fun onPageFinished(v: WebView, url: String?) {
                if (enCarro) v.evaluateJavascript(JS_TECLADO, null)
                if (enCarro && (url ?: "").contains("accounts.google.")) { alLogin?.invoke(); v.loadUrl(INICIO) }
            }
        }
        isFocusable = true
        isFocusableInTouchMode = true
        if (enCarro && alEscribir != null) addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun escribir(tipo: String, pista: String) { Handler(Looper.getMainLooper()).post { alEscribir(pista, tipo == "password") } }
        }, "JTVTeclado")
        loadUrl(inicio)
    }

    /** Avisa cuando se toca un cuadro de texto de la página (para abrir el teclado del carro). */
    private const val JS_TECLADO = """(function(){
        if(window.__jtvTeclado)return; window.__jtvTeclado=1;
        document.addEventListener('focusin',function(e){
            var t=e.target; if(!t)return;
            var ok=t.tagName=='TEXTAREA'||t.isContentEditable||(t.tagName=='INPUT'&&['text','search','email','tel','url','password','number',''].indexOf((t.type||'').toLowerCase())>=0);
            if(!ok)return; window.__jtvCampo=t;
            JTVTeclado.escribir((t.type||'text').toLowerCase(), t.placeholder||t.getAttribute('aria-label')||'');
        },true);
    })();"""

    /** Pone el texto escrito en el teclado del carro dentro del cuadro de la página (y busca si es un buscador). */
    fun jsPoner(texto: String): String {
        val t = org.json.JSONObject.quote(texto)
        return """(function(){
            var c=window.__jtvCampo||document.activeElement; if(!c)return;
            c.focus();
            var p=c.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
            var d=Object.getOwnPropertyDescriptor(p,'value');
            if(c.isContentEditable)c.textContent=$t; else if(d&&d.set)d.set.call(c,$t); else c.value=$t;
            c.dispatchEvent(new Event('input',{bubbles:true})); c.dispatchEvent(new Event('change',{bubbles:true}));
            if((c.type||'')=='search'||c.getAttribute('role')=='combobox'||/search|buscar/i.test(c.name||c.className||'')){
                var f=c.form; if(f){ if(f.requestSubmit)f.requestSubmit(); else f.submit(); }
                else ['keydown','keypress','keyup'].forEach(function(k){c.dispatchEvent(new KeyboardEvent(k,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));});
            }
        })();"""
    }

    fun guardarSesion() { try { CookieManager.getInstance().flush() } catch (e: Exception) {} }

    /** Pausa o sigue el video que se está viendo. */
    const val JS_PAUSA = """(function(){
        var l=[].slice.call(document.querySelectorAll('video'));
        var v=l.filter(function(x){return !x.paused})[0];
        if(v){v.pause();window.__jtvP=v;return;}
        var p=window.__jtvP||l[0]; if(p)p.play();
    })();"""

    // ---- datos que viven en JTV (modo pasajero) y diagnóstico, por el mismo puente que usa JTV Video
    private val URI: Uri = Uri.parse("content://ec.jat.tv.datos")
    private fun llamar(c: Context, metodo: String, arg: String? = null): Bundle? =
        try { c.contentResolver.call(URI, metodo, arg, null) } catch (e: Exception) { null }
    fun modoPasajero(c: Context): Boolean = llamar(c, "estado")?.getBoolean("modoPasajero", true) ?: true
    fun anotar(c: Context, t: String) { llamar(c, "anotar", t) }
}

class YouTubeCarro(val ctx: CarContext) : SurfaceCallback {
    private val main = Handler(Looper.getMainLooper())
    private var vd: VirtualDisplay? = null
    private var pres: Presentation? = null
    private var raiz: FrameLayout? = null
    private var web: WebView? = null
    private var completa: View? = null
    private var tapa: TextView? = null
    private var urlActual = YouTubeWeb.INICIO
    var alCambiar: (() -> Unit)? = null

    fun arrancar() {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(this)
        iniciarGps()
    }

    fun liberar() {
        guardarUrl()
        YouTubeWeb.guardarSesion()
        try { ctx.getSystemService(LocationManager::class.java).removeUpdates(gps) } catch (e: Exception) {}
        liberarPantalla()
        main.removeCallbacksAndMessages(null)
    }

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ pantalla del carro
    override fun onSurfaceAvailable(sc: SurfaceContainer) {
        val s: Surface = sc.surface ?: return
        liberarPantalla()
        try {
            val dm = ctx.getSystemService(DisplayManager::class.java)
            val d = dm.createVirtualDisplay("JTV YouTube", sc.width, sc.height, sc.dpi, s, 0)
            vd = d
            val p = Presentation(ctx, d.display, R.style.Jtv_Pantalla)
            p.window?.setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            p.setContentView(armarVista(p.context))
            p.show()
            pres = p
            aplicarMovimiento()
        } catch (e: Exception) {
            YouTubeWeb.anotar(ctx, "   ⚠ JTV YouTube no pudo armar la pantalla: ${e.javaClass.simpleName}")
        }
    }

    override fun onSurfaceDestroyed(sc: SurfaceContainer) {
        guardarUrl()
        liberarPantalla()
    }

    private fun guardarUrl() { web?.url?.let { if (it.startsWith("http")) urlActual = it } }

    private fun armarVista(c: Context): View {
        val r = FrameLayout(c).apply { setBackgroundColor(Color.BLACK) }
        val w = YouTubeWeb.crear(c, urlActual, { v, cb -> pantallaCompleta(v, cb) }, enCarro = true,
            alEscribir = { pista, clave -> abrirTeclado(pista, clave) }, alLogin = { avisoLogin() })
        r.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val t = TextView(c).apply {
            text = "🚗 Imagen en pausa mientras el carro avanza.\nEl sonido sigue. Al detenerte vuelve la imagen."
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
            typeface = Jtv.titulo(c)
            background = Jtv.Fondo()
            visibility = View.GONE
        }
        r.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        raiz = r; web = w; tapa = t
        return r
    }

    /** Pantalla completa del video de YouTube (encima de la página). */
    private var salirCompleta: WebChromeClient.CustomViewCallback? = null

    private fun pantallaCompleta(v: View?, cb: WebChromeClient.CustomViewCallback?) {
        val r = raiz ?: return
        completa?.let { r.removeView(it) }
        completa = v
        salirCompleta = cb
        if (v != null) r.addView(v, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun liberarPantalla() {
        YouTubeWeb.guardarSesion()
        try { web?.stopLoading(); web?.destroy() } catch (e: Exception) {}
        try { pres?.dismiss() } catch (e: Exception) {}
        try { vd?.release() } catch (e: Exception) {}
        web = null; raiz = null; completa = null; tapa = null; pres = null; vd = null
    }

    // ------------------------------------------------------------------ botones del carro
    fun atras() {
        val w = web ?: return
        if (completa != null) { val cb = salirCompleta; pantallaCompleta(null, null); cb?.onCustomViewHidden() }
        else if (w.canGoBack()) w.goBack()
    }
    fun inicio() { web?.loadUrl(YouTubeWeb.INICIO) }
    fun pausa() { web?.evaluateJavascript(YouTubeWeb.JS_PAUSA, null) }
    // ---- teclado del carro para escribir en la página
    private var tecladoAbierto = false

    private fun abrirTeclado(pista: String, clave: Boolean) {
        if (tecladoAbierto) return
        tecladoAbierto = true
        irA(PantallaEscribir(ctx, this, pista, clave))
    }

    fun escribirEnPagina(texto: String) { web?.evaluateJavascript(YouTubeWeb.jsPoner(texto), null) }

    fun tecladoCerrado() { tecladoAbierto = false; web?.evaluateJavascript("document.activeElement&&document.activeElement.blur()", null) }

    /** En el carro no se inicia sesión de Google (no hay teclado seguro): se hace desde el celular. */
    private fun avisoLogin() {
        YouTubeWeb.anotar(ctx, "   ▶ YouTube pidió iniciar sesión en el carro: se indica hacerlo desde el celular")
        val t = tapa ?: return
        if (t.visibility == View.VISIBLE) return
        t.text = "🔑 Para entrar con tu cuenta de YouTube usa el celular:\nJTV → ⋮ → Iniciar sesión de YouTube (carro).\n\nEn el carro sigue sin cuenta."
        t.visibility = View.VISIBLE
        main.postDelayed({ aplicarMovimiento() }, 6000)
    }

    fun buscar(texto: String) {
        if (texto.isBlank()) return
        web?.loadUrl("https://m.youtube.com/results?search_query=" + Uri.encode(texto.trim()))
    }
    fun desplazar(abajo: Boolean) {
        val w = web ?: return
        w.scrollBy(0, (if (abajo) 1 else -1) * (w.height * 0.6f).toInt())
    }

    fun irA(p: Screen) = ctx.getCarService(ScreenManager::class.java).push(p)
    fun volver() = ctx.getCarService(ScreenManager::class.java).popToRoot()

    // ------------------------------------------------------------------ toques en la pantalla del carro
    override fun onClick(x: Float, y: Float) {
        val r = raiz ?: return
        val t = SystemClock.uptimeMillis()
        val ab = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val ar = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        r.dispatchTouchEvent(ab); r.dispatchTouchEvent(ar)
        ab.recycle(); ar.recycle()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        web?.scrollBy(0, distanceY.toInt())
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        web?.flingScroll(0, -velocityY.toInt())
    }

    // ------------------------------------------------------------------ seguridad (GPS)
    private var enMovimiento = false
    private var ultimoMovimiento = 0L

    private fun tienePermisoGps() =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val gps = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (!l.hasSpeed()) return
            val ahora = SystemClock.elapsedRealtime()
            if (l.speed > 2.8f) { ultimoMovimiento = ahora; if (!enMovimiento) { enMovimiento = true; aplicarMovimiento() } }
            else if (enMovimiento && ahora - ultimoMovimiento > 4000) { enMovimiento = false; aplicarMovimiento() }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun iniciarGps() {
        if (!tienePermisoGps()) return
        try {
            ctx.getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gps, Looper.getMainLooper())
        } catch (e: Exception) {}
    }

    private fun aplicarMovimiento() {
        val pasajero = YouTubeWeb.modoPasajero(ctx)
        val tapar = !pasajero && (enMovimiento || !tienePermisoGps())
        tapa?.text = if (!tienePermisoGps()) "🔒 Con el modo pasajero apagado, JTV YouTube necesita el permiso de ubicación\n(para tapar la imagen mientras manejas). El sonido sigue."
                     else "🚗 Imagen en pausa mientras el carro avanza.\nEl sonido sigue. Al detenerte vuelve la imagen."
        tapa?.visibility = if (tapar) View.VISIBLE else View.GONE
    }
}

class PantallaYouTube(ctx: CarContext, private val yt: YouTubeCarro) : Screen(ctx) {
    private fun icono(res: Int, accion: () -> Unit): Action =
        Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build())
            .setOnClickListener { accion() }.build()

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(icono(R.drawable.ic_atras) { yt.atras() })
            .addAction(Action.Builder().setTitle("🏠").setOnClickListener { yt.inicio() }.build())
            .addAction(Action.Builder().setTitle("🔍 Buscar").setOnClickListener { yt.irA(PantallaBuscarYouTube(carContext, yt)) }.build())
            .addAction(Action.Builder().setTitle("⏯").setOnClickListener { yt.pausa() }.build())
        // ▲ ▼ para bajar por la página sin tocar, y "mover" para que el carro pase el deslizar del dedo
        val mapa = ActionStrip.Builder()
            .addAction(icono(R.drawable.ic_arriba) { yt.desplazar(false) })
            .addAction(icono(R.drawable.ic_abajo) { yt.desplazar(true) })
            .addAction(Action.PAN)
        return NavigationTemplate.Builder().setActionStrip(strip.build()).setMapActionStrip(mapa.build()).build()
    }
}

/** Teclado del carro para escribir en un cuadro de texto de la página (buscar, comentar, etc.). */
class PantallaEscribir(ctx: CarContext, private val yt: YouTubeCarro, private val pista: String, private val clave: Boolean) : Screen(ctx) {
    private var listo = false
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { yt.tecladoCerrado() }
        })
    }
    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {}
            override fun onSearchSubmitted(searchText: String) {
                if (listo) return
                listo = true
                yt.escribirEnPagina(searchText); yt.volver()
            }
        }).setHeaderAction(Action.BACK).setShowKeyboardByDefault(true)
            .setSearchHint(if (clave) "Contraseña" else pista.ifBlank { "Escribe aquí" })
            .setItemList(ItemList.Builder().setNoItemsMessage("Escribe y toca la lupa o Enter").build())
            .build()
}

/** Buscar en YouTube con el teclado del carro (o el micrófono del teclado). */
class PantallaBuscarYouTube(ctx: CarContext, private val yt: YouTubeCarro) : Screen(ctx) {
    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {}
            override fun onSearchSubmitted(searchText: String) { yt.buscar(searchText); yt.volver() }
        }).setHeaderAction(Action.BACK).setShowKeyboardByDefault(true)
            .setSearchHint("Buscar en YouTube").setItemList(ItemList.Builder().setNoItemsMessage("Escribe y toca buscar").build())
            .build()
}
