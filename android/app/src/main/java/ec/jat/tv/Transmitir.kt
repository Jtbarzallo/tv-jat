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
import com.google.android.gms.cast.CastMediaControlIntent
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
    fun enviar(
        ctx: Context, url: String, titulo: String, subtitulo: String, imagen: String,
        tipo: String, enVivo: Boolean, desdeMs: Long = 0L, fallo: (String) -> Unit
    ): Boolean {
        val rmc = sesion(ctx)?.remoteMediaClient ?: return false
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, titulo)
            if (subtitulo.isNotEmpty()) putString(MediaMetadata.KEY_SUBTITLE, subtitulo)
            if (imagen.startsWith("http")) addImage(WebImage(Uri.parse(imagen)))
        }
        val info = MediaInfo.Builder(url)
            .setContentUrl(url)
            .setStreamType(if (enVivo) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(tipo)
            .setMetadata(meta)
            .build()
        val pedido = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(desdeMs)
            .build()
        try {
            rmc.load(pedido).setResultCallback { r ->
                if (!r.status.isSuccess) fallo("La TV no pudo abrir \"$titulo\".")
            }
        } catch (e: Throwable) {
            fallo("No se pudo enviar a la TV.")
            return false
        }
        return true
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
        background = GradientDrawable().apply { setColor(0xFF2A3156.toInt()); cornerRadius = 14 * d }
        setOnClickListener { alTocar() }
    }
}
