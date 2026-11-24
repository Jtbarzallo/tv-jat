package ec.jat.tv

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject

/**
 * TV JAT Video no guarda datos propios: todo (lista, favoritos, último canal, avance de películas,
 * opciones) vive en la app TV JAT, que ya hace el respaldo en Descargas/TVJAT. Esto lo pide y lo guarda allá.
 */
object Puente {
    private const val AUTORIDAD = "ec.jat.tv.datos"
    val LISTA: Uri = Uri.parse("content://$AUTORIDAD/lista")
    private val URI: Uri = Uri.parse("content://$AUTORIDAD")

    fun llamar(ctx: Context, metodo: String, arg: String? = null, extras: Bundle? = null): Bundle? = try {
        ctx.contentResolver.call(URI, metodo, arg, extras)
    } catch (e: Exception) { null }

    private var estadoCache: Bundle? = null
    private var estadoHora = 0L

    /** Estado de TV JAT (se guarda 1,5 s para no preguntar mil veces seguidas). */
    fun estado(ctx: Context): Bundle {
        val ahora = SystemClock.elapsedRealtime()
        val c = estadoCache
        if (c != null && ahora - estadoHora < 1500) return c
        val b = llamar(ctx, "estado") ?: Bundle()
        estadoCache = b; estadoHora = ahora
        return b
    }

    fun olvidar() { estadoHora = 0 }
    fun hayTvJat(ctx: Context) = llamar(ctx, "estado") != null
}

/** Mismos nombres que en TV JAT, pero los datos vienen por el Puente. */
object Datos {
    fun textoLista(ctx: Context): String? = try {
        ctx.contentResolver.openInputStream(Puente.LISTA)?.use { it.bufferedReader().readText() }
    } catch (e: Exception) { null }

    fun marcaLista(ctx: Context): Long = Puente.estado(ctx).getLong("marcaLista", 0L)
    fun favoritos(ctx: Context): Set<String> = Puente.estado(ctx).getStringArrayList("favoritos")?.toSet() ?: emptySet()
    fun caidos(ctx: Context): Set<String> = Puente.estado(ctx).getStringArrayList("caidos")?.toSet() ?: emptySet()
    fun ocultarCaidos(ctx: Context) = Puente.estado(ctx).getBoolean("ocultarCaidos", true)
    fun ultimo(ctx: Context): String = Puente.estado(ctx).getString("ultimo") ?: ""
    fun modoPasajero(ctx: Context) = Puente.estado(ctx).getBoolean("modoPasajero", true)
    fun formaImagen(ctx: Context): String = Puente.estado(ctx).getString("formaImagen") ?: "A"

    fun guardarUltimo(ctx: Context, url: String) { Puente.llamar(ctx, "guardarUltimo", url); Puente.olvidar() }
    fun setModoPasajero(ctx: Context, v: Boolean) { Puente.llamar(ctx, "modoPasajero", if (v) "1" else "0"); Puente.olvidar() }
    fun setFormaImagen(ctx: Context, v: String) { Puente.llamar(ctx, "formaImagen", v); Puente.olvidar() }
    fun progreso(ctx: Context): JSONObject = try { JSONObject(Puente.estado(ctx).getString("progreso") ?: "{}") } catch (e: Exception) { JSONObject() }
}

object Reproduccion {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
}

/** El diagnóstico se anota en TV JAT (⋮ → 🔧 Diagnóstico Android Auto). */
object Registro {
    private var instalado = false
    private var app: Context? = null
    fun anotar(ctx: Context, texto: String) { Puente.llamar(ctx, "anotar", texto) }
    fun instalar(ctx: Context) {
        if (instalado) return
        instalado = true
        app = ctx.applicationContext
        val previo = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, e ->
            val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
            app?.let { anotar(it, "❌ TV JAT VIDEO SE CERRÓ: " + sw.toString().lines().take(25).joinToString("\n    ")) }
            previo?.uncaughtException(hilo, e)
        }
    }
}

/** Pide a TV JAT que calle el modo radio si está sonando (para que no suenen los dos). */
object AutoService {
    var ctx: Context? = null
    fun pausarSiSuena() { ctx?.let { Puente.llamar(it, "pausarRadio") } }
}
