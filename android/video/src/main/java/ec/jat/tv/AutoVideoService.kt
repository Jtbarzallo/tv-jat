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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.media.AudioFormat
import androidx.car.app.media.CarAudioRecord
import androidx.media3.common.Timeline
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
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
 * JTV Video: canales y Mis Películas CON IMAGEN en la pantalla del carro (Android Auto).
 *
 * Funciona como lo hace Fermata Auto: Android Auto le presta a las apps de "navegación" una
 * pantalla libre (la que usan para dibujar el mapa); JTV dibuja ahí el video.
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
        Registro.anotar(carContext, "🖼️ JTV Video abierto en el carro (nivel ${carContext.carAppApiLevel})")
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
    /** Forma normal, pero sin nada encima: el video va directo a la pantalla del carro (fluido). */
    private var salidaDirecta = false

    // diagnóstico (se ve en Menú → Diagnóstico)
    private var cuadrosPerdidos = 0L
    private var perdidosMinuto = 0L
    /** Cada minuto anota en el diagnóstico los cuadros perdidos (para saber si el video va fluido). */
    private val anotarCuadros: Runnable = object : Runnable {
        override fun run() {
            if (player.isPlaying && superficie != null) {
                val forma = when { directa -> "directa"; salidaDirecta -> "directo (sin lista)"; else -> "con lista encima" }
                Registro.anotar(ctx, "   🎞 último minuto: $perdidosMinuto cuadros perdidos · ${player.videoSize.width}x${player.videoSize.height} · $forma")
            }
            perdidosMinuto = 0
            main.postDelayed(this, 60_000)
        }
    }
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
            " · Cuadros perdidos: $cuadrosPerdidos · Pausas por carga: $pausasCarga · Grabador: ${Grabador.info()}" +
            (if (dError.isNotEmpty()) " · Error: $dError" else "")
    }

    // seguridad: carro avanzando
    var enMovimiento = true
        private set
    private var hayGps = false
    private var lentoDesde = 0L
    private var avisoFijo = ""

    // canales por dirección (para saber qué canal pide el reproductor, aunque cambie solo)
    @Volatile private var porUrl: Map<String, Canal> = emptyMap()
    @Volatile private var calidadMem: String = "auto"

    init {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Reproduccion.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val conCabeceras = ResolvingDataSource.Factory(http) { spec -> resolverPedido(spec) }
        Grabador.iniciar(ctx)
        main.postDelayed(anotarCuadros, 60_000)
        // si el chip de video del celular no puede abrir un canal, probar con otro decodificador
        val renderizadores = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(ctx, renderizadores)
            .setMediaSourceFactory(FabricaFuentes(DefaultDataSource.Factory(ctx, conCabeceras)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Colchón en memoria (lo grabado está en el Grabador). Si se queda sin colchón,
            // espera a tener 6 s antes de seguir (evita el "avanza-se detiene-avanza").
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 60_000, 2_500, 6_000)
                .setTargetBufferBytes(48 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build())
            .build()
        // No pedir más calidad de la elegida (de fábrica 720p, como la pantalla del carro)
        aplicarCalidad()
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(et: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                cuadrosPerdidos += droppedFrames
                perdidosMinuto += droppedFrames
            }
        })

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                yaArranco = false
                reintentoPantalla = false
                if (listaVisible) repintarLista()
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
                // error del cambio de pantalla (no del canal): reintentar el mismo canal, sin saltar
                if (arreglarErrorDePantalla(e)) return
                if (modo == Modo.PELICULAS) {
                    ponerAviso("No se pudo abrir este video"); return
                }
                // la dirección no dice el formato: probar como transmisión en vivo (HLS)
                if (actual != null && actual.localConfiguration?.mimeType == null && probadosHls.add(actual.mediaId)) {
                    player.replaceMediaItem(idx, actual.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build())
                    player.prepare(); player.play(); return
                }
                // sin internet: no es el canal; esperar y reintentar el MISMO canal
                if (esDeRed(e) && !hayInternet()) {
                    ponerAviso("📶 Sin internet.\nReintentando este canal…")
                    main.removeCallbacks(reintento)
                    main.postDelayed(reintento, 5000)
                    return
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

    /** Antes de cada descarga: canal grabado (línea de tiempo) o directo con sus cabeceras. */
    private fun resolverPedido(spec: androidx.media3.datasource.DataSpec): androidx.media3.datasource.DataSpec {
        // Línea de tiempo: si el reproductor pide la lista de un canal, se graba y se ve desde lo grabado
        val pedido = if (modo == Modo.CANALES) porUrl[spec.uri.toString()] else null
        if (pedido != null) {
            val local = try { Grabador.direccion(pedido.url, pedido.userAgent, pedido.referer, maxAlto()) } catch (e: Exception) { null }
            if (local != null) return spec.withUri(local)
        }
        val c = canalActual
            val extra = HashMap<String, String>()
            if (modo == Modo.CANALES && c != null) {
                if (c.userAgent.isNotEmpty()) extra["User-Agent"] = c.userAgent
                if (c.referer.isNotEmpty()) extra["Referer"] = c.referer
            }
        return if (extra.isEmpty()) spec else spec.withAdditionalHeaders(extra)
    }

    // ------------------------------------------------------------------ red
    private fun esDeRed(e: PlaybackException) = e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        e.errorCode == PlaybackException.ERROR_CODE_TIMEOUT

    private fun hayInternet(): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cap = cm.getNetworkCapabilities(cm.activeNetwork)
        cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) { true }

    private val reintento: Runnable = object : Runnable {
        override fun run() {
            if (modo != Modo.CANALES) return
            if (!hayInternet()) { ponerAviso("📶 Sin internet.\nReintentando este canal…"); main.postDelayed(this, 5000); return }
            ponerAviso("Volvió el internet…")
            errorSeguidos = 0
            player.prepare(); player.play()
        }
    }

    private fun maxAlto() = when (calidadMem) { "alta" -> 1080; "ahorro" -> 480; else -> 720 }

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
        val lista = if (Datos.ocultarCaidos(ctx)) cache.filter { it.url !in caidos } else cache
        // En el carro solo salen los FAVORITOS (los que Jorge marcó con ★); el resto no se muestra
        val fav = favoritos()
        return lista.filter { it.url in fav }
    }

    /** Hay lista cargada en JTV (aunque no haya favoritos). */
    private fun hayLista(): Boolean = cache.isNotEmpty() || (Datos.textoLista(ctx)?.isNotBlank() == true)

    fun grupoDe(c: Canal) = c.grupo.ifBlank { "Otros" }

    private fun itemCanal(c: Canal): MediaItem {
        val b = MediaItem.Builder().setMediaId(c.url).setUri(Uri.parse(c.url))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(c.nombre).build())
            // En vivo: velocidad normal siempre (acelerar/frenar para alcanzar el directo hace que la imagen dé saltitos), y a velocidad
            // normal siempre (acelerar/frenar para alcanzar el directo hace que la imagen dé saltitos)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder()
                .setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
        val u = c.url.lowercase()
        when {
            u.contains("m3u8") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            u.contains(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return b.build()
    }

    fun ponerCanales(lista: List<Canal>, inicio: Int) {
        if (lista.isEmpty()) return
        if (enWeb) cerrarTikTok()
        guardarAvance(true)
        AutoService.pausarSiSuena()
        modo = Modo.CANALES
        canalesLista = lista
        porUrl = lista.associateBy { it.url }
        canalActual = lista[inicio]
        main.removeCallbacks(reintento)
        probadosHls.clear(); errorSeguidos = 0
        player.setMediaItems(lista.map { itemCanal(it) }, inicio, C.TIME_UNSET)
        player.prepare(); player.play()
        aplicarMovimiento()
        alCambiar?.invoke()
    }

    /** Al abrir JTV Video: sigue en el último canal que vio. */
    fun empezar() {
        if (player.mediaItemCount > 0) return
        val todos = canales()
        if (todos.isEmpty()) {
            ponerAviso(if (hayLista()) "No tienes canales favoritos.\nEn JTV del celular toca la ★ de los canales que quieras ver aquí."
                       else "No hay lista de canales.\nAbre JTV en el celular y carga una lista.")
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
        if (enWeb) cerrarTikTok()
        guardarAvance(true)
        AutoService.pausarSiSuena()
        modo = Modo.PELICULAS
        Grabador.detener()
        main.removeCallbacks(reintento)
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
        // La forma la decide sola la app: normal (con la lista encima del video); si la imagen
        // sale negra, pasa sola a la directa (ver revisarImagen)
        if (formaDirectaSesion) {
            directa = true; dForma = "directa (automática)"
        } else try {
            val dm = ctx.getSystemService(DisplayManager::class.java)
            val d = dm.createVirtualDisplay("JTV Video", sc.width, sc.height, sc.dpi, s, 0)
            vd = d
            val p = Presentation(ctx, d.display, R.style.Jtv_Pantalla)
            p.window?.setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            p.setContentView(armarVista(p.context))
            p.show()
            pres = p
            directa = false
            dForma = "normal"
            main.removeCallbacks(revisarImagen); main.postDelayed(revisarImagen, 12_000)
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
        if (enWeb) { if (directa) cerrarTikTok() else armarWeb() }
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
            typeface = Jtv.titulo(c)
            background = Jtv.forma(c, 12f, 9f, intArrayOf(Jtv.conAlfa(Jtv.SUPERFICIE2, 0.85f), Jtv.conAlfa(Jtv.NAVY, 0.85f)), Jtv.LINEA, 1f)
            setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
            visibility = View.GONE
        }
        raiz.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        val a = TextView(c).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            typeface = Jtv.titulo(c)
            background = Jtv.forma(c, 18f, 14f, intArrayOf(Jtv.conAlfa(Jtv.SUPERFICIE2, 0.92f), Jtv.conAlfa(Jtv.NAVY, 0.92f)), Jtv.LINEA, 1.2f)
            setPadding(dp(c, 24), dp(c, 16), dp(c, 24), dp(c, 16))
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
        ajustarFranja()
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
        quitarWeb()
        textura?.let { player.clearVideoTextureView(it) }
        textura = null
        try { pres?.dismiss() } catch (e: Exception) {}
        try { vd?.release() } catch (e: Exception) {}
        if (directa || salidaDirecta) player.clearVideoSurface()
        salidaDirecta = false
        main.removeCallbacks(aDirecto)
        pres = null; vd = null; vista = null; titulo = null; aviso = null
        capa = null; panel = null; panelLista = null; panelScroll = null; panelTitulo = null; raizVista = null
        chipCanales = null; chipPelis = null; btnPausa = null; btnAnt = null; btnSig = null; lblAnt = null; lblSig = null
        infoNombre = null; infoSub = null; infoTiempo = null; infoVivo = null; infoCalidad = null; barra = null; estrella = null
        filasLogo.clear(); filaMarcada = null; filasAccion.clear(); cursor = -1
        panelVisible = false
        listaVisible = false; main.removeCallbacks(aOcultarLista)
        main.removeCallbacks(refresco); main.removeCallbacks(ocultarSolo); main.removeCallbacks(revisarImagen)
        superficie = null; directa = false
    }

    private val ocultarTitulo = Runnable { titulo?.visibility = View.GONE }

    private fun mostrarTitulo(texto: String) {
        val t = titulo ?: return
        if (panelVisible || salidaDirecta) return       // la barra de abajo ya dice el nombre
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
        revisarSalida()
    }

    // ------------------------------------------------------------------ interfaz sobre el video
    // Diseño: barra de arriba (Canales · Películas · JTV · 🔍), lista de canales a la derecha,
    // botones redondos abajo (Guía · Anterior · Pausa · Siguiente · Cerrar TV) y barra de información
    // (canal, avance/colchón, EN VIVO, Ajustes, Calidad).
    // colores de JTV (los mismos del celular)
    private val AZUL_SEL = 0xFF1A2A78.toInt()
    private val ACENTO = Jtv.CIELO
    private val ROJO = Jtv.VIVO
    private val GRIS = Jtv.TEXTO2
    private val BOTON = Jtv.conAlfa(Jtv.SUPERFICIE2, 0.85f)

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
        if (listaVisible) repintarLista()
    }

    // calidad máxima de la imagen (menos calidad = menos datos y menos cortes)
    fun calidadTexto() = when (calidadMem) { "alta" -> "1080p"; "ahorro" -> "480p"; else -> "720p" }

    private fun aplicarCalidad() {
        calidadMem = Datos.calidad(ctx)
        val (w, h) = when (calidadMem) { "alta" -> 1920 to 1080; "ahorro" -> 854 to 480; else -> 1280 to 720 }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(w, h).build()
    }

    fun cambiarCalidad() {
        val sig = when (calidadMem) { "auto" -> "alta"; "alta" -> "ahorro"; else -> "auto" }
        Datos.setCalidad(ctx, sig)
        calidadMem = sig
        aplicarCalidad()
        ponerAviso(""); actualizarCapa()
        if (listaVisible && panelVista == "opciones") repintarLista()
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
        typeface = Jtv.textoFuerte(c)
        setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7))
        setOnClickListener { accion(); tocado() }
        Jtv.vivo(this, 0.92f, Jtv.forma(c, 12f, 8f, intArrayOf(Color.WHITE)))
    }

    private class Boton(val vista: LinearLayout, val icono: ImageView?, val texto: TextView)

    private fun boton(c: android.content.Context, icono: Int, glifo: String, etiqueta: String,
                      fondo: Int, tinte: Int, tam: Int, accion: () -> Unit): Boton {
        val caja = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { accion(); tocado() }
        }
        // botones con la forma JTV: el principal con el degradado de la marca, cerrar en rojo, el resto de vidrio azul
        val circulo = FrameLayout(c).apply {
            background = when (fondo) {
                Color.WHITE -> Jtv.forma(c, tam * 0.32f, tam * 0.22f, Jtv.MARCA)
                ROJO -> Jtv.forma(c, tam * 0.32f, tam * 0.22f, intArrayOf(Jtv.VIVO, 0xFFB81E4E.toInt()))
                else -> Jtv.forma(c, tam * 0.32f, tam * 0.22f, intArrayOf(Jtv.conAlfa(Jtv.SUPERFICIE2, 0.88f), Jtv.conAlfa(Jtv.NAVY, 0.88f)), Jtv.LINEA, 1.2f)
            }
        }
        val tinteReal = if (fondo == Color.WHITE) Color.WHITE else tinte
        var img: ImageView? = null
        if (icono != 0) {
            img = ImageView(c).apply { setImageResource(icono); setColorFilter(tinteReal) }
            val m = tam / 4
            circulo.addView(img, FrameLayout.LayoutParams(dp(c, tam - 2 * m), dp(c, tam - 2 * m), Gravity.CENTER))
        } else {
            circulo.addView(TextView(c).apply {
                text = glifo; setTextColor(tinteReal); gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, tam * 0.42f)
                typeface = Jtv.titulo(c)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        caja.addView(circulo, LinearLayout.LayoutParams(dp(c, tam), dp(c, tam)))
        Jtv.vivo(circulo, 0.9f)
        circulo.setOnClickListener { accion(); tocado() }
        val t = TextView(c).apply {
            text = etiqueta; setTextColor(Jtv.HIELO); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Jtv.textoFuerte(c)
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
            Jtv.vivo(this, 0.9f, Jtv.forma(c, 12f, 8f, intArrayOf(Color.WHITE)))
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
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Jtv.conAlfa(Jtv.NEGRO, 0.92f), Jtv.conAlfa(Jtv.NAVY, 0.55f), 0x00000000))
            setPadding(dp(c, 8), 0, dp(c, 8), 0)
            setOnClickListener { tocado() }
        }
        val cc = chip(c, "📺 Canales") { panelVista = "canales"; pintarPanel() }
        val cpel = chip(c, "🎬 Películas") { panelVista = if (carpetaSel.isNotEmpty() && modo == Modo.PELICULAS) "carpeta" else "pelis"; pintarPanel() }
        arriba.addView(cc); arriba.addView(cpel)
        arriba.addView(chip(c, "🎵 TikTok") { abrirTikTok() })
        // logo oficial de JTV al centro
        val centroLogo = FrameLayout(c)
        centroLogo.addView(Jtv.logo(c, 28), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 28), Gravity.CENTER))
        arriba.addView(centroLogo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        arriba.addView(chip(c, "🎤 Voz") { buscarPorVoz() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Jtv.titulo(c)
            background = Jtv.forma(c, 14f, 10f, Jtv.MARCA)
        })
        cp.addView(arriba, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 50), Gravity.TOP))

        // ---- lista en franja aparte, a la izquierda (no va encima del video)
        val pn = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Jtv.SUPERFICIE2, Jtv.NAVY, Jtv.FONDO))
            visibility = View.GONE
            setOnClickListener { tocado() }
        }
        val tt = TextView(c).apply {
            setTextColor(ACENTO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Jtv.titulo(c)
            setPadding(dp(c, 16), dp(c, 12), dp(c, 14), dp(c, 8))
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
        raiz.addView(pn, FrameLayout.LayoutParams((ancho * 0.34f).toInt().coerceAtLeast(dp(c, 230)), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.TOP))

        // ---- abajo: botones redondos + barra de información
        val abajo = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setOnClickListener { tocado() } }
        val controles = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, Jtv.conAlfa(Jtv.NAVY, 0.55f), Jtv.conAlfa(Jtv.NEGRO, 0.85f)))
            setPadding(dp(c, 8), dp(c, 10), dp(c, 8), dp(c, 6))
        }
        val lpB = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val guia = boton(c, 0, "☰", "Lista", BOTON, Color.WHITE, 48) { alternarLista() }
        val ant = boton(c, R.drawable.ic_anterior, "", "Anterior", BOTON, Color.WHITE, 48) { anterior() }
        val pau = boton(c, R.drawable.ic_pausa, "", "Pausa", Color.WHITE, ROJO, 60) { pausaSigue(); actualizarCapa() }
        val sig = boton(c, R.drawable.ic_siguiente, "", "Siguiente", BOTON, Color.WHITE, 48) { siguiente() }
        val cer = boton(c, 0, "⏻", "Apagar", ROJO, Color.WHITE, 48) { cerrar() }
        listOf(guia, ant, pau, sig, cer).forEach { controles.addView(it.vista, lpB) }
        abajo.addView(controles)

        val info = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Jtv.conAlfa(Jtv.NAVY, 0.97f), Jtv.conAlfa(0xFF0F1A52.toInt(), 0.97f), Jtv.conAlfa(Jtv.NAVY, 0.97f)))
            setPadding(dp(c, 16), dp(c, 8), dp(c, 8), dp(c, 8))
        }
        val est = TextView(c).apply {
            text = "☆"; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setOnClickListener { cambiarFavoritoActual(); tocado() }
        }
        info.addView(est, LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)).apply { rightMargin = dp(c, 8) })
        val izq = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val nom = TextView(c).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); typeface = Jtv.titulo(c)
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
                // películas: salta a ese minuto · en vivo: regresa en la línea de tiempo
                Salto.ir(player, f) { avisoBreve("No se pudo ir a ese punto.\nVolví a EN VIVO") }
                actualizarCapa()
                tocado()
            }
        }
        centro.addView(br, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 20)))
        val filaT = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tiempo = TextView(c).apply { setTextColor(GRIS); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); maxLines = 1 }
        filaT.addView(tiempo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val vivo = TextView(c).apply {
            text = "● EN VIVO"; setTextColor(ROJO); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Jtv.titulo(c)
            setPadding(dp(c, 6), dp(c, 2), dp(c, 2), dp(c, 2))
            setOnClickListener { irAlDirecto(); tocado() }
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
        infoNombre = nom; infoSub = sub; infoTiempo = tiempo; infoVivo = vivo; infoCalidad = caTxt; barra = br; estrella = est
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
        chipCanales?.let { it.background = if (enCanales) Jtv.forma(it.context, 12f, 8f, intArrayOf(AZUL_SEL, Jtv.conAlfa(Jtv.INDIGO, 0.7f)), Jtv.LINEA, 1f) else null }
        chipPelis?.let { it.background = if (enPelis) Jtv.forma(it.context, 12f, 8f, intArrayOf(AZUL_SEL, Jtv.conAlfa(Jtv.INDIGO, 0.7f)), Jtv.LINEA, 1f) else null }
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
        val colchon = (player.bufferedPosition - pos).coerceAtLeast(0)
        if (enVivo && dur > 0 && dur != C.TIME_UNSET) {
            // línea de tiempo: de lo más viejo que se puede ver hasta el directo
            br?.colorRelleno = 0x00000000
            br?.colorGuardado = ACENTO
            br?.guardado = player.bufferedPosition.toFloat() / dur
            br?.avance = pos.toFloat() / dur
            val atras = (dur - pos).coerceAtLeast(0)
            infoTiempo?.text = "Viendo ${horaReloj(atras)} · grabado ${hms(dur)}"
            val lejos = atras > 50_000
            infoVivo?.text = if (lejos) "● Ir a EN VIVO" else "● EN VIVO"
            infoVivo?.visibility = View.VISIBLE
        } else if (enVivo) {
            // transmisión sin línea de tiempo (solo se puede guardar hacia adelante)
            br?.colorRelleno = 0x00000000
            br?.colorGuardado = ACENTO
            br?.guardado = colchon / 60_000f
            br?.avance = 0f
            infoTiempo?.text = "Guardado: ${colchon / 1000} s"
            infoVivo?.text = "● EN VIVO"
            infoVivo?.visibility = View.VISIBLE
        } else {
            br?.colorRelleno = Jtv.ELECTRICO
            br?.colorGuardado = 0x55FFFFFF
            br?.guardado = if (dur > 0) player.bufferedPosition.toFloat() / dur else 0f
            br?.avance = if (dur > 0) pos.toFloat() / dur else 0f
            infoTiempo?.text = if (dur > 0) "${hms(pos)} / ${hms(dur)}" else hms(pos)
            infoVivo?.visibility = View.GONE
        }
        // estrella de favorito (solo en canales)
        val est = estrella
        val cAct = if (!pelis) canalesLista.getOrNull(i) else null
        if (est != null) {
            if (cAct == null) est.visibility = View.GONE
            else {
                est.visibility = View.VISIBLE
                val fav = cAct.url in favoritos()
                est.text = if (fav) "★" else "☆"
                est.setTextColor(if (fav) 0xFF2A1F00.toInt() else GRIS)
                est.background = if (fav) Jtv.forma(est.context, 12f, 8f, intArrayOf(Jtv.ORO, 0xFFFF9F2E.toInt())) else GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setStroke(dp(est.context, 2), GRIS)
                }
            }
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
            if (marcada) background = Jtv.forma(cx, 12f, 9f, intArrayOf(AZUL_SEL, Jtv.conAlfa(Jtv.INDIGO, 0.6f)), Jtv.ELECTRICO, 1.6f)
            setOnClickListener { accion(); tocado() }
        }
        val caja = FrameLayout(cx).apply {
            background = Jtv.forma(cx, 10f, 7f, intArrayOf(0xFF1B2766.toInt(), 0xFF0E1745.toInt())); clipToOutline = true
            setPadding(dp(cx, 3), dp(cx, 3), dp(cx, 3), dp(cx, 3))
        }
        caja.addView(TextView(cx).apply {
            text = iniciales(c.nombre); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Jtv.titulo(cx)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val img = ImageView(cx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; tag = c }
        caja.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        f.addView(caja, LinearLayout.LayoutParams(dp(cx, 44), dp(cx, 44)))
        val textos = LinearLayout(cx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(cx, 10), 0, 0, 0) }
        textos.addView(TextView(cx).apply {
            text = "$n. " + (if (fav) "★ " else "") + c.nombre
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = if (marcada) Jtv.titulo(cx) else Jtv.texto(cx)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        textos.addView(TextView(cx).apply {
            text = "● EN VIVO"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Jtv.titulo(cx); letterSpacing = 0.06f
            background = Jtv.forma(cx, 4f, 3f, if (marcada) intArrayOf(Jtv.VIVO, 0xFFE0305F.toInt()) else intArrayOf(0xFF7A2346.toInt()))
            setPadding(dp(cx, 6), dp(cx, 1), dp(cx, 6), dp(cx, 1))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(cx, 3) })
        f.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ll.addView(f)
        filasAccion.add(f to accion)
        if (marcada) {
            ll.addView(View(cx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(cx, 3)))
            filaMarcada = f
        } else ll.addView(View(cx).apply { setBackgroundColor(Jtv.conAlfa(Jtv.CIELO, 0.10f)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        filasLogo.add(f to img)
    }

    /** Logos: JTV los baja (LogoProvider) y aquí se piden solo los de las filas que se ven. */
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
            typeface = if (marcada) Jtv.titulo(c) else Jtv.texto(c)
            setPadding(dp(c, 14), dp(c, 12), dp(c, 12), dp(c, 12))
            if (marcada) background = Jtv.forma(c, 12f, 9f, intArrayOf(AZUL_SEL, Jtv.conAlfa(Jtv.INDIGO, 0.6f)), Jtv.ELECTRICO, 1.6f)
            setOnClickListener { accion(); tocado() }
        }
        ll.addView(v)
        filasAccion.add(v to accion)
        if (marcada) {
            ll.addView(View(c), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 3)))
            filaMarcada = v
        } else ll.addView(View(c).apply { setBackgroundColor(Jtv.conAlfa(Jtv.CIELO, 0.10f)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    /** Pinta la lista y la muestra (si estaba escondida). */
    fun pintarPanel() {
        repintarLista()
        if (!listaVisible) mostrarLista() else tocarLista()
    }

    /** Solo vuelve a pintar la lista (sin mostrarla). */
    fun repintarLista() {
        val ll = panelLista ?: return
        ll.removeAllViews()
        filasLogo.clear(); filaMarcada = null
        filasAccion.clear(); cursor = -1
        favMem = null
        panelScroll?.scrollTo(0, 0)
        when (panelVista) {
            "grupos" -> {
                panelTitulo?.text = "☰ Guía"
                val todos = canales()
                fila("★ Favoritos", "${todos.size} canales", true) { grupoSel = null; panelVista = "canales"; pintarPanel() }
                fila("🎬 Mis Películas", "Videos del celular") { panelVista = "pelis"; pintarPanel() }
                fila("🎵 TikTok", "Videos de TikTok en el carro") { abrirTikTok() }
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
            "voz" -> {
                panelTitulo?.text = if (vozTexto.isEmpty()) "🎤 Búsqueda por voz" else "🎤 «$vozTexto»"
                fila("🎤 Hablar otra vez") { buscarPorVoz() }
                val todos = canales()
                val fav = favoritos()
                vozResultados.forEachIndexed { n, c ->
                    filaCanal(n + 1, c, false, c.url in fav) {
                        grupoSel = null
                        val i = todos.indexOfFirst { it.url == c.url }
                        if (i >= 0) ponerCanales(todos, i) else ponerCanales(vozResultados, n)
                        panelVista = "canales"; pintarPanel()
                    }
                }
                if (vozResultados.isEmpty() && vozTexto.isNotEmpty()) fila("No encontré ese canal", "Prueba otra vez o usa el teclado") {}
                fila("⌨ Escribir con teclado", "Solo con el carro detenido") { ocultarPanel(); irA(PantallaBuscar(ctx, this)) }
            }
            "opciones" -> {
                panelTitulo?.text = "⚙ Ajustes"
                val entera = Datos.ajusteImagen(ctx) == "entera"
                fila(if (entera) "🖼️ Imagen: ENTERA" else "🖼️ Imagen: COMPLETA",
                    if (entera) "Se ve todo, con franjas negras" else "Llena la pantalla (recorta bordes)") { cambiarAjuste() }
                fila("📶 Calidad: ${calidadTexto()}", when (calidadMem) {
                    "alta" -> "Mejor imagen, gasta más datos"; "ahorro" -> "Menos datos y menos cortes"; else -> "Normal (como la pantalla del carro)"
                }) { cambiarCalidad() }
                val pasajero = Datos.modoPasajero(ctx)
                fila(if (pasajero) "👥 Modo pasajero: ACTIVADO" else "👥 Modo pasajero: desactivado",
                    if (pasajero) "La imagen se ve con el carro en marcha" else "La imagen se tapa al avanzar") { cambiarModoPasajero(); pintarPanel() }
                fila("📋 Menú completo") { ocultarPanel(); irA(PantallaMenu(ctx, this)) }
                fila("🔧 Diagnóstico", diagnostico()) {}
            }
            else -> {   // canales
                val lista = canales()          // en el carro: solo favoritos
                panelTitulo?.text = "★ Favoritos  ▾ cambiar"
                val fav = favoritos()
                val actual = if (modo == Modo.CANALES) canalActual?.url else null
                lista.take(500).forEachIndexed { i, c ->
                    filaCanal(i + 1, c, c.url == actual, c.url in fav) { ponerCanales(lista, i); pintarPanel() }
                }
                if (lista.isEmpty()) fila("No tienes canales favoritos", "En JTV del celular toca la ★ de los canales que quieras aquí") {}
            }
        }
        actualizarCapa()
        // llevar a la vista el canal que está sonando y bajar los logos de lo que se ve
        panelScroll?.post {
            val v = filaMarcada
            if (v != null) panelScroll?.scrollTo(0, maxOf(0, v.top - dp(ll.context, 60)))
            cursor = filasAccion.indexOfFirst { it.first === v }.coerceAtLeast(0)
            if (usaCursor) pintarCursor()
            cargarLogosVisibles()
        }
    }

    private fun ponerPeliDe(p: Pelicula, todas: List<Pelicula>) {
        val carpeta = todas.filter { it.carpeta == p.carpeta }.sortedBy { it.archivo.lowercase() }
        ponerPeliculas(carpeta, carpeta.indexOf(p).coerceAtLeast(0))
        carpetaSel = p.carpeta; panelVista = "carpeta"; pintarPanel()
    }

    // ------------------------------------------------------------------ cursor (carros sin toque)
    private val filasAccion = ArrayList<Pair<View, () -> Unit>>()
    private var cursor = -1
    private var usaCursor = false

    private fun pintarCursor() {
        filasAccion.forEach { it.first.foreground = null }
        val v = filasAccion.getOrNull(cursor)?.first ?: return
        v.foreground = GradientDrawable().apply { setStroke(dp(v.context, 3), Color.WHITE) }
        val sv = panelScroll ?: return
        if (v.top < sv.scrollY || v.bottom > sv.scrollY + sv.height)
            sv.smoothScrollTo(0, maxOf(0, v.top - sv.height / 3))
    }

    /** Botones ▲ ▼ del carro: mueven el cuadro blanco por la lista. */
    fun moverCursor(d: Int) {
        if (!listaVisible) { alternarLista(); return }
        if (filasAccion.isEmpty()) return
        usaCursor = true
        cursor = (cursor + d).coerceIn(0, filasAccion.size - 1)
        pintarCursor(); tocado()
    }

    /** Botón ✓ del carro: elige la fila del cuadro blanco. */
    fun elegirCursor() {
        if (!listaVisible) { alternarLista(); return }
        val f = filasAccion.getOrNull(cursor) ?: return
        f.second(); tocado()
    }

    // ------------------------------------------------------------------ favorito con la estrella
    private var estrella: TextView? = null
    private var favMem: Set<String>? = null
    /** Favoritos guardados en memoria (no se pregunta a JTV cada segundo: eso trababa la imagen). */
    private fun favoritos(): Set<String> = favMem ?: Datos.favoritos(ctx).also { favMem = it }

    fun esFavoritoActual(): Boolean {
        if (modo != Modo.CANALES) return false
        val c = canalesLista.getOrNull(player.currentMediaItemIndex) ?: return false
        return c.url in favoritos()
    }

    fun cambiarFavoritoActual() {
        if (modo != Modo.CANALES) return
        val c = canalesLista.getOrNull(player.currentMediaItemIndex) ?: return
        val ahora = Datos.cambiarFavorito(ctx, c.url)
        favMem = null
        avisoBreve(if (ahora) "★ ${c.nombre} agregado a Favoritos" else "☆ ${c.nombre} quitado de Favoritos")
        actualizarCapa()
        alCambiar?.invoke()
    }

    private val quitarAvisoBreve = Runnable { ponerAviso("") }
    private fun avisoBreve(t: String) {
        ponerAviso(t)
        main.removeCallbacks(quitarAvisoBreve)
        main.postDelayed(quitarAvisoBreve, 2500)
    }

    // ------------------------------------------------------------------ línea de tiempo
    private fun horaReloj(atrasMs: Long): String {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(System.currentTimeMillis() - atrasMs))
        return if (atrasMs > 50_000) "$hora (−${hms(atrasMs)})" else hora
    }

    /** "Ir a EN VIVO": vuelve al directo (con su colchón de 30 s). */
    fun irAlDirecto() {
        if (modo != Modo.CANALES) return
        player.seekToDefaultPosition()
        player.play()
        actualizarCapa()
    }

    // ------------------------------------------------------------------ búsqueda por voz
    private var reconocedor: SpeechRecognizer? = null
    @Volatile private var grabando = false
    private var vozResultados: List<Canal> = emptyList()
    private var vozTexto = ""
    private var volumenAntes = 1f
    var escuchando = false
        private set

    private val vozTiempo = Runnable {
        if (escuchando) { detenerVoz(); avisoBreve("🎤 No te escuché. Toca 🎤 Voz otra vez") }
    }

    /** Botón 🎤 Voz: escucha el nombre del canal y lo pone (o muestra los parecidos). */
    fun buscarPorVoz() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Registro.anotar(ctx, "   🎤 falta el permiso del micrófono")
            ponerAviso("🎤 Falta activar el micrófono.\nEn el celular: JTV → ⋮ →\n🎤 Activar micrófono del carro")
            main.removeCallbacks(quitarAvisoBreve); main.postDelayed(quitarAvisoBreve, 8000)
            try {
                ctx.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { ok, _ ->
                    if (ok.contains(Manifest.permission.RECORD_AUDIO)) buscarPorVoz()
                }
            } catch (e: Exception) {}
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            avisoBreve("Este celular no tiene reconocimiento de voz")
            vozTexto = ""; vozResultados = emptyList(); panelVista = "voz"; pintarPanel()
            return
        }
        // primero el micrófono del carro; si no se puede, el del celular
        val conCarro = Build.VERSION.SDK_INT >= 33 && (try { ctx.carAppApiLevel >= 5 } catch (e: Exception) { false })
        escuchar(conCarro)
    }

    private fun escuchar(conCarro: Boolean) {
        detenerVoz()
        escuchando = true
        vozTexto = ""
        volumenAntes = player.volume
        player.volume = 0.1f                    // que el micrófono no oiga la TV
        ponerAviso("🎤 Escuchando…\ndi el nombre del canal")
        val sr = try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (e: Exception) { null }
        if (sr == null) { detenerVoz(); avisoBreve("No se pudo usar el micrófono"); return }
        reconocedor = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { ponerAviso("🎤 Buscando…") }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(r: Bundle?) {
                val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotBlank()) ponerAviso("🎤 «$t»")
            }
            override fun onResults(r: Bundle?) {
                val textos = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                detenerVoz()
                procesarVoz(textos)
            }
            override fun onError(error: Int) {
                Registro.anotar(ctx, "   🎤 voz error $error (${if (conCarro) "micrófono del carro" else "micrófono del celular"})")
                detenerVoz()
                if (conCarro && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    escuchar(false)            // el micrófono del carro no funcionó: probar con el del celular
                } else avisoBreve("🎤 No te entendí. Toca 🎤 Voz y di el nombre del canal")
            }
        })
        val pedido = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-EC")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        if (conCarro && Build.VERSION.SDK_INT >= 33) {
            try {
                val rec = CarAudioRecord.create(ctx)
                val tubo = ParcelFileDescriptor.createPipe()
                pedido.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, tubo[0])
                pedido.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                pedido.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                pedido.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE)
                grabando = true
                Thread {
                    val salida = ParcelFileDescriptor.AutoCloseOutputStream(tubo[1])
                    try {
                        rec.startRecording()
                        val buf = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
                        while (grabando) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n <= 0) break
                            salida.write(buf, 0, n)
                        }
                    } catch (e: Exception) {
                    } finally {
                        try { rec.stopRecording() } catch (e: Exception) {}
                        try { salida.close() } catch (e: Exception) {}
                    }
                }.start()
            } catch (e: Exception) {
                Registro.anotar(ctx, "   🎤 micrófono del carro no disponible: ${e.javaClass.simpleName}")
                grabando = false
                main.post { escuchar(false) }
                return
            }
        }
        try { sr.startListening(pedido) } catch (e: Exception) { detenerVoz(); avisoBreve("No se pudo usar el micrófono"); return }
        main.removeCallbacks(vozTiempo)
        main.postDelayed(vozTiempo, 15_000)
        alCambiar?.invoke()
    }

    private fun detenerVoz() {
        main.removeCallbacks(vozTiempo)
        grabando = false
        try { reconocedor?.destroy() } catch (e: Exception) {}
        reconocedor = null
        if (escuchando) { player.volume = volumenAntes; ponerAviso("") }
        escuchando = false
        revisarSalida()
    }

    private val palabrasDeMas = setOf("pon", "ponme", "poner", "pone", "quiero", "ver", "canal", "el", "la", "en", "de", "por", "favor", "abre", "abrir")

    private fun normal(t: String): String {
        val sinTildes = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return sinTildes.replace(Regex("\\(.*?\\)|\\[.*?]"), " ").replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+")).filter { it.isNotEmpty() && it !in setOf("hd", "fhd", "sd", "uhd", "4k") }.joinToString(" ")
    }

    private fun distancia(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    private fun parecido(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        return (100 * (1f - distancia(a, b).toFloat() / maxOf(a.length, b.length))).toInt()
    }

    /** Qué tanto se parece lo dicho al nombre del canal (0 a 100). */
    private fun puntaje(dicho: String, nombre: String): Int {
        val d = normal(dicho).split(" ").filter { it !in palabrasDeMas }.joinToString(" ")
        val n = normal(nombre)
        if (d.isEmpty() || n.isEmpty()) return 0
        val dj = d.replace(" ", ""); val nj = n.replace(" ", "")
        if (dj == nj) return 100
        if (nj.startsWith(dj) && dj.length >= 3) return 90
        if (nj.contains(dj) && dj.length >= 3) return 80
        if (dj.contains(nj) && nj.length >= 3) return 75
        val pd = d.split(" "); val pn = n.split(" ")
        val todas = pd.all { w -> pn.any { it == w || (w.length >= 4 && parecido(w, it) >= 75) } }
        if (todas) return 70
        return parecido(dj, nj)
    }

    private fun procesarVoz(textos: List<String>) {
        vozTexto = textos.firstOrNull().orEmpty()
        Registro.anotar(ctx, "   🎤 dijo: ${textos.joinToString(" | ")}")
        val todos = canales()
        val ranking = todos.map { c -> c to textos.maxOfOrNull { puntaje(it, c.nombre) }.let { it ?: 0 } }
            .filter { it.second >= 60 }.sortedByDescending { it.second }
        vozResultados = ranking.take(20).map { it.first }
        val primero = ranking.getOrNull(0)
        val segundo = ranking.getOrNull(1)
        if (primero != null && primero.second >= 80 && (segundo == null || segundo.second < primero.second)) {
            // uno solo claro: se pone directo
            grupoSel = null
            ponerCanales(todos, todos.indexOf(primero.first))
            avisoBreve("🎤 ${primero.first.nombre}")
            panelVista = "canales"
            if (listaVisible) repintarLista()
        } else {
            // varios parecidos (o ninguno): se muestran para elegir
            panelVista = "voz"
            pintarPanel()
        }
        alCambiar?.invoke()
    }

    /** Se esconde sola a los 12 s sin tocar (si está en pausa se queda). */
    private val ocultarSolo = Runnable { if (player.isPlaying && !escuchando) ocultarPanel() else tocado() }
    private fun tocado() { main.removeCallbacks(ocultarSolo); if (panelVisible) main.postDelayed(ocultarSolo, 12_000); tocarLista() }

    // ------------------------------------------------------------------ lista en franja aparte (izquierda)
    /** La lista de canales va en una franja a la izquierda; el video se corre a la derecha (no se tapa). */
    var listaVisible = false
        private set
    private val aOcultarLista = Runnable { if (escuchando || panelVista == "voz") tocarLista() else ocultarLista() }

    /** Cada vez que se usa la lista: se esconde sola a los 3 s sin usarla. */
    private fun tocarLista() {
        main.removeCallbacks(aOcultarLista)
        if (listaVisible) main.postDelayed(aOcultarLista, 3000)
    }

    /** Botón "Lista": la muestra o la esconde. */
    fun alternarLista() {
        if (listaVisible) { ocultarLista(); return }
        panelVista = if (modo == Modo.PELICULAS) (if (carpetaSel.isNotEmpty()) "carpeta" else "pelis") else "canales"
        pintarPanel()
    }

    private fun mostrarLista() {
        val pn = panel
        if (pn == null) { irA(PantallaMenu(ctx, this)); return }   // forma B: no hay dónde dibujar
        listaVisible = true
        pn.visibility = View.VISIBLE
        ajustarFranja()
        tocarLista()
        revisarSalida()
        alCambiar?.invoke()
    }

    fun ocultarLista() {
        main.removeCallbacks(aOcultarLista)
        if (!listaVisible) return
        listaVisible = false
        usaCursor = false
        panel?.visibility = View.GONE
        ajustarFranja()
        revisarSalida()
        alCambiar?.invoke()
    }

    /** Acomoda la franja, el video y los controles según la lista esté o no. */
    private fun ajustarFranja() {
        if (ancho <= 0 || alto <= 0) return
        val r = areaUsable()
        val pn = panel ?: return
        val w = (r.width() * 0.34f).toInt().coerceAtLeast(dp(pn.context, 230)).coerceAtMost((r.width() * 0.5f).toInt())
        (pn.layoutParams as FrameLayout.LayoutParams).let { lp ->
            lp.width = w
            lp.setMargins(r.left, r.top, 0, maxOf(0, alto - r.bottom))
            pn.layoutParams = lp
        }
        val izq = if (listaVisible) r.left + w else 0
        vista?.let { v ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.setMargins(izq, 0, 0, 0)
            v.layoutParams = lp
        }
        capa?.let { v ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.setMargins(if (listaVisible) izq else r.left, r.top, maxOf(0, ancho - r.right), maxOf(0, alto - r.bottom))
            v.layoutParams = lp
        }
    }

    fun mostrarPanel() {
        val cp = capa
        if (cp == null) { irA(PantallaMenu(ctx, this)); return }   // forma B: no hay dónde dibujar
        if (panelVista == "canales" && modo == Modo.PELICULAS) panelVista = "carpeta"
        if (panelVista == "carpeta" && carpetaSel.isEmpty()) panelVista = "pelis"
        panelVisible = true
        titulo?.visibility = View.GONE
        cp.visibility = View.VISIBLE      // solo los controles; la lista sale con el botón Lista
        tocado()
        main.removeCallbacks(refresco); main.post(refresco)
        revisarSalida()
        alCambiar?.invoke()
    }

    fun ocultarPanel() {
        capa?.visibility = View.GONE
        panelVisible = false
        ocultarLista()
        usaCursor = false
        main.removeCallbacks(ocultarSolo); main.removeCallbacks(refresco)
        revisarSalida()
        alCambiar?.invoke()
    }

    // ------------------------------------------------------------------ video fluido
    // Dibujar el video dentro de la "pantalla intermedia" (para poner la lista encima) hace que se pierdan
    // cuadros. Por eso: si no hay nada encima, el video va DIRECTO a la pantalla del carro (fluido);
    // al tocar (lista, avisos, voz) se pasa a la pantalla intermedia, y al esconderse vuelve a directo.
    private fun hayAlgoEncima(): Boolean =
        enWeb || panelVisible || listaVisible || escuchando || avisoFijo.isNotEmpty() || avisoTemporal.isNotEmpty()

    private val aDirecto = Runnable { usarDirecto() }

    /** Si el paso a directo hizo fallar el video una vez, en esta sesión el video se queda en la forma normal. */
    // v1.27: la salida directa queda apagada. Al tocar la pantalla el video tenía que volver de la salida
    // directa a la pantalla normal (casi medio segundo de espera) y por eso los toques tardaban. Además,
    // con la lista en franja aparte el video tiene que achicarse, y eso solo se puede en la pantalla normal.
    private var directoFallo = true
    /** Hora del último cambio de pantalla (para saber si un error vino de ese cambio). */
    private var ultimoCambio = 0L

    private fun revisarSalida() {
        if (vd == null || directa) return
        if (directoFallo) { main.removeCallbacks(aDirecto); usarPresentacion(); return }
        if (hayAlgoEncima()) { main.removeCallbacks(aDirecto); usarPresentacion() }
        else { main.removeCallbacks(aDirecto); main.postDelayed(aDirecto, 1500) }   // espera un poco: evita saltos
    }

    private fun usarDirecto() {
        val d = vd ?: return
        val s = superficie ?: return
        if (salidaDirecta || directa || directoFallo || hayAlgoEncima()) return
        if (player.playbackState != Player.STATE_READY) {           // en plena carga no se cambia de pantalla
            main.postDelayed(aDirecto, 1500); return
        }
        salidaDirecta = true
        ultimoCambio = System.currentTimeMillis()
        try { d.surface = null } catch (e: Exception) {}              // la pantalla intermedia suelta la del carro…
        // …y el video se conecta solo cuando el carro ya la soltó de verdad (v1.19 lo hacía al instante y
        // el video fallaba con "Unexpected runtime error" a los 2 segundos de cada canal)
        main.postDelayed({
            if (salidaDirecta && vd === d && superficie === s) try {
                player.setVideoSurface(s)
                ultimoCambio = System.currentTimeMillis()
            } catch (e: Exception) {
                Registro.anotar(ctx, "   🖼️ no se pudo pasar a directo: ${e.javaClass.simpleName}")
                directoFallo = true
                usarPresentacion()
            }
        }, 400)
    }

    private fun usarPresentacion() {
        val d = vd ?: return
        val s = superficie ?: return
        val tx = textura ?: return
        if (!salidaDirecta) return
        salidaDirecta = false
        ultimoCambio = System.currentTimeMillis()
        player.setVideoTextureView(tx)       // el video vuelve a la pantalla intermedia…
        main.postDelayed({                   // …y cuando el video ya soltó la pantalla, se conecta
            if (!salidaDirecta && vd === d && superficie === s) try { d.surface = s } catch (e: Exception) {}
        }, 400)
    }

    /**
     * El error "Unexpected runtime error" (FAILED_RUNTIME_CHECK) no es culpa del canal: viene del cambio de pantalla.
     * Se vuelve a la forma normal (con lista encima), se deja de pasar a directo en esta sesión y se reintenta
     * el MISMO canal (antes saltaba de canal en canal).
     */
    private fun arreglarErrorDePantalla(e: PlaybackException): Boolean {
        if (e.errorCode != PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK) return false
        val porCambio = salidaDirecta || System.currentTimeMillis() - ultimoCambio < 10_000
        if (!porCambio && reintentoPantalla) return false
        if (porCambio && !directoFallo) {
            directoFallo = true
            Registro.anotar(ctx, "   🖼️ el paso a directo falló: el video se queda con la forma normal")
        }
        reintentoPantalla = true
        main.removeCallbacks(aDirecto)
        if (salidaDirecta) {
            usarPresentacion()
            main.postDelayed({ player.prepare(); player.play() }, 600)
        } else { player.prepare(); player.play() }
        return true
    }
    private var reintentoPantalla = false

    fun alternarPanel() { if (panelVisible) ocultarPanel() else mostrarPanel() }

    /** Botón "⏻ Apagar": guarda, para el video, el sonido y la grabación, y cierra JTV Video en el carro. */
    fun cerrar() {
        Registro.anotar(ctx, "⏻ JTV Video apagado con el botón Apagar")
        guardarAvance(true)
        if (enWeb) cerrarTikTok()
        ocultarPanel()
        try { player.stop() } catch (e: Exception) {}
        Grabador.detener()
        try { ctx.finishCarApp() } catch (e: Exception) {}
    }

    // ------------------------------------------------------------------ TikTok en el carro
    /** true mientras se ve TikTok (la web de TikTok en la pantalla del carro). */
    var enWeb = false
        private set
    private var web: android.webkit.WebView? = null
    private var capaWeb: FrameLayout? = null
    private var deslizado = 0f
    private var ultimoMovimientoWeb = 0L

    fun abrirTikTok() {
        if (directa || raizVista == null) {
            avisoBreve("TikTok necesita la pantalla normal.\nCierra JTV Video y vuelve a abrirlo."); return
        }
        guardarAvance(true)
        player.pause()
        enWeb = true
        ocultarPanel()
        main.removeCallbacks(aDirecto)
        usarPresentacion()
        armarWeb()
        Registro.anotar(ctx, "🎵 TikTok abierto en el carro")
        alCambiar?.invoke()
    }

    fun cerrarTikTok() {
        if (!enWeb) return
        enWeb = false
        quitarWeb()
        Registro.anotar(ctx, "🎵 TikTok cerrado: vuelve a la TV")
        alCambiar?.invoke()
        revisarSalida()
    }

    private fun armarWeb() {
        val raiz = raizVista ?: return
        if (capaWeb != null) return
        val c = raiz.context
        val cw = FrameLayout(c).apply { setBackgroundColor(Color.BLACK); isClickable = true }
        val w = try { TikTok.crearWeb(c) } catch (e: Exception) {
            Registro.anotar(ctx, "   ⚠ TikTok no pudo abrir el navegador: ${e.javaClass.simpleName}")
            enWeb = false; avisoBreve("No se pudo abrir TikTok"); return
        }
        cw.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // botones grandes al costado: anterior, pausa, siguiente y volver a la TV
        val lado = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8))
            background = Jtv.forma(c, 18f, 14f, intArrayOf(Jtv.conAlfa(Jtv.SUPERFICIE2, 0.80f), Jtv.conAlfa(Jtv.NAVY, 0.80f)), Jtv.LINEA, 1f)
        }
        fun boton(glifo: String, primario: Boolean = false, accion: () -> Unit) {
            lado.addView(Jtv.botonIcono(c, glifo, 54, primario) { accion() },
                LinearLayout.LayoutParams(dp(c, 54), dp(c, 54)).apply { setMargins(0, dp(c, 5), 0, dp(c, 5)) })
        }
        boton("▲") { moverWeb(false) }
        boton("⏯", primario = true) { TikTok.pausa(w) }
        boton("▼") { moverWeb(true) }
        boton("📺") { cerrarTikTok() }
        cw.addView(lado, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(c, 10) })
        // encima del video, debajo de la interfaz de la TV
        val idx = capa?.let { raiz.indexOfChild(it) }?.takeIf { it >= 0 } ?: raiz.childCount
        raiz.addView(cw, idx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = w; capaWeb = cw
        aplicarMovimiento()
    }

    private fun quitarWeb() {
        TikTok.guardarSesion()
        val cw = capaWeb
        val w = web
        capaWeb = null; web = null
        try { (cw?.parent as? ViewGroup)?.removeView(cw) } catch (e: Exception) {}
        try { w?.stopLoading(); w?.destroy() } catch (e: Exception) {}
    }

    /** Siguiente (abajo) o anterior video de TikTok; con un pequeño freno para que no salte dos de golpe. */
    fun moverWeb(abajo: Boolean) {
        val w = web ?: return
        val ahora = SystemClock.uptimeMillis()
        if (ahora - ultimoMovimientoWeb < 700) return
        ultimoMovimientoWeb = ahora
        TikTok.mover(w, abajo)
    }

    fun pausaWeb() { web?.let { TikTok.pausa(it) } }

    /** Deslizar el dedo en el carro: hacia arriba = siguiente video, hacia abajo = anterior. */
    private fun deslizarWeb(dy: Float) {
        deslizado += dy
        val umbral = dp(ctx, 90).toFloat()
        if (deslizado > umbral) { deslizado = 0f; moverWeb(true) }
        else if (deslizado < -umbral) { deslizado = 0f; moverWeb(false) }
    }

    // toques en la pantalla del carro
    override fun onClick(x: Float, y: Float) {
        if (!enWeb && (capa == null || !panelVisible)) { mostrarPanel(); return }
        val raiz = raizVista ?: return
        val t = SystemClock.uptimeMillis()
        val ab = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val ar = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        raiz.dispatchTouchEvent(ab); raiz.dispatchTouchEvent(ar)
        ab.recycle(); ar.recycle()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (enWeb) { deslizarWeb(distanceY); return }
        if (!listaVisible) return
        panelScroll?.scrollBy(0, distanceY.toInt())
        tocado()
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (enWeb) { if (kotlin.math.abs(velocityY) > 800) moverWeb(velocityY < 0); return }
        if (!listaVisible) return
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
            !tienePermisoGps() -> "🔒 Para ver la imagen, acepta en el celular el permiso de ubicación de JTV\n(sirve para tapar la imagen mientras manejas).\nEl sonido sigue."
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
        capaWeb?.visibility = if (tapar) View.INVISIBLE else View.VISIBLE
        pintarAviso()
    }

    /** Activa/desactiva el modo pasajero desde el menú del carro. */
    fun cambiarModoPasajero() {
        val nuevo = !Datos.modoPasajero(ctx)
        Datos.setModoPasajero(ctx, nuevo)
        if (!nuevo) iniciarGps() else aplicarMovimiento()
    }

    /** Forma de imagen automática: si en la forma normal la imagen sale negra, se pasa a la directa. */
    private var formaDirectaSesion = false
    private var revisionesNegras = 0
    private val revisarImagen: Runnable = object : Runnable {
        override fun run() {
            val tx = textura ?: return
            if (directa || formaDirectaSesion) return
            if (salidaDirecta) { main.postDelayed(this, 5000); return }
            // solo se puede revisar si el video está corriendo y la imagen no está tapada
            if (!player.isPlaying || player.videoSize.width <= 0 || vista?.visibility != View.VISIBLE) {
                main.postDelayed(this, 5000); return
            }
            val negra = try {
                val b = tx.getBitmap(32, 18)
                if (b == null) true else {
                    var max = 0
                    for (x in 0 until b.width) for (y in 0 until b.height) {
                        val px = b.getPixel(x, y)
                        max = maxOf(max, Color.red(px) + Color.green(px) + Color.blue(px))
                    }
                    b.recycle()
                    max < 30
                }
            } catch (e: Exception) { false }
            if (!negra) { revisionesNegras = 0; return }      // se ve: listo
            revisionesNegras++
            if (revisionesNegras < 3) { main.postDelayed(this, 4000); return }
            Registro.anotar(ctx, "   🖼️ la imagen sale negra en la forma normal: paso sola a la forma directa")
            formaDirectaSesion = true
            val s = superficie ?: return
            onSurfaceAvailable(SurfaceContainer(s, ancho, alto, dpiCarro))
        }
    }

    fun liberar() {
        guardarAvance(true)
        main.removeCallbacksAndMessages(null)
        try { ctx.getSystemService(LocationManager::class.java).removeUpdates(gps) } catch (e: Exception) {}
        detenerVoz()
        main.removeCallbacks(anotarCuadros)
        liberarPantalla()
        Grabador.cerrar()
        hiloLogos.shutdownNow()
        player.release()
        alCambiar = null
    }
}

