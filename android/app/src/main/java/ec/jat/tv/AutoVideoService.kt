package ec.jat.tv

import android.Manifest
import android.app.Presentation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
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
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.VideoSize
import android.view.TextureView
import android.view.WindowManager
import java.io.File

/**
 * TV JAT Video: canales y Mis Películas CON IMAGEN en la pantalla del carro (Android Auto).
 *
 * Funciona como lo hace Fermata Auto: Android Auto le presta a las apps de "navegación" una
 * pantalla libre (la que usan para dibujar el mapa); TV JAT dibuja ahí el video.
 * Requisito en el celular: Android Auto → Ajustes para desarrolladores → "Fuentes desconocidas".
 *
 * Seguridad: con el GPS del celular se sabe si el carro avanza. Mientras avanza, la imagen se
 * tapa y solo sigue el sonido; al detenerse vuelve la imagen.
 */
class AutoVideoService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCreateSession(): Session = SesionVideo()
}

class SesionVideo : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        Registro.instalar(carContext)
        Registro.anotar(carContext, "🖼️ TV JAT Video abierto en el carro (nivel ${carContext.carAppApiLevel})")
        val tv = VideoCarro(carContext)
        tv.arrancar()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = tv.liberar()
        })
        return PantallaVideo(carContext, tv)
    }
}

enum class Modo { CANALES, PELICULAS }

// =====================================================================================
//  El reproductor que dibuja en la pantalla del carro
// =====================================================================================
class VideoCarro(val ctx: CarContext) : SurfaceCallback {

    private val main = Handler(Looper.getMainLooper())
    val player: ExoPlayer
    var modo = Modo.CANALES
        private set
    private var canalesLista: List<Canal> = emptyList()
    private var pelisLista: List<Pelicula> = emptyList()
    @Volatile private var canalActual: Canal? = null
    private val probadosHls = HashSet<String>()
    private var errorSeguidos = 0

    /** Para que la pantalla de botones se actualice (ícono pausa/play, etc.). */
    var alCambiar: (() -> Unit)? = null

    // pantalla del carro
    private var superficie: Surface? = null
    private var ancho = 0
    private var alto = 0
    private var dpiCarro = 160
    private var area: Rect? = null
    private var vd: VirtualDisplay? = null
    private var pres: Presentation? = null
    private var vista: AspectRatioFrameLayout? = null   // marco que respeta la forma del video
    private var textura: TextureView? = null
    private var titulo: TextView? = null
    private var aviso: TextView? = null
    private var directa = false     // si no se pudo armar la pantalla, el video va directo

    // diagnóstico (se ve en Menú → Diagnóstico)
    private var dPantalla = "todavía no llega"
    private var dForma = "-"
    private var dVideo = "sin imagen todavía"
    private var dError = ""
    fun diagnostico(): String {
        val est = when (player.playbackState) {
            Player.STATE_READY -> if (player.isPlaying) "reproduciendo" else "en pausa"
            Player.STATE_BUFFERING -> "cargando"
            Player.STATE_ENDED -> "terminó"
            else -> "detenido"
        }
        return "Pantalla del carro: $dPantalla · Forma $dForma · Video: $dVideo · Estado: $est" +
            (if (dError.isNotEmpty()) " · Error: $dError" else "")
    }

    // seguridad: carro avanzando
    var enMovimiento = true
        private set
    private var hayGps = false
    private var lentoDesde = 0L
    private var avisoFijo = ""

