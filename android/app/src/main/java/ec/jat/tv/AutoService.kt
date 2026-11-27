package ec.jat.tv

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * JTV en Android Auto.
 * Android Auto no deja ver video en la pantalla del carro (solo apps de audio), así que aquí los
 * canales se ESCUCHAN como si fueran radio: se apaga la imagen y se baja solo el sonido
 * (además así gasta muchos menos datos).
 * En el carro solo salen los canales FAVORITOS (los marcados con ★ en el celular); el resto no se muestra.
 */
class AutoService : MediaLibraryService() {

    companion object {
        private var instancia: AutoService? = null
        /** Si suena en el carro y se abre un canal con imagen en el celular, se calla el del carro. */
        fun pausarSiSuena() {
            val s = instancia ?: return
            if (s::player.isInitialized && s.player.isPlaying) s.player.pause()
        }

        private const val RAIZ = "raiz"
        private const val FAVORITOS = "fav"
        private const val TODOS = "todos"
        private const val GRUPO = "g:"
        private const val CANAL = "canal\u0001"   // canal\u0001<contexto>\u0001<url>
        private const val SEP = '\u0001'
    }

    private var sesion: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val main = Handler(Looper.getMainLooper())
    private val probadosHls = HashSet<String>()
    private var errorSeguidos = 0
    @Volatile private var canalActual: Canal? = null
    private var listaActual: List<Canal> = emptyList()

    // --------------------------------------------------------------- canales (con memoria)
    private var cacheMarca = -1L
    private var cache: List<Canal> = emptyList()

    /** Canales de la lista actual, sin los que no funcionan (si están ocultos en la app). */
    private fun canales(): List<Canal> {
        val f = File(filesDir, "lista.m3u")
        val marca = if (f.exists()) f.lastModified() xor f.length() else 0L
        val caidos = Datos.caidos(this)
        val ocultar = Datos.ocultarCaidos(this)
        if (marca != cacheMarca) {
            cache = Datos.textoLista(this)?.let { M3u.leer(it) } ?: emptyList()
            cacheMarca = marca
        }
        return if (ocultar) cache.filter { it.url !in caidos } else cache
    }

    private fun grupoDe(c: Canal) = c.grupo.ifBlank { "Otros" }


    /** Lista de canales de un menú (contexto). */
    private fun contexto(ctx: String): List<Canal> {
        // en el carro todo sale solo de los favoritos
        val todos = Datos.favoritos(this).let { f -> canales().filter { it.url in f } }
        return when {
            ctx == FAVORITOS -> todos
            ctx == TODOS -> todos
            ctx.startsWith(GRUPO) -> { val g = ctx.removePrefix(GRUPO); todos.filter { grupoDe(it) == g } }
            else -> emptyList()
        }
    }