/**
 * Igual que la fábrica normal, pero para canales HLS espera mucho más antes de dar el canal por "trabado"
 * cuando no llegan partes nuevas (sin internet sigue con lo grabado en vez de cortarse a los pocos segundos).
 */
class FabricaFuentes(ds: DataSource.Factory) : MediaSource.Factory {
    private val normal = DefaultMediaSourceFactory(ds)
    private val hls = HlsMediaSource.Factory(ds).setPlaylistTrackerFactory { a, b, c -> DefaultHlsPlaylistTracker(a, b, c, 40.0) }

    override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider): MediaSource.Factory {
        normal.setDrmSessionManagerProvider(p); hls.setDrmSessionManagerProvider(p); return this
    }
    override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy): MediaSource.Factory {
        normal.setLoadErrorHandlingPolicy(p); hls.setLoadErrorHandlingPolicy(p); return this
    }
    override fun getSupportedTypes(): IntArray = normal.supportedTypes
    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        if (mediaItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) hls.createMediaSource(mediaItem)
        else normal.createMediaSource(mediaItem)
}

/** Barra de avance (películas) o de colchón (en vivo); en películas se toca para saltar. */
class Barra(c: android.content.Context) : View(c) {
    var avance = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var colorRelleno = Color.WHITE
        set(v) { field = v; invalidate() }
    /** Parte ya guardada (colchón) de la línea de tiempo. */
    var guardado = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var colorGuardado = 0x66FFFFFF
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
        // los colores de la marca (cielo / eléctrico) se pintan con el degradado de JTV
        val deg = android.graphics.LinearGradient(x0, 0f, x1, 0f, intArrayOf(Jtv.CIELO, Jtv.ELECTRICO, Jtv.VIOLETA), null, android.graphics.Shader.TileMode.CLAMP)
        if (guardado > 0f) {
            p.color = colorGuardado
            if (colorGuardado == Jtv.CIELO) p.shader = deg
            cv.drawRoundRect(x0, mitad - grueso / 2, x0 + (x1 - x0) * guardado, mitad + grueso / 2, grueso, grueso, p)
            p.shader = null
        }
        val xf = x0 + (x1 - x0) * avance
        p.color = colorRelleno
        if (colorRelleno == Jtv.ELECTRICO) p.shader = deg
        cv.drawRoundRect(x0, mitad - grueso / 2, xf, mitad + grueso / 2, grueso, grueso, p)
        p.shader = null
        p.color = Jtv.conAlfa(Jtv.CIELO, 0.35f)
        cv.drawCircle(xf, mitad, r * 1.45f, p)
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
        if (tv.enWeb) {
            // TikTok: volver a la TV, anterior, pausa y siguiente (y deslizar el dedo para cambiar de video)
            val sw = ActionStrip.Builder()
                .addAction(Action.Builder().setTitle("📺 TV").setOnClickListener { tv.cerrarTikTok() }.build())
                .addAction(icono(R.drawable.ic_arriba) { tv.moverWeb(false) })
                .addAction(Action.Builder().setTitle("⏯").setOnClickListener { tv.pausaWeb() }.build())
                .addAction(icono(R.drawable.ic_abajo) { tv.moverWeb(true) })
            return NavigationTemplate.Builder().setActionStrip(sw.build())
                .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build()).build()
        }
        val pelis = tv.modo == Modo.PELICULAS
        // ☰ Lista muestra la lista en una franja a la izquierda (el video se corre); ✕ la quita.
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle(if (tv.listaVisible) "✕" else "☰ Lista").setOnClickListener {
                tv.alternarLista()
            }.build())
        if (tv.listaVisible) {
            strip.addAction(Action.Builder().setTitle("🎤 Voz").setOnClickListener { tv.buscarPorVoz() }.build())
            strip.addAction(icono(if (tv.player.playWhenReady) R.drawable.ic_pausa else R.drawable.ic_play) { tv.pausaSigue() })
            if (!pelis) strip.addAction(Action.Builder().setTitle(if (tv.esFavoritoActual()) "★" else "☆")
                .setOnClickListener { tv.cambiarFavoritoActual() }.build())
        } else {
            strip.addAction(icono(if (pelis) R.drawable.ic_atras else R.drawable.ic_anterior) { tv.anterior() })
            strip.addAction(icono(if (tv.player.playWhenReady) R.drawable.ic_pausa else R.drawable.ic_play) { tv.pausaSigue() })
            strip.addAction(icono(if (pelis) R.drawable.ic_adelante else R.drawable.ic_siguiente) { tv.siguiente() })
        }
        // Botones del costado: ▲ ▼ ✓ para moverse por la lista sin tocar la pantalla, y el de "mover",
        // que hace que Android Auto pase los toques y el deslizar del dedo (igual que Fermata Auto).
        val mapa = ActionStrip.Builder()
        if (tv.listaVisible) {
            mapa.addAction(icono(R.drawable.ic_arriba) { tv.moverCursor(-1) })
            mapa.addAction(icono(R.drawable.ic_abajo) { tv.moverCursor(1) })
            mapa.addAction(icono(R.drawable.ic_ok) { tv.elegirCursor() })
        }
        mapa.addAction(Action.PAN)
        return NavigationTemplate.Builder().setActionStrip(strip.build()).setMapActionStrip(mapa.build()).build()
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

