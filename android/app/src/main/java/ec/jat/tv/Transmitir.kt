package ec.jat.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.mediarouter.media.MediaRouter
import android.os.Handler
import android.os.Looper
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.common.images.WebImage

/** Opciones de Google Cast (las pide Android al arrancar la transmisión). */
class CastOpciones : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notif = NotificationOptions.Builder()
            .setTargetActivityClassName(MainActivity::class.java.name)
            .build()
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(notif)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .setResumeSavedSession(false)
            .build()
    }
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** Escucha de la sesión de transmisión con todo vacío (solo se rellena lo que hace falta). */
open class OyenteTv : SessionManagerListener<CastSession> {
    override fun onSessionStarting(s: CastSession) {}
    override fun onSessionStarted(s: CastSession, sessionId: String) {}
    override fun onSessionStartFailed(s: CastSession, error: Int) {}
    override fun onSessionEnding(s: CastSession) {}
    override fun onSessionEnded(s: CastSession, error: Int) {}
    override fun onSessionResuming(s: CastSession, sessionId: String) {}
    override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {}
    override fun onSessionResumeFailed(s: CastSession, error: Int) {}
    override fun onSessionSuspended(s: CastSession, reason: Int) {}
}

/**
 * Transmitir a la TV (Android TV / Chromecast) con Google Cast.
 * El celular solo le pasa la DIRECCIÓN del canal a la TV y la TV lo descarga sola:
 * el celular no descarga ni muestra el video, así casi no gasta batería ni datos.
 */
object Transmitir {