    init {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Reproduccion.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val conCabeceras = ResolvingDataSource.Factory(http) { spec ->
            val c = canalActual
            val extra = HashMap<String, String>()
            if (modo == Modo.CANALES && c != null) {
                if (c.userAgent.isNotEmpty()) extra["User-Agent"] = c.userAgent
                if (c.referer.isNotEmpty()) extra["Referer"] = c.referer
            }
            if (extra.isEmpty()) spec else spec.withAdditionalHeaders(extra)
        }
        player = ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, conCabeceras)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val i = player.currentMediaItemIndex
                if (modo == Modo.CANALES) {
                    val c = canalesLista.getOrNull(i) ?: return
                    canalActual = c
                    Datos.guardarUltimo(ctx, c.url)
                    mostrarTitulo(c.nombre)
                    Registro.anotar(ctx, "▶ (video) Canal: ${c.nombre}  (${c.url})")
                } else {
                    val p = pelisLista.getOrNull(i) ?: return
                    // terminó la anterior y pasó sola a la siguiente (series)
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        pelisLista.getOrNull(i - 1)?.let { Peliculas.marcar(ctx, it, true) }
                        val pos = Peliculas.posicion(ctx, p)
                        if (pos > 5000) player.seekTo(pos)
                    }
                    mostrarTitulo(p.titulo)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) guardarAvance(false)
                alCambiar?.invoke()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                alCambiar?.invoke()
            }

            override fun onPlaybackStateChanged(state: Int) {
                Registro.anotar(ctx, "   (video) estado: " + when (state) { Player.STATE_BUFFERING -> "cargando"; Player.STATE_READY -> "listo"; Player.STATE_ENDED -> "terminó"; else -> "detenido" })
                when (state) {
                    Player.STATE_READY -> { errorSeguidos = 0; ponerAviso("") }
                    Player.STATE_BUFFERING -> ponerAviso("Cargando…")
                    Player.STATE_ENDED -> if (modo == Modo.PELICULAS) {
                        pelisLista.getOrNull(player.currentMediaItemIndex)?.let { Peliculas.marcar(ctx, it, true) }
                        ponerAviso("Terminó")
                    }
                    else -> {}
                }
            }

            override fun onVideoSizeChanged(vs: VideoSize) {
                Registro.anotar(ctx, "   🖼️ tamaño del video: ${vs.width}x${vs.height}")
                dVideo = if (vs.width > 0) "${vs.width}x${vs.height}" else "sin imagen (solo sonido)"
                if (vs.width > 0 && vs.height > 0) vista?.setAspectRatio(vs.width * vs.pixelWidthHeightRatio / vs.height)
            }

            override fun onPlayerError(e: PlaybackException) {
                dError = e.errorCodeName
                Registro.anotar(ctx, "   ⚠ video error: ${e.errorCodeName} · ${e.cause?.message ?: e.message}")
                val idx = player.currentMediaItemIndex
                val actual = player.currentMediaItem
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                if (modo == Modo.PELICULAS) {
                    ponerAviso("No se pudo abrir este video"); return
                }
                // la dirección no dice el formato: probar como transmisión en vivo (HLS)
                if (actual != null && actual.localConfiguration?.mimeType == null && probadosHls.add(actual.mediaId)) {
                    player.replaceMediaItem(idx, actual.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build())
                    player.prepare(); player.play(); return
                }
                // canal caído: pasa solo al siguiente (máximo 10 seguidos)
                errorSeguidos++
                if (player.hasNextMediaItem() && errorSeguidos <= 10) {
                    ponerAviso("Canal sin señal, pasando al siguiente…")
                    main.postDelayed({
                        if (player.currentMediaItemIndex == idx) {
                            player.seekToNextMediaItem(); player.prepare(); player.play()
                        }
                    }, 1500)
                } else ponerAviso("Este canal no responde")
            }
        })
    }

    /** Se llama cuando ya está todo listo (guardado del avance y GPS). */
    fun arrancar() {
        main.postDelayed(guardadoPeriodico, 10_000)
        iniciarGps()
    }

    // ------------------------------------------------------------------ canales
    private var cacheMarca = -1L
    private var cache: List<Canal> = emptyList()

    /** Canales de la lista actual, sin los caídos (si están ocultos en la app). */
    fun canales(): List<Canal> {
        val f = File(ctx.filesDir, "lista.m3u")
        val marca = if (f.exists()) f.lastModified() xor f.length() else 0L
        if (marca != cacheMarca) {
            cache = Datos.textoLista(ctx)?.let { M3u.leer(it) } ?: emptyList()
            cacheMarca = marca
        }
        val caidos = Datos.caidos(ctx)
        return if (Datos.ocultarCaidos(ctx)) cache.filter { it.url !in caidos } else cache
    }

    fun grupoDe(c: Canal) = c.grupo.ifBlank { "Otros" }

    private fun itemCanal(c: Canal): MediaItem {
        val b = MediaItem.Builder().setMediaId(c.url).setUri(Uri.parse(c.url))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(c.nombre).build())
        val u = c.url.lowercase()
        when {
            u.contains("m3u8") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            u.contains(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return b.build()
    }

    fun ponerCanales(lista: List<Canal>, inicio: Int) {
        if (lista.isEmpty()) return
        guardarAvance(true)
        AutoService.pausarSiSuena()
        modo = Modo.CANALES
        canalesLista = lista
        canalActual = lista[inicio]
        probadosHls.clear(); errorSeguidos = 0
        player.setMediaItems(lista.map { itemCanal(it) }, inicio, C.TIME_UNSET)
        player.prepare(); player.play()
        aplicarMovimiento()
        alCambiar?.invoke()
    }

    /** Al abrir TV JAT Video: sigue en el último canal que vio. */
    fun empezar() {
        if (player.mediaItemCount > 0) return
        val todos = canales()
        if (todos.isEmpty()) {
            ponerAviso("No hay lista de canales.\nAbre TV JAT en el celular y carga una lista.")
            return
        }
        val i = todos.indexOfFirst { it.url == Datos.ultimo(ctx) }.coerceAtLeast(0)
        ponerCanales(todos, i)
    }

    // ------------------------------------------------------------------ películas
    fun permisoVideos(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun puedeVerVideos() = ctx.checkSelfPermission(permisoVideos()) == PackageManager.PERMISSION_GRANTED

    fun ponerPeliculas(lista: List<Pelicula>, inicio: Int) {
        if (lista.isEmpty()) return
        guardarAvance(true)
        AutoService.pausarSiSuena()
        modo = Modo.PELICULAS
        pelisLista = lista
        canalActual = null
        val items = lista.map {
            MediaItem.Builder().setMediaId(it.clave).setUri(it.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.titulo).build()).build()
        }
        player.setMediaItems(items, inicio, Peliculas.posicion(ctx, lista[inicio]))
        player.prepare(); player.play()
        aplicarMovimiento()
        alCambiar?.invoke()
    }

    private fun guardarAvance(forzar: Boolean) {
        if (modo != Modo.PELICULAS) return
        val p = pelisLista.getOrNull(player.currentMediaItemIndex) ?: return
        val pos = player.currentPosition
        if (pos < 3000) return
        val dur = if (player.duration > 0) player.duration else p.duracionMs
        Peliculas.guardarPosicion(ctx, p, pos, dur, forzar)
    }

    private val guardadoPeriodico = object : Runnable {
        override fun run() {
            if (player.isPlaying) guardarAvance(false)
            main.postDelayed(this, 10_000)
        }
    }

    // ------------------------------------------------------------------ botones
    fun anterior() {
        if (modo == Modo.PELICULAS) player.seekTo(maxOf(0L, player.currentPosition - 10_000))
        else if (player.hasPreviousMediaItem()) { errorSeguidos = 0; player.seekToPreviousMediaItem(); player.play() }
    }

    fun siguiente() {
        if (modo == Modo.PELICULAS) player.seekTo(player.currentPosition + 30_000)
        else if (player.hasNextMediaItem()) { errorSeguidos = 0; player.seekToNextMediaItem(); player.play() }
    }

    fun pausaSigue() {
        if (player.mediaItemCount == 0) { empezar(); return }
        if (player.playWhenReady) player.pause()
        else {
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                if (modo == Modo.CANALES) player.seekToDefaultPosition()
                player.prepare()
            }
            player.play()
        }
    }

    fun irA(pantalla: Screen) = ctx.getCarService(ScreenManager::class.java).push(pantalla)
    fun volverAlVideo() = ctx.getCarService(ScreenManager::class.java).popToRoot()

    // ------------------------------------------------------------------ pantalla del carro
    override fun onSurfaceAvailable(sc: SurfaceContainer) {
        val s = sc.surface ?: return
        liberarPantalla()
        superficie = s; ancho = sc.width; alto = sc.height; dpiCarro = sc.dpi
        dPantalla = "${sc.width}x${sc.height}"
        if (Datos.formaImagen(ctx) == "B") {
            directa = true; dForma = "B (directa)"
        } else try {
            val dm = ctx.getSystemService(DisplayManager::class.java)
            val d = dm.createVirtualDisplay("TV JAT Video", sc.width, sc.height, sc.dpi, s, 0)
            vd = d
            val p = Presentation(ctx, d.display, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            p.window?.setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            p.setContentView(armarVista(p.context))
            p.show()
            pres = p
            directa = false
            dForma = "A (ajustada)"
        } catch (e: Exception) {
            // Plan B: el video va directo a la pantalla (puede verse algo estirado)
            liberarPantalla()
            superficie = s; ancho = sc.width; alto = sc.height
            directa = true
            dForma = "B (A falló: ${e.javaClass.simpleName})"
        }
        aplicarArea()
        aplicarMovimiento()
        Registro.anotar(ctx, "   pantalla del carro: $dPantalla · forma $dForma")
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        area = Rect(visibleArea)
        aplicarArea()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (area == null) { area = Rect(stableArea); aplicarArea() }
    }

    override fun onSurfaceDestroyed(sc: SurfaceContainer) {
        guardarAvance(true)
        liberarPantalla()
    }

    private fun dp(c: android.content.Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    private fun armarVista(c: android.content.Context): View {
        val raiz = FrameLayout(c).apply { setBackgroundColor(Color.BLACK) }
        val marco = AspectRatioFrameLayout(c).apply { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT }
        val tx = TextureView(c)
        marco.addView(tx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        raiz.addView(marco, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val vs = player.videoSize
        if (vs.width > 0 && vs.height > 0) marco.setAspectRatio(vs.width * vs.pixelWidthHeightRatio / vs.height)
        player.setVideoTextureView(tx)
        textura = tx
        val t = TextView(c).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6))
            visibility = View.GONE
        }
        raiz.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        val a = TextView(c).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 14))
            visibility = View.GONE
        }
        raiz.addView(a, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        vista = marco; titulo = t; aviso = a
        return raiz
    }

    /** El video ocupa solo la parte de la pantalla que no tapan los botones de Android Auto. */
    private fun aplicarArea() {
        val r = area ?: return
        if (ancho <= 0 || alto <= 0) return
        vista?.let { v ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.setMargins(r.left, r.top, maxOf(0, ancho - r.right), maxOf(0, alto - r.bottom))
            v.layoutParams = lp
        }
        titulo?.let { v ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.setMargins(r.left + 12, r.top + 12, 0, 0)
            v.layoutParams = lp
        }
    }

    private fun liberarPantalla() {
        textura?.let { player.clearVideoTextureView(it) }
        textura = null
        try { pres?.dismiss() } catch (e: Exception) {}
        try { vd?.release() } catch (e: Exception) {}
        if (directa) player.clearVideoSurface()
        pres = null; vd = null; vista = null; titulo = null; aviso = null
        superficie = null; directa = false
    }

    private val ocultarTitulo = Runnable { titulo?.visibility = View.GONE }

    private fun mostrarTitulo(texto: String) {
        val t = titulo ?: return
        t.text = texto
        t.visibility = View.VISIBLE
        main.removeCallbacks(ocultarTitulo)
        main.postDelayed(ocultarTitulo, 5000)
    }

    private var avisoTemporal = ""

    private fun ponerAviso(texto: String) {
        avisoTemporal = texto
        pintarAviso()
    }

    private fun pintarAviso() {
        val a = aviso ?: return
        val txt = avisoFijo.ifEmpty { avisoTemporal }
        a.text = txt
        a.visibility = if (txt.isEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ seguridad (GPS)
    private fun tienePermisoGps() =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val gps = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            main.removeCallbacks(sinSenal)
            hayGps = true
            if (!l.hasSpeed()) return
            val ahora = SystemClock.elapsedRealtime()
            if (l.speed > 2.8f) {             // más de ~10 km/h: avanza
                lentoDesde = 0
                if (!enMovimiento) { enMovimiento = true; aplicarMovimiento() }
            } else if (l.speed < 1.2f) {      // casi quieto por 3 segundos: detenido
                if (lentoDesde == 0L) lentoDesde = ahora
                if (enMovimiento && ahora - lentoDesde > 3000) { enMovimiento = false; aplicarMovimiento() }
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** Si en 20 s no hay señal de GPS (garaje, techo), se entiende que está estacionado. */
    private val sinSenal = Runnable {
        if (!hayGps) { enMovimiento = false; aplicarMovimiento() }
    }

    private fun iniciarGps() {
        if (!tienePermisoGps()) {
            if (Datos.modoPasajero(ctx)) { aplicarMovimiento(); return }   // no hace falta el GPS
            enMovimiento = true
            aplicarMovimiento()
            try {
                ctx.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { ok, _ ->
                    if (ok.contains(Manifest.permission.ACCESS_FINE_LOCATION)) iniciarGps()
                }
            } catch (e: Exception) {}
            return
        }
        try {
            val lm = ctx.getSystemService(LocationManager::class.java)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gps, Looper.getMainLooper())
            main.postDelayed(sinSenal, 20_000)
        } catch (e: Exception) {
            enMovimiento = false
        }
        aplicarMovimiento()
    }

    /** Mientras el carro avanza: imagen tapada, el sonido sigue. */
    private fun aplicarMovimiento() {
        val tapar = enMovimiento && !Datos.modoPasajero(ctx)
        avisoFijo = when {
            !tapar -> ""
            !tienePermisoGps() -> "🔒 Para ver la imagen, acepta en el celular el permiso de ubicación de TV JAT\n(sirve para tapar la imagen mientras manejas).\nEl sonido sigue."
            else -> "🚗 Imagen en pausa mientras el carro avanza.\nEl sonido sigue. Al detenerte vuelve la imagen."
        }
        // en canales, sin imagen se ahorran datos
        val sinVideo = tapar && modo == Modo.CANALES
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, sinVideo).build()
        if (directa) {
            val s = superficie
            if (s != null) {
                if (tapar) {
                    player.clearVideoSurface()
                    try { val c = s.lockCanvas(null); c.drawColor(Color.BLACK); s.unlockCanvasAndPost(c) } catch (e: Exception) {}
                } else player.setVideoSurface(s)
            }
        } else {
            vista?.visibility = if (tapar) View.INVISIBLE else View.VISIBLE
        }
        pintarAviso()
    }

    /** Activa/desactiva el modo pasajero desde el menú del carro. */
    fun cambiarModoPasajero() {
        val nuevo = !Datos.modoPasajero(ctx)
        Datos.setModoPasajero(ctx, nuevo)
        if (!nuevo) iniciarGps() else aplicarMovimiento()
    }

    /** Cambia entre la forma A (ajustada) y B (directa) de mostrar la imagen. */
    fun cambiarFormaImagen() {
        Datos.setFormaImagen(ctx, if (Datos.formaImagen(ctx) == "B") "A" else "B")
        val s = superficie ?: return
        val sc = SurfaceContainer(s, ancho, alto, dpiCarro)
        onSurfaceAvailable(sc)
    }

    fun liberar() {
        guardarAvance(true)
        main.removeCallbacksAndMessages(null)
        try { ctx.getSystemService(LocationManager::class.java).removeUpdates(gps) } catch (e: Exception) {}
        liberarPantalla()
        player.release()
        alCambiar = null
    }
}