    // --------------------------------------------------------------- elementos del menú
    private fun carpeta(id: String, titulo: String, sub: String = ""): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(titulo).setSubtitle(sub.ifEmpty { null })
                .setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS).build()
        ).build()

    private fun itemCanal(c: Canal, ctx: String): MediaItem {
        val b = MediaItem.Builder()
            .setMediaId("$CANAL$ctx$SEP${c.url}")
            .setUri(Uri.parse(c.url))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(c.nombre)
                    .setArtist(grupoDe(c))
                    .setStation(c.nombre)
                    .setArtworkUri(LogoProvider.uri(c.logo, c.nombre))
                    .setIsBrowsable(false).setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION).build()
            )
        val u = c.url.lowercase()
        when {
            u.contains("m3u8") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            u.contains(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return b.build()
    }

    private fun raiz(): MediaItem = carpeta(RAIZ, "JTV")

    private fun hijos(id: String): List<MediaItem> = when {
        id == RAIZ -> {
            // solo favoritos, directo en la primera pantalla
            val fav = contexto(FAVORITOS)
            if (fav.isEmpty()) listOf(carpeta(FAVORITOS, "No tienes canales favoritos",
                "En JTV del celular toca la ★ de los canales que quieras aquí"))
            else fav.map { itemCanal(it, FAVORITOS) }
        }
        id == FAVORITOS || id == TODOS || id.startsWith(GRUPO) -> contexto(id).map { itemCanal(it, id) }
        else -> emptyList()
    }

    private fun item(id: String): MediaItem? = when {
        id == RAIZ -> raiz()
        id == FAVORITOS -> carpeta(FAVORITOS, "★ Favoritos")
        id == TODOS -> carpeta(TODOS, "📺 Todos los canales")
        id.startsWith(GRUPO) -> carpeta(id, id.removePrefix(GRUPO))
        id.startsWith(CANAL) -> resolver(id)?.let { it.first[it.second] }
        else -> null
    }

    /** De un canal elegido saca la lista completa del menú y la posición, para poder pasar al siguiente. */
    private fun resolver(id: String): Pair<List<MediaItem>, Int>? {
        if (!id.startsWith(CANAL)) return null
        val resto = id.removePrefix(CANAL)
        val ctx = resto.substringBefore(SEP)
        val url = resto.substringAfter(SEP)
        var lista = contexto(ctx)
        var ctxUsado = ctx
        if (lista.none { it.url == url }) { lista = contexto(FAVORITOS); ctxUsado = FAVORITOS }
        val i = lista.indexOfFirst { it.url == url }
        if (i < 0) return null
        listaActual = lista
        return lista.map { itemCanal(it, ctxUsado) } to i
    }

    private fun buscar(q: String): List<MediaItem> {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        return contexto(FAVORITOS).filter { it.nombre.lowercase().contains(t) || it.grupo.lowercase().contains(t) }
            .take(100).map { itemCanal(it, FAVORITOS) }
    }

    // --------------------------------------------------------------- reproductor
    override fun onCreate() {
        super.onCreate()
        instancia = this
        Registro.instalar(this)
        Registro.anotar(this, "🚗 Servicio de audio (JTV) iniciado. Canales en la lista: ${canales().size} · favoritos en el carro: ${contexto(FAVORITOS).size}")
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Reproduccion.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val conCabeceras = ResolvingDataSource.Factory(http) { spec ->
            val c = canalActual
            val extra = HashMap<String, String>()
            if (c != null) {
                if (c.userAgent.isNotEmpty()) extra["User-Agent"] = c.userAgent
                if (c.referer.isNotEmpty()) extra["Referer"] = c.referer
            }
            if (extra.isEmpty()) spec else spec.withAdditionalHeaders(extra)
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, conCabeceras)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Solo sonido: sin imagen (en el carro no se puede ver y así ahorra datos)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val url = mediaItem?.mediaId?.substringAfterLast(SEP) ?: return
                canalActual = listaActual.firstOrNull { it.url == url } ?: canales().firstOrNull { it.url == url }
                Datos.guardarUltimo(this@AutoService, url)
                Registro.anotar(this@AutoService, "▶ Canal: ${canalActual?.nombre ?: url}  ($url)")
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) errorSeguidos = 0
                val t = when (state) { Player.STATE_BUFFERING -> "cargando"; Player.STATE_READY -> "listo (debe sonar)"; Player.STATE_ENDED -> "terminó"; else -> "detenido" }
                Registro.anotar(this@AutoService, "   estado: $t")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Registro.anotar(this@AutoService, if (isPlaying) "   🔊 sonando" else "   ⏸ sin sonar")
            }

            override fun onPlayerError(e: PlaybackException) {
                Registro.anotar(this@AutoService, "   ⚠ error: ${e.errorCodeName} · ${e.cause?.message ?: e.message}")
                val idx = player.currentMediaItemIndex
                val actual = player.currentMediaItem
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                // la dirección no dice el formato: probar como transmisión en vivo (HLS)
                if (actual != null && actual.localConfiguration?.mimeType == null && probadosHls.add(actual.mediaId)) {
                    player.replaceMediaItem(idx, actual.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build())
                    player.prepare(); player.play(); return
                }
                // canal caído: pasa solo al siguiente (máximo 10 seguidos para no gastar datos sin fin)
                errorSeguidos++
                if (player.hasNextMediaItem() && errorSeguidos <= 10) {
                    main.postDelayed({
                        if (player.currentMediaItemIndex == idx) {
                            player.seekToNextMediaItem(); player.prepare(); player.play()
                        }
                    }, 1500)
                }
            }
        })

        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        sesion = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(abrir)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = sesion

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = sesion?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        if (instancia === this) instancia = null
        sesion?.run { player.release(); release() }
        sesion = null
        super.onDestroy()
    }

    private fun pagina(todos: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(todos)
        val desde = page.toLong() * pageSize
        if (desde >= todos.size) return ImmutableList.of()
        val hasta = minOf(todos.size.toLong(), desde + pageSize).toInt()
        return ImmutableList.copyOf(todos.subList(desde.toInt(), hasta))
    }

    /** Pide a Android Auto mostrar los menús como lista y los canales como cuadritos con su logo. */
    private fun estilo(): LibraryParams = LibraryParams.Builder().setExtras(Bundle().apply {
        putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
        putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
        putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)   // lista: nombres completos y grandes
        putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
    }).build()

    private inner class Callback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Registro.anotar(this@AutoService, "📡 Conectó: ${browser.packageName}")
            return Futures.immediateFuture(LibraryResult.ofItem(raiz(), estilo()))
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val encontrado = item(mediaId)
            return Futures.immediateFuture(
                if (encontrado != null) LibraryResult.ofItem(encontrado, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(pagina(hijos(parentId), page, pageSize), params))

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, buscar(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(pagina(buscar(query), page, pageSize), params))

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val salida = mediaItems.mapNotNull { mi ->
                resolver(mi.mediaId)?.let { it.first[it.second] } ?: mi.takeIf { it.localConfiguration != null }
            }.toMutableList()
            return Futures.immediateFuture(salida)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            probadosHls.clear(); errorSeguidos = 0
            Registro.anotar(this@AutoService, "👆 ${controller.packageName} pidió: " + mediaItems.joinToString { it.mediaId.substringAfterLast(SEP).ifEmpty { it.requestMetadata.searchQuery ?: "?" } })
            if (mediaItems.size == 1) {
                // "Ok Google, pon Ecuavisa en JTV"
                val consulta = mediaItems[0].requestMetadata.searchQuery
                if (consulta != null) {
                    var lista = buscar(consulta)
                    if (lista.isEmpty()) lista = contexto(FAVORITOS).map { itemCanal(it, FAVORITOS) }
                    listaActual = contexto(FAVORITOS)
                    return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(lista, 0, C.TIME_UNSET))
                }
                val r = resolver(mediaItems[0].mediaId)
                if (r == null) Registro.anotar(this@AutoService, "   ⚠ no encontré ese canal en la lista")
                if (r != null) return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(r.first, r.second, C.TIME_UNSET))
            }
            val salida = mediaItems.mapNotNull { mi ->
                resolver(mi.mediaId)?.let { it.first[it.second] } ?: mi.takeIf { it.localConfiguration != null }
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(salida, startIndex.coerceIn(0, maxOf(0, salida.size - 1)), C.TIME_UNSET)
            )
        }

        /** Al subirse al carro y tocar "play": vuelve al último canal que vio. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val ultimo = Datos.ultimo(this@AutoService)
            val r = resolver("$CANAL$FAVORITOS$SEP$ultimo")
                ?: contexto(FAVORITOS).firstOrNull()?.let { resolver("$CANAL$FAVORITOS$SEP${it.url}") }
            return Futures.immediateFuture(
                if (r == null) MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
                else MediaSession.MediaItemsWithStartPosition(r.first, r.second, C.TIME_UNSET)
            )
        }
    }
}