    /** En la propia TV no tiene sentido el botón de transmitir. */
    fun esTv(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun contexto(ctx: Context): CastContext? = try {
        @Suppress("DEPRECATION")
        CastContext.getSharedInstance(ctx.applicationContext)
    } catch (e: Throwable) { null }

    fun sesion(ctx: Context): CastSession? = try {
        contexto(ctx)?.sessionManager?.currentCastSession?.takeIf { it.isConnected }
    } catch (e: Throwable) { null }

    fun nombreTv(ctx: Context): String = sesion(ctx)?.castDevice?.friendlyName ?: "la TV"

    fun escuchar(ctx: Context, o: OyenteTv) {
        try { contexto(ctx)?.sessionManager?.addSessionManagerListener(o, CastSession::class.java) } catch (e: Throwable) {}
    }

    fun dejarDeEscuchar(ctx: Context, o: OyenteTv) {
        try { contexto(ctx)?.sessionManager?.removeSessionManagerListener(o, CastSession::class.java) } catch (e: Throwable) {}
    }

    fun detener(ctx: Context) {
        try { contexto(ctx)?.sessionManager?.endCurrentSession(true) } catch (e: Throwable) {}
        ServidorPeliculas.detener(ctx)
    }

    /** Muestra las TV de la casa; al elegir una (y conectarse) llama a [listo]. */
    fun elegirTv(act: Activity, listo: () -> Unit) {
        val cast = contexto(act)
        if (cast == null) {
            Toast.makeText(act, "Este celular no tiene Google Play Services para transmitir.", Toast.LENGTH_LONG).show()
            return
        }
        // ya está conectado a una TV
        sesion(act)?.let {
            AlertDialog.Builder(act)
                .setTitle("📡 Conectado a ${nombreTv(act)}")
                .setPositiveButton("Enviar aquí") { _, _ -> listo() }
                .setNegativeButton("Desconectar") { _, _ -> detener(act) }
                .setNeutralButton("Cancelar", null)
                .show()
            return
        }
        val selector = cast.mergedSelector
        if (selector == null) {
            Toast.makeText(act, "No se pudo buscar televisores.", Toast.LENGTH_LONG).show()
            return
        }
        val router = MediaRouter.getInstance(act)
        val rutas = ArrayList<MediaRouter.RouteInfo>()
        val adaptador = ArrayAdapter<String>(act, android.R.layout.simple_list_item_1, ArrayList())

        fun refrescar() {
            rutas.clear()
            rutas.addAll(router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) })
            adaptador.clear()
            adaptador.addAll(rutas.map { "📺 " + it.name })
            adaptador.notifyDataSetChanged()
        }

        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refrescar()
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refrescar()
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refrescar()
        }
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refrescar()

        val dialogo = AlertDialog.Builder(act)
            .setTitle("📡 Ver en la TV · buscando en tu WiFi…")
            .setAdapter(adaptador) { _, pos ->
                val ruta = rutas.getOrNull(pos) ?: return@setAdapter
                val oyente = object : OyenteTv() {
                    override fun onSessionStarted(s: CastSession, sessionId: String) {
                        dejarDeEscuchar(act, this); listo()
                    }
                    override fun onSessionStartFailed(s: CastSession, error: Int) {
                        dejarDeEscuchar(act, this)
                        Toast.makeText(act, "No se pudo conectar con la TV. Intenta otra vez.", Toast.LENGTH_LONG).show()
                    }
                }
                escuchar(act, oyente)
                Toast.makeText(act, "Conectando con ${ruta.name}…", Toast.LENGTH_SHORT).show()
                router.selectRoute(ruta)
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dialogo.setOnDismissListener { router.removeCallback(callback) }
        dialogo.show()
        // si después de un rato no aparece ninguna TV, se avisa por qué puede ser
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (dialogo.isShowing && rutas.isEmpty())
                Toast.makeText(act, "No aparece ninguna TV. Revisa que la TV esté encendida y en el mismo WiFi que el celular.", Toast.LENGTH_LONG).show()
        }, 9000)
    }

    /** Tipo de transmisión según la dirección del canal. */
    fun tipoDe(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains(".mpd") -> "application/dash+xml"
            u.contains(".mp4") -> "video/mp4"
            else -> "application/x-mpegURL"   // casi todos los canales M3U son HLS
        }
    }

    /** Manda un video a la TV. [fallo] se llama si la TV no lo pudo abrir. */
    /**
     * Revisa por dentro un canal HLS para decirle a la TV qué tipo de video trae.
     * Sin esto, Google Cast supone otro formato y en muchos canales solo se oye el audio (sin imagen).
     * Devuelve "ts" (lo más común en canales M3U), "fmp4", o null si no se pudo saber.
     */
    private fun formatoHls(url: String, ua: String, referer: String): String? {
        fun bajar(u: String): String? = try {
            val con = URL(u).openConnection() as HttpURLConnection
            con.connectTimeout = 8000; con.readTimeout = 10000
            con.instanceFollowRedirects = true
            con.setRequestProperty("User-Agent", ua.ifEmpty { Reproduccion.USER_AGENT })
            if (referer.isNotEmpty()) con.setRequestProperty("Referer", referer)
            if (con.responseCode in 200..299) con.inputStream.bufferedReader().use { r ->
                val sb = StringBuilder(); var n = 0
                while (n < 400) { val l = r.readLine() ?: break; sb.append(l).append('\n'); n++ }
                sb.toString()
            } else null
        } catch (e: Throwable) { null }

        var actual = url
        var texto = bajar(actual) ?: return null
        // lista principal: entrar a la primera calidad
        if (texto.contains("#EXT-X-STREAM-INF")) {
            val lineas = texto.lines().map { it.trim() }
            val i = lineas.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            val variante = lineas.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return "ts"
            actual = URL(URL(actual), variante).toString()
            texto = bajar(actual) ?: return "ts"
        }
        if (texto.contains("#EXT-X-MAP")) return "fmp4"
        val segmento = texto.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.lowercase() ?: return "ts"
        val ruta = segmento.substringBefore('?')
        return if (ruta.endsWith(".m4s") || ruta.endsWith(".mp4") || ruta.endsWith(".m4v") || ruta.endsWith(".cmfv")) "fmp4" else "ts"
    }

    /** Manda un video a la TV. [fallo] se llama si la TV no lo pudo abrir. */
    fun enviar(
        ctx: Context, url: String, titulo: String, subtitulo: String, imagen: String,
        tipo: String, enVivo: Boolean, desdeMs: Long = 0L,
        ua: String = "", referer: String = "", fallo: (String) -> Unit
    ): Boolean {
        if (sesion(ctx)?.remoteMediaClient == null) return false
        val principal = Handler(Looper.getMainLooper())
        thread(isDaemon = true) {
            val formato = if (tipo == "application/x-mpegURL") formatoHls(url, ua, referer) else null
            principal.post { cargar(ctx, url, titulo, subtitulo, imagen, tipo, enVivo, desdeMs, formato, fallo) }
        }
        return true
    }

    private fun cargar(
        ctx: Context, url: String, titulo: String, subtitulo: String, imagen: String,
        tipo: String, enVivo: Boolean, desdeMs: Long, formato: String?, fallo: (String) -> Unit
    ) {
        val rmc = sesion(ctx)?.remoteMediaClient ?: return
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, titulo)
            if (subtitulo.isNotEmpty()) putString(MediaMetadata.KEY_SUBTITLE, subtitulo)
            if (imagen.startsWith("http")) addImage(WebImage(Uri.parse(imagen)))
        }
        val b = MediaInfo.Builder(url)
            .setContentUrl(url)
            .setStreamType(if (enVivo) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(tipo)
            .setMetadata(meta)
        // decirle a la TV el tipo de video y audio del canal (si no, a veces solo suena el audio)
        when (formato) {
            "ts" -> b.setHlsSegmentFormat(HlsSegmentFormat.TS).setHlsVideoSegmentFormat(HlsVideoSegmentFormat.MPEG2_TS)
            "fmp4" -> b.setHlsSegmentFormat(HlsSegmentFormat.FMP4).setHlsVideoSegmentFormat(HlsVideoSegmentFormat.FMP4)
        }
        val pedido = MediaLoadRequestData.Builder()
            .setMediaInfo(b.build())
            .setAutoplay(true)
            .setCurrentTime(desdeMs)
            .build()
        try {
            rmc.load(pedido).setResultCallback { r ->
                if (!r.status.isSuccess) fallo("La TV no pudo abrir \"$titulo\".")
            }
        } catch (e: Throwable) {
            fallo("No se pudo enviar a la TV.")
        }
    }

    fun pausarOSeguir(ctx: Context) {
        try { sesion(ctx)?.remoteMediaClient?.togglePlayback() } catch (e: Throwable) {}
    }

    fun posicionTv(ctx: Context): Long = try {
        sesion(ctx)?.remoteMediaClient?.approximateStreamPosition ?: -1L
    } catch (e: Throwable) { -1L }

    /** Botón redondo oscuro para la capa "Viendo en la TV". */
    fun boton(act: Activity, texto: String, alTocar: () -> Unit): TextView = TextView(act).apply {
        val d = act.resources.displayMetrics.density
        text = texto
        setTextColor(Color.WHITE)
        textSize = 17f
        setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
        typeface = Jtv.titulo(act)
        background = Jtv.forma(act, 14f, 10f, intArrayOf(Jtv.conAlfa(Jtv.SUPERFICIE2, 0.9f), Jtv.conAlfa(Jtv.SUPERFICIE, 0.9f)), Jtv.LINEA, 1.2f)
        setOnClickListener { alTocar() }
        Jtv.vivo(this)
    }
}