// =====================================================================================
//  Pantallas (menús) en el carro
// =====================================================================================
private fun limiteLista(c: CarContext): Int = try {
    if (c.carAppApiLevel >= 2) c.getCarService(ConstraintManager::class.java)
        .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST) else 6
} catch (e: Exception) { 6 }

/** Pantalla principal: el video con los botones Menú · ⏮ · ⏯ · ⏭. */
class PantallaVideo(ctx: CarContext, private val tv: VideoCarro) : Screen(ctx) {
    init {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(tv)
        tv.empezar()
        tv.alCambiar = { invalidate() }
    }

    private fun icono(res: Int, accion: () -> Unit): Action =
        Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build())
            .setOnClickListener { accion() }.build()

    override fun onGetTemplate(): Template {
        val pelis = tv.modo == Modo.PELICULAS
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Menú").setOnClickListener { tv.irA(PantallaMenu(carContext, tv)) }.build())
            .addAction(icono(if (pelis) R.drawable.ic_atras else R.drawable.ic_anterior) { tv.anterior() })
            .addAction(icono(if (tv.player.playWhenReady) R.drawable.ic_pausa else R.drawable.ic_play) { tv.pausaSigue() })
            .addAction(icono(if (pelis) R.drawable.ic_adelante else R.drawable.ic_siguiente) { tv.siguiente() })
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }
}

