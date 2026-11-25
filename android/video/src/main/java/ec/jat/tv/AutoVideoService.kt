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
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ImageView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.LruCache
import java.util.Locale
import java.util.concurrent.Executors
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
import ec.jat.tv.video.R

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
        AutoService.ctx = carContext.applicationContext
        if (!Puente.hayTvJat(carContext)) {
            return SinTvJat(carContext)
        }
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
    private var cuadrosPerdidos = 0L
    private var pausasCarga = 0
    private var yaArranco = false
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
            " · Cuadros perdidos: $cuadrosPerdidos · Pausas por carga: $pausasCarga" +
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
            // Colchón grande: junta varios segundos antes de empezar y no arranca otra vez
            // hasta tener 6 s guardados (evita el "avanza-se detiene-avanza")
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 90_000, 3_000, 6_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build())
            .build()
        // No pedir más calidad de la elegida (de fábrica 720p, como la pantalla del carro)
        aplicarCalidad()
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(et: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                cuadrosPerdidos += droppedFrames
            }
        })

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                yaArranco = false
                if (panelVisible) pintarPanel()
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
                actualizarCapa()
                alCambiar?.invoke()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                alCambiar?.invoke()
            }

            override fun onPlaybackStateChanged(state: Int) {
                Registro.anotar(ctx, "   (video) estado: " + when (state) { Player.STATE_BUFFERING -> "cargando"; Player.STATE_READY -> "listo"; Player.STATE_ENDED -> "terminó"; else -> "detenido" })
                when (state) {
                    Player.STATE_READY -> { errorSeguidos = 0; yaArranco = true; ponerAviso("") }
                    Player.STATE_BUFFERING -> { if (yaArranco) pausasCarga++; ponerAviso("Cargando…") }
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
        val marca = Datos.marcaLista(ctx)
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
            // En vivo: ir unos segundos atrás del "en directo" para tener colchón, y a velocidad
            // normal siempre (acelerar/frenar para alcanzar el directo hace que la imagen dé saltitos)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(15_000).setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
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
        // al abrir, mostrar la interfaz unos segundos para que se vea cómo usarla
        if (capa != null) main.postDelayed({ if (capa != null && !panelVisible) mostrarPanel() }, 800)
        Registro.anotar(ctx, "   pantalla del carro: $dPantalla · forma $dForma")
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        area = Rect(visibleArea)
        aplicarArea()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        areaEstable = Rect(stableArea)
        aplicarArea()
    }

    override fun onSurfaceDestroyed(sc: SurfaceContainer) {
        guardarAvance(true)
        liberarPantalla()
    }

    private fun dp(c: android.content.Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    private fun armarVista(c: android.content.Context): View {
        val raiz = FrameLayout(c).apply { setBackgroundColor(Color.BLACK) }
        val marco = AspectRatioFrameLayout(c).apply { resizeMode = modoAjuste() }
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
            setTypeface(typeface, Typeface.BOLD)
            background = redondo(0xB3000000.toInt(), dp(c, 8).toFloat())
            setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
            visibility = View.GONE
        }
        raiz.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        val a = TextView(c).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            background = redondo(0xCC000000.toInt(), dp(c, 12).toFloat())
            setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 14))
            visibility = View.GONE
        }
        raiz.addView(a, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        vista = marco; titulo = t; aviso = a
        armarCapa(c, raiz)
        raizVista = raiz
        return raiz
    }

    /** La interfaz se acomoda a la parte de la pantalla que Android Auto deja libre; el video ocupa todo. */
    private fun aplicarArea() {
        if (ancho <= 0 || alto <= 0) return
        val r = areaUsable()
        capa?.let { v ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.setMargins(r.left, r.top, maxOf(0, ancho - r.right), maxOf(0, alto - r.bottom))
            v.layoutParams = lp
        }
        panel?.let { v ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.width = (r.width() * 0.36f).toInt().coerceAtLeast(dp(v.context, 230)).coerceAtMost((r.width() * 0.5f).toInt())
            v.layoutParams = lp
        }
        titulo?.let { v ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.setMargins(r.left + 12, r.top + 12, 0, 0)
            v.layoutParams = lp
        }
    }

    /** Parte fija que Android Auto nunca tapa (si no la manda, la visible; si no, toda). */
    private fun areaUsable(): Rect {
        val e = areaEstable
        if (e != null && e.width() > 100 && e.height() > 100) return e
        val v = area
        if (v != null && v.width() > 100 && v.height() > 100) return v
        return Rect(0, 0, ancho, alto)
    }

    private fun liberarPantalla() {
        textura?.let { player.clearVideoTextureView(it) }
        textura = null
        try { pres?.dismiss() } catch (e: Exception) {}
        try { vd?.release() } catch (e: Exception) {}
        if (directa) player.clearVideoSurface()
        pres = null; vd = null; vista = null; titulo = null; aviso = null
        capa = null; panel = null; panelLista = null; panelScroll = null; panelTitulo = null; raizVista = null
        chipCanales = null; chipPelis = null; btnPausa = null; btnAnt = null; btnSig = null; lblAnt = null; lblSig = null
        infoNombre = null; infoSub = null; infoTiempo = null; infoVivo = null; infoCalidad = null; barra = null
        filasLogo.clear(); filaMarcada = null
        panelVisible = false
        main.removeCallbacks(refresco); main.removeCallbacks(ocultarSolo)
        superficie = null; directa = false
    }

    private val ocultarTitulo = Runnable { titulo?.visibility = View.GONE }

    private fun mostrarTitulo(texto: String) {
        val t = titulo ?: return
        if (panelVisible) return       // la barra de abajo ya dice el nombre
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

    // ------------------------------------------------------------------ interfaz sobre el video
    // Diseño: barra de arriba (Canales · Películas · TV JAT · 🔍), lista de canales a la derecha,
    // botones redondos abajo (Guía · Anterior · Pausa · Siguiente · Cerrar TV) y barra de información
    // (canal, avance/colchón, EN VIVO, Ajustes, Calidad).
    private val AZUL_SEL = 0xFF1F3B5E.toInt()
    private val ACENTO = 0xFF2F8BFF.toInt()
    private val ROJO = 0xFFE53935.toInt()
    private val GRIS = 0xFFB4BAC2.toInt()
    private val BOTON = 0xCC3A3F47.toInt()

    private var raizVista: FrameLayout? = null
    private var capa: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var panelScroll: ScrollView? = null
    private var panelLista: LinearLayout? = null
    private var panelTitulo: TextView? = null
    private var chipCanales: TextView? = null
    private var chipPelis: TextView? = null
    private var btnPausa: ImageView? = null
    private var btnAnt: ImageView? = null
    private var btnSig: ImageView? = null
    private var lblAnt: TextView? = null
    private var lblSig: TextView? = null
    private var infoNombre: TextView? = null
    private var infoSub: TextView? = null
    private var infoTiempo: TextView? = null
    private var infoVivo: TextView? = null
    private var infoCalidad: TextView? = null
    private var barra: Barra? = null
    private var areaEstable: Rect? = null
    var panelVisible = false
        private set
    private var panelVista = "canales"      // canales · grupos · pelis · carpeta · opciones
    private var grupoSel: String? = null      // null = todos · "★" = favoritos
    private var carpetaSel = ""

    /** Android Auto manda los toques en la pantalla desde el nivel 5. */
    fun aceptaToques() = try { ctx.carAppApiLevel >= 5 } catch (e: Exception) { false }

    fun modoAjuste() = if (Datos.ajusteImagen(ctx) == "entera") AspectRatioFrameLayout.RESIZE_MODE_FIT
                       else AspectRatioFrameLayout.RESIZE_MODE_ZOOM

    fun cambiarAjuste() {
        Datos.setAjusteImagen(ctx, if (Datos.ajusteImagen(ctx) == "entera") "completa" else "entera")
        vista?.resizeMode = modoAjuste()
        if (panelVisible) pintarPanel()
    }

    // calidad máxima de la imagen (menos calidad = menos datos y menos cortes)
    fun calidadTexto() = when (Datos.calidad(ctx)) { "alta" -> "1080p"; "ahorro" -> "480p"; else -> "720p" }

    private fun aplicarCalidad() {
        val (w, h) = when (Datos.calidad(ctx)) { "alta" -> 1920 to 1080; "ahorro" -> 854 to 480; else -> 1280 to 720 }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(w, h).build()
    }

    fun cambiarCalidad() {
        val sig = when (Datos.calidad(ctx)) { "auto" -> "alta"; "alta" -> "ahorro"; else -> "auto" }
        Datos.setCalidad(ctx, sig)
        aplicarCalidad()
        ponerAviso(""); actualizarCapa()
        if (panelVista == "opciones") pintarPanel()
    }

    private fun redondo(color: Int, radio: Float = -1f) = GradientDrawable().apply {
        if (radio < 0) shape = GradientDrawable.OVAL else cornerRadius = radio
        setColor(color)
    }

    private fun degradado(arribaOscuro: Boolean, oscuro: Int) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (arribaOscuro) intArrayOf(oscuro, 0x00000000) else intArrayOf(0x00000000, oscuro)
    )

    private fun chip(c: android.content.Context, txt: String, accion: () -> Unit) = TextView(c).apply {
        text = txt
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6))
        setOnClickListener { accion(); tocado() }
    }

    private class Boton(val vista: LinearLayout, val icono: ImageView?, val texto: TextView)

    private fun boton(c: android.content.Context, icono: Int, glifo: String, etiqueta: String,
                      fondo: Int, tinte: Int, tam: Int, accion: () -> Unit): Boton {
        val caja = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { accion(); tocado() }
        }
        val circulo = FrameLayout(c).apply { background = redondo(fondo) }
        var img: ImageView? = null
        if (icono != 0) {
            img = ImageView(c).apply { setImageResource(icono); setColorFilter(tinte) }
            val m = tam / 4
            circulo.addView(img, FrameLayout.LayoutParams(dp(c, tam - 2 * m), dp(c, tam - 2 * m), Gravity.CENTER))
        } else {
            circulo.addView(TextView(c).apply {
                text = glifo; setTextColor(tinte); gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, tam * 0.42f)
                setTypeface(typeface, Typeface.BOLD)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        caja.addView(circulo, LinearLayout.LayoutParams(dp(c, tam), dp(c, tam)))
        val t = TextView(c).apply {
            text = etiqueta; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(0, dp(c, 3), 0, 0)
        }
        caja.addView(t)
        return Boton(caja, img, t)
    }

    private fun miniBoton(c: android.content.Context, glifo: String, etiqueta: String, accion: () -> Unit): Pair<View, TextView> {
        val caja = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(c, 10), dp(c, 2), dp(c, 10), dp(c, 2))
            setOnClickListener { accion(); tocado() }
        }
        caja.addView(TextView(c).apply { text = glifo; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f) })
        val t = TextView(c).apply { text = etiqueta; setTextColor(GRIS); gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        caja.addView(t)
        return caja to t
    }

    private fun armarCapa(c: android.content.Context, raiz: FrameLayout) {
        val cp = FrameLayout(c).apply {
            visibility = View.GONE
            setOnClickListener { ocultarPanel() }      // tocar la imagen (fuera de botones) esconde todo
        }

        // ---- barra de arriba
        val arriba = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = degradado(true, 0xDD000000.toInt())
            setPadding(dp(c, 8), 0, dp(c, 8), 0)
            setOnClickListener { tocado() }
        }
        val cc = chip(c, "📺 Canales") { panelVista = "canales"; pintarPanel() }
        val cpel = chip(c, "🎬 Películas") { panelVista = if (carpetaSel.isNotEmpty() && modo == Modo.PELICULAS) "carpeta" else "pelis"; pintarPanel() }
        arriba.addView(cc); arriba.addView(cpel)
        arriba.addView(TextView(c).apply {
            text = "TV JAT"; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        arriba.addView(chip(c, "🔍") { ocultarPanel(); irA(PantallaBuscar(ctx, this)) }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) })
        cp.addView(arriba, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 50), Gravity.TOP))

        // ---- lista al costado derecho
        val pn = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = redondo(0xE6151920.toInt(), dp(c, 10).toFloat())
            clipToOutline = true
            setOnClickListener { tocado() }
        }
        val tt = TextView(c).apply {
            setTextColor(ACENTO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 6))
            setOnClickListener {
                panelVista = when (panelVista) { "canales" -> "grupos"; "carpeta" -> "pelis"; "grupos" -> "canales"; else -> panelVista }
                pintarPanel(); tocado()
            }
        }
        pn.addView(tt)
        val sv = ScrollView(c).apply {
            isVerticalScrollBarEnabled = true
            setOnScrollChangeListener { _, _, _, _, _ -> cargarLogosVisibles() }
        }
        val ll = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(ll)
        pn.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        cp.addView(pn, FrameLayout.LayoutParams((ancho * 0.36f).toInt().coerceAtLeast(dp(c, 230)), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.TOP).apply {
            topMargin = dp(c, 52); bottomMargin = dp(c, 150); rightMargin = dp(c, 8)
        })

        // ---- abajo: botones redondos + barra de información
        val abajo = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setOnClickListener { tocado() } }
        val controles = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = degradado(false, 0xAA000000.toInt())
            setPadding(dp(c, 8), dp(c, 10), dp(c, 8), dp(c, 6))
        }
        val lpB = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val guia = boton(c, 0, "☰", "Guía", BOTON, Color.WHITE, 48) { panelVista = "grupos"; pintarPanel() }
        val ant = boton(c, R.drawable.ic_anterior, "", "Anterior", BOTON, Color.WHITE, 48) { anterior() }
        val pau = boton(c, R.drawable.ic_pausa, "", "Pausa", Color.WHITE, ROJO, 60) { pausaSigue(); actualizarCapa() }
        val sig = boton(c, R.drawable.ic_siguiente, "", "Siguiente", BOTON, Color.WHITE, 48) { siguiente() }
        val cer = boton(c, 0, "✕", "Cerrar TV", ROJO, Color.WHITE, 48) { cerrar() }
        listOf(guia, ant, pau, sig, cer).forEach { controles.addView(it.vista, lpB) }
        abajo.addView(controles)

        val info = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xF0121519.toInt())
            setPadding(dp(c, 16), dp(c, 6), dp(c, 6), dp(c, 6))
        }
        val izq = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val nom = TextView(c).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); setTypeface(typeface, Typeface.BOLD)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(c).apply {
            setTextColor(GRIS); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        izq.addView(nom); izq.addView(sub)
        info.addView(izq, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
        val centro = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 12), 0, dp(c, 8), 0) }
        val br = Barra(c).apply {
            alTocar = { f ->
                if (modo == Modo.PELICULAS && player.duration > 0) { player.seekTo((player.duration * f).toLong()); actualizarCapa() }
                tocado()
            }
        }
        centro.addView(br, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 20)))
        val filaT = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tiempo = TextView(c).apply { setTextColor(GRIS); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); maxLines = 1 }
        filaT.addView(tiempo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val vivo = TextView(c).apply {
            text = "● EN VIVO"; setTextColor(ROJO); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTypeface(typeface, Typeface.BOLD)
        }
        filaT.addView(vivo)
        centro.addView(filaT)
        info.addView(centro, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        val (aj, _) = miniBoton(c, "⚙", "Ajustes") { panelVista = "opciones"; pintarPanel() }
        val (ca, caTxt) = miniBoton(c, "📶", "Calidad") { cambiarCalidad() }
        info.addView(aj); info.addView(ca)
        abajo.addView(info)
        cp.addView(abajo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        raiz.addView(cp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        capa = cp; panel = pn; panelScroll = sv; panelLista = ll; panelTitulo = tt
        chipCanales = cc; chipPelis = cpel
        btnPausa = pau.icono; btnAnt = ant.icono; btnSig = sig.icono; lblAnt = ant.texto; lblSig = sig.texto
        infoNombre = nom; infoSub = sub; infoTiempo = tiempo; infoVivo = vivo; infoCalidad = caTxt; barra = br
    }

    private fun hms(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** Pone al día botones, nombre, barra de avance/colchón y calidad. */
    fun actualizarCapa() {
        if (!panelVisible) return
        val pelis = modo == Modo.PELICULAS
        btnPausa?.setImageResource(if (player.playWhenReady) R.drawable.ic_pausa else R.drawable.ic_play)
        btnAnt?.setImageResource(if (pelis) R.drawable.ic_atras else R.drawable.ic_anterior)
        btnSig?.setImageResource(if (pelis) R.drawable.ic_adelante else R.drawable.ic_siguiente)
        lblAnt?.text = if (pelis) "−10 s" else "Anterior"
        lblSig?.text = if (pelis) "+30 s" else "Siguiente"
        val enPelis = panelVista == "pelis" || panelVista == "carpeta"
        val enCanales = panelVista == "canales" || panelVista == "grupos"
        chipCanales?.background = if (enCanales) redondo(AZUL_SEL, 40f) else null
        chipPelis?.background = if (enPelis) redondo(AZUL_SEL, 40f) else null
        val i = player.currentMediaItemIndex
        if (pelis) {
            val p = pelisLista.getOrNull(i)
            infoNombre?.text = p?.titulo ?: "Mis Películas"
            infoSub?.text = if (p != null) "${p.carpeta} · ${i + 1} de ${pelisLista.size}" else ""
        } else {
            val c = canalesLista.getOrNull(i)
            infoNombre?.text = c?.nombre ?: "Sin canal"
            infoSub?.text = if (c != null) "${grupoDe(c)} · Canal ${i + 1} de ${canalesLista.size}" else "Toca ☰ Guía para elegir"
        }
        val dur = player.duration
        val pos = player.currentPosition
        val enVivo = !pelis && (player.isCurrentMediaItemLive || dur == C.TIME_UNSET || dur <= 0)
        val br = barra
        if (enVivo) {
            val colchon = (player.bufferedPosition - pos).coerceAtLeast(0)
            br?.colorRelleno = ACENTO
            br?.avance = colchon / 30_000f
            infoTiempo?.text = "Colchón: ${colchon / 1000} s"
            infoVivo?.visibility = View.VISIBLE
        } else {
            br?.colorRelleno = ROJO
            br?.avance = if (dur > 0) pos.toFloat() / dur else 0f
            infoTiempo?.text = if (dur > 0) "${hms(pos)} / ${hms(dur)}" else hms(pos)
            infoVivo?.visibility = View.GONE
        }
        infoCalidad?.text = "Calidad ${calidadTexto()}"
    }

    private val refresco = object : Runnable {
        override fun run() {
            if (!panelVisible) return
            actualizarCapa()
            main.postDelayed(this, 1000)
        }
    }

    // ---- filas de la lista
    private val filasLogo = ArrayList<Pair<View, ImageView>>()
    private var filaMarcada: View? = null
    private val logos = LruCache<String, Bitmap>(150)
    private val pedidosLogo = HashSet<String>()
    private val hiloLogos = Executors.newSingleThreadExecutor()

    private fun iniciales(nombre: String): String {
        val limpio = nombre.replace(Regex("\\(.*?\\)|\\[.*?]"), " ").trim()
        return limpio.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.take(1) }.uppercase().ifEmpty { "TV" }
    }

    /** Fila de canal como en la foto: número, logo, nombre y la etiqueta roja EN VIVO. */
    private fun filaCanal(n: Int, c: Canal, marcada: Boolean, fav: Boolean, accion: () -> Unit) {
        val ll = panelLista ?: return
        val cx = ll.context
        val f = LinearLayout(cx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(cx, 10), dp(cx, 8), dp(cx, 10), dp(cx, 8))
            if (marcada) setBackgroundColor(AZUL_SEL)
            setOnClickListener { accion(); tocado() }
        }
        val caja = FrameLayout(cx).apply { background = redondo(0xFF0D1F4A.toInt(), dp(cx, 6).toFloat()); clipToOutline = true }
        caja.addView(TextView(cx).apply {
            text = iniciales(c.nombre); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTypeface(typeface, Typeface.BOLD)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val img = ImageView(cx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; tag = c }
        caja.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        f.addView(caja, LinearLayout.LayoutParams(dp(cx, 44), dp(cx, 44)))
        val textos = LinearLayout(cx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(cx, 10), 0, 0, 0) }
        textos.addView(TextView(cx).apply {
            text = "$n. " + (if (fav) "★ " else "") + c.nombre
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            if (marcada) setTypeface(typeface, Typeface.BOLD)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        textos.addView(TextView(cx).apply {
            text = "EN VIVO"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            background = redondo(if (marcada) ROJO else 0xFF8E2A28.toInt(), dp(cx, 3).toFloat())
            setPadding(dp(cx, 6), dp(cx, 1), dp(cx, 6), dp(cx, 1))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(cx, 3) })
        f.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ll.addView(f)
        if (marcada) {
            ll.addView(View(cx).apply { setBackgroundColor(ACENTO) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(cx, 3)))
            filaMarcada = f
        } else ll.addView(View(cx).apply { setBackgroundColor(0x1FFFFFFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        filasLogo.add(f to img)
    }

    /** Logos: TV JAT los baja (LogoProvider) y aquí se piden solo los de las filas que se ven. */
    private fun cargarLogosVisibles() {
        val sv = panelScroll ?: return
        val arriba = sv.scrollY - 300
        val abajo = sv.scrollY + sv.height + 600
        for ((f, img) in filasLogo) {
            if (img.drawable != null || f.bottom < arriba || f.top > abajo) continue
            val c = img.tag as? Canal ?: continue
            if (c.logo.isBlank()) continue
            val clave = c.logo + "|" + c.nombre
            val enCache = logos.get(clave)
            if (enCache != null) { img.setImageBitmap(enCache); continue }
            if (!pedidosLogo.add(clave)) continue
            val uri = Uri.Builder().scheme("content").authority("ec.jat.tv.logos").appendPath("logo")
                .appendQueryParameter("u", c.logo).appendQueryParameter("n", c.nombre).build()
            try {
                hiloLogos.execute {
                    val bmp = try { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
                    if (bmp != null) main.post {
                        logos.put(clave, bmp); pedidosLogo.remove(clave)
                        if (img.tag == c) img.setImageBitmap(bmp)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun fila(texto: String, sub: String = "", marcada: Boolean = false, accion: () -> Unit) {
        val ll = panelLista ?: return
        val c = ll.context
        val v = TextView(c).apply {
            text = if (sub.isEmpty()) texto else "$texto\n$sub"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, if (marcada) Typeface.BOLD else Typeface.NORMAL)
            setPadding(dp(c, 14), dp(c, 12), dp(c, 12), dp(c, 12))
            if (marcada) setBackgroundColor(AZUL_SEL)
            setOnClickListener { accion(); tocado() }
        }
        ll.addView(v)
        if (marcada) {
            ll.addView(View(c).apply { setBackgroundColor(ACENTO) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 3)))
            filaMarcada = v
        } else ll.addView(View(c).apply { setBackgroundColor(0x1FFFFFFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    fun pintarPanel() {
        val ll = panelLista ?: return
        ll.removeAllViews()
        filasLogo.clear(); filaMarcada = null
        panelScroll?.scrollTo(0, 0)
        when (panelVista) {
            "grupos" -> {
                panelTitulo?.text = "☰ Guía · elige un grupo"
                val todos = canales()
                val fav = Datos.favoritos(ctx)
                fila("★ Favoritos", "${todos.count { it.url in fav }} canales", grupoSel == "★") { grupoSel = "★"; panelVista = "canales"; pintarPanel() }
                fila("📺 Todos", "${todos.size} canales", grupoSel == null) { grupoSel = null; panelVista = "canales"; pintarPanel() }
                fila("🎬 Mis Películas", "Videos del celular") { panelVista = "pelis"; pintarPanel() }
                todos.groupBy { grupoDe(it) }.toSortedMap(compareBy<String>({ it.lowercase() }, { it })).forEach { (g, l) ->
                    fila(g, "${l.size} canales", grupoSel == g) { grupoSel = g; panelVista = "canales"; pintarPanel() }
                }
            }
            "pelis" -> {
                panelTitulo?.text = "🎬 Mis Películas"
                if (!puedeVerVideos()) {
                    fila("Permitir ver tus videos", "Toca y acepta en el celular") {
                        try { ctx.requestPermissions(listOf(permisoVideos())) { _, _ -> pintarPanel() } } catch (e: Exception) {}
                    }
                } else {
                    val todas = try { Peliculas.buscar(ctx) } catch (e: Exception) { emptyList() }
                    val seguir = todas.filter { val a = Peliculas.avance(ctx, it); a > 0f && a < 1f }
                        .sortedByDescending { Peliculas.ultimaVez(ctx, it) }.take(5)
                    seguir.forEach { p -> fila("▶ ${p.titulo}", "Seguir viendo · ${(Peliculas.avance(ctx, p) * 100).toInt()} %") { ponerPeliDe(p, todas) } }
                    todas.groupBy { it.carpeta }.toSortedMap(compareBy<String>({ it.lowercase() }, { it })).forEach { (cp, l) ->
                        fila("📁 $cp", "${l.size} videos") { carpetaSel = cp; panelVista = "carpeta"; pintarPanel() }
                    }
                    if (todas.isEmpty()) fila("No hay videos en el celular") {}
                }
            }
            "carpeta" -> {
                panelTitulo?.text = "◂ $carpetaSel"
                val todas = try { Peliculas.buscar(ctx) } catch (e: Exception) { emptyList() }
                val actual = if (modo == Modo.PELICULAS) pelisLista.getOrNull(player.currentMediaItemIndex)?.clave else null
                todas.filter { it.carpeta == carpetaSel }.sortedBy { it.archivo.lowercase() }.forEach { p ->
                    val av = Peliculas.avance(ctx, p)
                    val sub = Peliculas.duracionTexto(p.duracionMs) + when { av >= 1f -> " · ✓ Vista"; av > 0f -> " · ${(av * 100).toInt()} %"; else -> "" }
                    fila(p.titulo, sub, p.clave == actual) { ponerPeliDe(p, todas) }
                }
            }
            "opciones" -> {
                panelTitulo?.text = "⚙ Ajustes"
                val entera = Datos.ajusteImagen(ctx) == "entera"
                fila(if (entera) "🖼️ Imagen: ENTERA" else "🖼️ Imagen: COMPLETA",
                    if (entera) "Se ve todo, con franjas negras" else "Llena la pantalla (recorta bordes)") { cambiarAjuste() }
                fila("📶 Calidad: ${calidadTexto()}", when (Datos.calidad(ctx)) {
                    "alta" -> "Mejor imagen, gasta más datos"; "ahorro" -> "Menos datos y menos cortes"; else -> "Normal (como la pantalla del carro)"
                }) { cambiarCalidad() }
                val pasajero = Datos.modoPasajero(ctx)
                fila(if (pasajero) "👥 Modo pasajero: ACTIVADO" else "👥 Modo pasajero: desactivado",
                    if (pasajero) "La imagen se ve con el carro en marcha" else "La imagen se tapa al avanzar") { cambiarModoPasajero(); pintarPanel() }
                fila(if (Datos.formaImagen(ctx) == "B") "🔧 Forma: B (directa)" else "🔧 Forma: A (normal)",
                    "Solo si no se ve la imagen") { cambiarFormaImagen() }
                fila("📋 Menú completo") { ocultarPanel(); irA(PantallaMenu(ctx, this)) }
                fila("🔧 Diagnóstico", diagnostico()) {}
            }
            else -> {   // canales
                val todos = canales()
                val lista = when (grupoSel) {
                    null -> todos
                    "★" -> Datos.favoritos(ctx).let { f -> todos.filter { it.url in f } }
                    else -> todos.filter { grupoDe(it) == grupoSel }
                }
                panelTitulo?.text = "📂 " + when (grupoSel) { null -> "Todos"; "★" -> "★ Favoritos"; else -> grupoSel } + "  ▾ cambiar"
                val fav = Datos.favoritos(ctx)
                val actual = if (modo == Modo.CANALES) canalActual?.url else null
                lista.take(500).forEachIndexed { i, c ->
                    filaCanal(i + 1, c, c.url == actual, c.url in fav) { ponerCanales(lista, i); pintarPanel() }
                }
                if (lista.isEmpty()) fila("No hay canales aquí") {}
            }
        }
        actualizarCapa()
        // llevar a la vista el canal que está sonando y bajar los logos de lo que se ve
        panelScroll?.post {
            val v = filaMarcada
            if (v != null) panelScroll?.scrollTo(0, maxOf(0, v.top - dp(ll.context, 60)))
            cargarLogosVisibles()
        }
    }

    private fun ponerPeliDe(p: Pelicula, todas: List<Pelicula>) {
        val carpeta = todas.filter { it.carpeta == p.carpeta }.sortedBy { it.archivo.lowercase() }
        ponerPeliculas(carpeta, carpeta.indexOf(p).coerceAtLeast(0))
        carpetaSel = p.carpeta; panelVista = "carpeta"; pintarPanel()
    }

    /** Se esconde sola a los 12 s sin tocar (si está en pausa se queda). */
    private val ocultarSolo = Runnable { if (player.isPlaying) ocultarPanel() else tocado() }
    private fun tocado() { main.removeCallbacks(ocultarSolo); if (panelVisible) main.postDelayed(ocultarSolo, 12_000) }

    fun mostrarPanel() {
        val cp = capa
        if (cp == null) { irA(PantallaMenu(ctx, this)); return }   // forma B: no hay dónde dibujar
        if (panelVista == "canales" && modo == Modo.PELICULAS) panelVista = "carpeta"
        if (panelVista == "carpeta" && carpetaSel.isEmpty()) panelVista = "pelis"
        panelVisible = true
        titulo?.visibility = View.GONE
        pintarPanel()
        cp.visibility = View.VISIBLE
        tocado()
        main.removeCallbacks(refresco); main.post(refresco)
        alCambiar?.invoke()
    }

    fun ocultarPanel() {
        capa?.visibility = View.GONE
        panelVisible = false
        main.removeCallbacks(ocultarSolo); main.removeCallbacks(refresco)
        alCambiar?.invoke()
    }

    fun alternarPanel() { if (panelVisible) ocultarPanel() else mostrarPanel() }

    /** Botón "Cerrar TV": guarda, detiene y cierra TV JAT Video en el carro. */
    fun cerrar() {
        guardarAvance(true)
        player.pause()
        ocultarPanel()
        try { ctx.finishCarApp() } catch (e: Exception) {}
    }

    // toques en la pantalla del carro
    override fun onClick(x: Float, y: Float) {
        if (capa == null || !panelVisible) { mostrarPanel(); return }
        val raiz = raizVista ?: return
        val t = SystemClock.uptimeMillis()
        val ab = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val ar = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        raiz.dispatchTouchEvent(ab); raiz.dispatchTouchEvent(ar)
        ab.recycle(); ar.recycle()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!panelVisible) return
        panelScroll?.scrollBy(0, distanceY.toInt())
        tocado()
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (!panelVisible) return
        panelScroll?.fling(-velocityY.toInt())
        tocado()
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
        hiloLogos.shutdownNow()
        player.release()
        alCambiar = null
    }
}

/** Barra de avance (películas) o de colchón (en vivo); en películas se toca para saltar. */
class Barra(c: android.content.Context) : View(c) {
    var avance = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var colorRelleno = Color.WHITE
        set(v) { field = v; invalidate() }
    var alTocar: ((Float) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val mitad = height / 2f
        val grueso = height * 0.24f
        val r = height * 0.32f
        val x0 = r; val x1 = width - r
        p.color = 0x55FFFFFF
        cv.drawRoundRect(x0, mitad - grueso / 2, x1, mitad + grueso / 2, grueso, grueso, p)
        val xf = x0 + (x1 - x0) * avance
        p.color = colorRelleno
        cv.drawRoundRect(x0, mitad - grueso / 2, xf, mitad + grueso / 2, grueso, grueso, p)
        p.color = Color.WHITE
        cv.drawCircle(xf, mitad, r, p)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val f = alTocar ?: return false
        if (e.action == MotionEvent.ACTION_UP) {
            val r = height * 0.32f
            f(((e.x - r) / (width - 2 * r)).coerceIn(0f, 1f))
        }
        return true
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
            .addAction(Action.Builder().setTitle(if (tv.panelVisible) "✕" else "☰ Menú").setOnClickListener {
                if (tv.aceptaToques()) tv.alternarPanel() else tv.irA(PantallaMenu(carContext, tv))
            }.build())
            .addAction(icono(if (pelis) R.drawable.ic_atras else R.drawable.ic_anterior) { tv.anterior() })
            .addAction(icono(if (tv.player.playWhenReady) R.drawable.ic_pausa else R.drawable.ic_play) { tv.pausaSigue() })
            .addAction(icono(if (pelis) R.drawable.ic_adelante else R.drawable.ic_siguiente) { tv.siguiente() })
            .build()
        // El botón de "mover" hace que Android Auto pase los toques y el deslizar del dedo a la pantalla
        // (igual que Fermata Auto). Así se puede tocar y deslizar la lista de canales del costado.
        return NavigationTemplate.Builder().setActionStrip(strip)
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build()).build()
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

/** Si falta la app TV JAT (de ahí salen la lista y los favoritos). */
class SinTvJat(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template =
        androidx.car.app.model.MessageTemplate.Builder(
            "Falta la app TV JAT en el celular (o hay que actualizarla).\nInstala TV JAT, ábrela una vez y carga una lista de canales."
        ).setTitle("TV JAT Video").build()
}