class PantallaMenu(ctx: CarContext, tv: VideoCarro) : PantallaFilas(ctx, "JTV", {
    val fav = tv.canales()          // en el carro: solo favoritos
    val l = ArrayList<Fila>()
    l.add(Fila("★ Favoritos", "${fav.size} canales", true) {
        tv.irA(PantallaFilas(ctx, "★ Favoritos") { filasCanales(tv, tv.canales()) })
    })
    l.add(Fila("🎬 Mis Películas", "Videos guardados en el celular", true) { tv.irA(pantallaPeliculas(ctx, tv)) })
    l.add(Fila("🎵 TikTok", "Videos de TikTok en la pantalla del carro") { tv.volverAlVideo(); tv.abrirTikTok() })
    l.add(Fila("🔍 Buscar canal") { tv.irA(PantallaBuscar(ctx, tv)) })
    val pasajero = Datos.modoPasajero(ctx)
    l.add(Fila(
        if (pasajero) "👥 Modo pasajero: ACTIVADO" else "👥 Modo pasajero: desactivado",
        if (pasajero) "La imagen se ve con el carro en marcha. Toca para taparla al avanzar"
        else "La imagen se tapa al avanzar. Toca para verla siempre"
    ) { tv.cambiarModoPasajero(); tv.volverAlVideo() })
    l.add(Fila("🔧 Diagnóstico", tv.diagnostico()) { tv.volverAlVideo() })
    l
})

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

/** Si falta la app JTV (de ahí salen la lista y los favoritos). */
class SinTvJat(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template =
        androidx.car.app.model.MessageTemplate.Builder(
            "Falta la app JTV en el celular (o hay que actualizarla).\nInstala JTV, ábrela una vez y carga una lista de canales."
        ).setTitle("JTV Video").build()
}