/** Una fila de un menú. */
class Fila(val titulo: String, val texto: String = "", val carpeta: Boolean = false, val alTocar: () -> Unit)

/** Lista con páginas (Android Auto deja pocas filas por pantalla). */
open class PantallaFilas(ctx: CarContext, private val titulo: String, private val cargar: () -> List<Fila>) : Screen(ctx) {
    private var pagina = 0
    private var filas: List<Fila>? = null

    override fun onGetTemplate(): Template {
        val todas = filas ?: cargar().also { filas = it }
        val porPagina = maxOf(1, limiteLista(carContext) - 2)
        val desde = pagina * porPagina
        val lista = ItemList.Builder().setNoItemsMessage("No hay nada aquí")
        if (pagina > 0) lista.addItem(Row.Builder().setTitle("◂ Anteriores").setOnClickListener { pagina--; invalidate() }.build())
        todas.drop(desde).take(porPagina).forEach { f ->
            val r = Row.Builder().setTitle(f.titulo).setOnClickListener { f.alTocar() }
            if (f.texto.isNotEmpty()) r.addText(f.texto)
            if (f.carpeta) r.setBrowsable(true)
            lista.addItem(r.build())
        }
        val quedan = todas.size - desde - porPagina
        if (quedan > 0) lista.addItem(Row.Builder().setTitle("Siguientes ▸").addText("$quedan más")
            .setOnClickListener { pagina++; invalidate() }.build())
        @Suppress("DEPRECATION")
        return ListTemplate.Builder().setTitle(titulo).setHeaderAction(Action.BACK).setSingleList(lista.build()).build()
    }
}

private fun filasCanales(tv: VideoCarro, lista: List<Canal>): List<Fila> {
    val fav = Datos.favoritos(tv.ctx)
    return lista.mapIndexed { i, c ->
        Fila(c.nombre, (if (c.url in fav) "★ " else "") + tv.grupoDe(c)) { tv.ponerCanales(lista, i); tv.volverAlVideo() }
    }
}

class PantallaMenu(ctx: CarContext, tv: VideoCarro) : PantallaFilas(ctx, "TV JAT", {
    val todos = tv.canales()
    val fav = Datos.favoritos(ctx).let { f -> todos.filter { it.url in f } }
    val l = ArrayList<Fila>()
    if (fav.isNotEmpty()) l.add(Fila("★ Favoritos", "${fav.size} canales", true) {
        tv.irA(PantallaFilas(ctx, "★ Favoritos") { filasCanales(tv, fav) })
    })
    l.add(Fila("📺 Todos los canales", "${todos.size} canales", true) {
        tv.irA(PantallaFilas(ctx, "Todos los canales") { filasCanales(tv, tv.canales()) })
    })
    l.add(Fila("📂 Canales por grupo", "", true) { tv.irA(pantallaGrupos(ctx, tv)) })
    l.add(Fila("🎬 Mis Películas", "Videos guardados en el celular", true) { tv.irA(pantallaPeliculas(ctx, tv)) })
    l.add(Fila("🔍 Buscar canal") { tv.irA(PantallaBuscar(ctx, tv)) })
    val pasajero = Datos.modoPasajero(ctx)
    l.add(Fila(
        if (pasajero) "👥 Modo pasajero: ACTIVADO" else "👥 Modo pasajero: desactivado",
        if (pasajero) "La imagen se ve con el carro en marcha. Toca para taparla al avanzar"
        else "La imagen se tapa al avanzar. Toca para verla siempre"
    ) { tv.cambiarModoPasajero(); tv.volverAlVideo() })
    val forma = Datos.formaImagen(ctx)
    l.add(Fila(
        if (forma == "B") "🖼️ Forma de imagen: B (directa)" else "🖼️ Forma de imagen: A (ajustada)",
        "Si no se ve la imagen, toca para probar la otra forma"
    ) { tv.cambiarFormaImagen(); tv.volverAlVideo() })
    l.add(Fila("🔧 Diagnóstico", tv.diagnostico()) { tv.volverAlVideo() })
    l
})

private fun pantallaGrupos(ctx: CarContext, tv: VideoCarro) = PantallaFilas(ctx, "Grupos") {
    val todos = tv.canales()
    todos.groupBy { tv.grupoDe(it) }.toSortedMap(compareBy<String>({ it.lowercase() }, { it })).map { (g, lista) ->
        Fila(g, "${lista.size} canales", true) { tv.irA(PantallaFilas(ctx, g) { filasCanales(tv, lista) }) }
    }
}

private fun filasPeliculas(tv: VideoCarro, mostrar: List<Pelicula>, todas: List<Pelicula>): List<Fila> = mostrar.map { p ->
    val av = Peliculas.avance(tv.ctx, p)
    val texto = Peliculas.duracionTexto(p.duracionMs) + when {
        av >= 1f -> " · ✓ Vista"
        av > 0f -> " · Vas en ${(av * 100).toInt()} %"
        else -> ""
    }
    Fila(p.titulo, texto) {
        // se reproduce con las demás de su carpeta, en orden (para series)
        val carpeta = todas.filter { it.carpeta == p.carpeta }.sortedBy { it.archivo.lowercase() }
        tv.ponerPeliculas(carpeta, carpeta.indexOf(p).coerceAtLeast(0))
        tv.volverAlVideo()
    }
}

private fun pantallaPeliculas(ctx: CarContext, tv: VideoCarro) = PantallaFilas(ctx, "🎬 Mis Películas") {
    if (!tv.puedeVerVideos()) {
        listOf(Fila("Permitir ver tus videos", "Toca aquí y acepta en el celular") {
            try { ctx.requestPermissions(listOf(tv.permisoVideos())) { _, _ -> } } catch (e: Exception) {}
            tv.volverAlVideo()
        })
    } else {
        val todas = try { Peliculas.buscar(ctx) } catch (e: Exception) { emptyList() }
        val l = ArrayList<Fila>()
        val seguir = todas.filter { val a = Peliculas.avance(ctx, it); a > 0f && a < 1f }
            .sortedByDescending { Peliculas.ultimaVez(ctx, it) }
        if (seguir.isNotEmpty()) l.add(Fila("▶ Seguir viendo", "${seguir.size}", true) {
            tv.irA(PantallaFilas(ctx, "Seguir viendo") { filasPeliculas(tv, seguir, todas) })
        })
        if (todas.isNotEmpty()) l.add(Fila("🆕 Agregadas recientemente", "${todas.size} videos", true) {
            tv.irA(PantallaFilas(ctx, "Recientes") { filasPeliculas(tv, todas, todas) })
        })
        todas.groupBy { it.carpeta }.toSortedMap(compareBy<String>({ it.lowercase() }, { it })).forEach { (c, lista) ->
            l.add(Fila("📁 $c", "${lista.size} videos", true) {
                tv.irA(PantallaFilas(ctx, c) { filasPeliculas(tv, lista.sortedBy { it.archivo.lowercase() }, todas) })
            })
        }
        l
    }
}

/** Buscar un canal por nombre (con el carro detenido Android Auto deja escribir). */
class PantallaBuscar(ctx: CarContext, private val tv: VideoCarro) : Screen(ctx) {
    private var texto = ""

    override fun onGetTemplate(): Template {
        val t = texto.trim().lowercase()
        val res = if (t.isEmpty()) emptyList() else tv.canales()
            .filter { it.nombre.lowercase().contains(t) || it.grupo.lowercase().contains(t) }
            .take(maxOf(1, limiteLista(carContext)))
        val il = ItemList.Builder().setNoItemsMessage(if (t.isEmpty()) "Escribe el nombre del canal" else "No se encontró")
        res.forEachIndexed { i, c ->
            il.addItem(Row.Builder().setTitle(c.nombre).addText(tv.grupoDe(c))
                .setOnClickListener { tv.ponerCanales(res, i); tv.volverAlVideo() }.build())
        }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) { texto = searchText; invalidate() }
            override fun onSearchSubmitted(searchText: String) { texto = searchText; invalidate() }
        }).setHeaderAction(Action.BACK).setShowKeyboardByDefault(true)
            .setSearchHint("Nombre del canal").setItemList(il.build()).build()
    }
}
