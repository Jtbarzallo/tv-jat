package ec.jat.tv

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import org.json.JSONObject
import java.util.concurrent.Executors

/** Un video guardado en el celular. */
data class Pelicula(
    val id: Long,
    val uri: Uri,
    val archivo: String,
    val titulo: String,
    val carpeta: String,
    val duracionMs: Long,
    val tamano: Long,
    val fechaAgregada: Long
) {
    /** Clave que no cambia aunque se reinstale la app (nombre + tamaño). */
    val clave: String get() = "$archivo|$tamano"
}

object Peliculas {

    // ---------- buscar videos en el celular ----------
    fun buscar(ctx: Context): List<Pelicula> {
        val lista = ArrayList<Pelicula>()
        val coleccion = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val columnas = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )
        ctx.contentResolver.query(coleccion, columnas, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val nombre = c.getString(1) ?: continue
                val duracion = c.getLong(3)
                if (duracion in 1 until 60_000) continue   // se saltan clips de menos de 1 minuto
                lista.add(Pelicula(
                    id = id,
                    uri = ContentUris.withAppendedId(coleccion, id),
                    archivo = nombre,
                    titulo = limpiarTitulo(nombre),
                    carpeta = c.getString(2) ?: "Otros",
                    duracionMs = duracion,
                    tamano = c.getLong(4),
                    fechaAgregada = c.getLong(5)
                ))
            }
        }
        return lista
    }

    private val basura = Regex(
        """\b(480p|720p|1080p|2160p|4k|uhd|hdr|x264|x265|h264|h265|hevc|xvid|web-?dl|webrip|web|bluray|blu-ray|brrip|bdrip|hdrip|dvdrip|hdtv|cam|ts|aac|ac3|dts|5\.1|dual|multi|latino|castellano|español|subtitulado|sub|yts|rarbg)\b.*""",
        RegexOption.IGNORE_CASE
    )

    /** "El.Padrino.1972.1080p.Latino.mkv" → "El Padrino (1972)" */
    fun limpiarTitulo(archivo: String): String {
        var t = archivo.substringBeforeLast('.')
        t = t.replace(Regex("""[\[{].*?[\]}]"""), " ")
        t = t.replace('.', ' ').replace('_', ' ')
        t = basura.replace(t, "")
        val anio = Regex("""\(?\b(19[3-9]\d|20[0-4]\d)\b\)?""").find(t)
        if (anio != null && anio.range.first > 0) {
            t = t.substring(0, anio.range.first).trim() + " (" + anio.groupValues[1] + ")"
        }
        t = t.replace(Regex("""\s+"""), " ").trim().trim('-', ' ')
        return t.ifEmpty { archivo.substringBeforeLast('.') }
    }

    fun duracionTexto(ms: Long): String {
        val min = ms / 60000
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    // ---------- miniaturas ----------
    private val memoria = LruCache<Long, Bitmap>(120)
    private val hilos = Executors.newFixedThreadPool(3)
    private val principal = Handler(Looper.getMainLooper())

    fun miniatura(ctx: Context, vista: ImageView, p: Pelicula) {
        vista.tag = p.id
        val enMemoria = memoria.get(p.id)
        if (enMemoria != null) { vista.setImageBitmap(enMemoria); return }
        vista.setImageDrawable(null)
        hilos.execute {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(p.uri, Size(480, 270), null)
                else MediaStore.Video.Thumbnails.getThumbnail(
                    ctx.contentResolver, p.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            } catch (e: Throwable) { null }
            if (bmp != null) principal.post {
                memoria.put(p.id, bmp)
                if (vista.tag == p.id) vista.setImageBitmap(bmp)
            }
        }
    }

    // ---------- en qué minuto quedó cada película ----------
    private const val PREFS = "tvjat"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun progresoJson(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString("progresoPeliculas", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    /** Posición guardada en ms (0 si no se ha visto o si ya terminó). */
    fun posicion(ctx: Context, p: Pelicula): Long {
        val o = progresoJson(ctx).optJSONObject(p.clave) ?: return 0
        return if (o.optBoolean("vista")) 0 else o.optLong("pos")
    }

    fun vista(ctx: Context, p: Pelicula): Boolean =
        progresoJson(ctx).optJSONObject(p.clave)?.optBoolean("vista") == true

    /** 0..1 cuánto se ha visto. */
    fun avance(ctx: Context, p: Pelicula): Float {
        val o = progresoJson(ctx).optJSONObject(p.clave) ?: return 0f
        if (o.optBoolean("vista")) return 1f
        val dur = if (p.duracionMs > 0) p.duracionMs else o.optLong("dur")
        return if (dur > 0) (o.optLong("pos").toFloat() / dur).coerceIn(0f, 1f) else 0f
    }

    fun ultimaVez(ctx: Context, p: Pelicula): Long = progresoJson(ctx).optJSONObject(p.clave)?.optLong("t") ?: 0

    private var ultimoRespaldo = 0L

    fun guardarPosicion(ctx: Context, p: Pelicula, pos: Long, dur: Long, forzarRespaldo: Boolean = false) {
        val todo = progresoJson(ctx)
        val terminada = dur > 0 && pos >= dur * 0.95
        todo.put(p.clave, JSONObject()
            .put("pos", if (terminada) 0 else pos)
            .put("dur", dur)
            .put("vista", terminada)
            .put("t", System.currentTimeMillis())
            .put("titulo", p.titulo))
        prefs(ctx).edit().putString("progresoPeliculas", todo.toString()).apply()
        val ahora = System.currentTimeMillis()
        if (forzarRespaldo || ahora - ultimoRespaldo > 60_000) {
            ultimoRespaldo = ahora
            Datos.guardarRespaldo(ctx)
        }
    }

    fun marcar(ctx: Context, p: Pelicula, vista: Boolean) {
        val todo = progresoJson(ctx)
        if (vista) todo.put(p.clave, JSONObject().put("pos", 0).put("dur", p.duracionMs)
            .put("vista", true).put("t", System.currentTimeMillis()).put("titulo", p.titulo))
        else todo.remove(p.clave)
        prefs(ctx).edit().putString("progresoPeliculas", todo.toString()).apply()
        Datos.guardarRespaldo(ctx)
    }

    /** Lo guarda JTV Video (desde el carro). */
    fun guardarClave(ctx: Context, clave: String, o: JSONObject, forzarRespaldo: Boolean) {
        val todo = progresoJson(ctx)
        todo.put(clave, o)
        prefs(ctx).edit().putString("progresoPeliculas", todo.toString()).apply()
        val ahora = System.currentTimeMillis()
        if (forzarRespaldo || ahora - ultimoRespaldo > 60_000) {
            ultimoRespaldo = ahora
            Datos.guardarRespaldo(ctx)
        }
    }

    /** Al recuperar un respaldo: se juntan los avances (lo más reciente manda). */
    fun importar(ctx: Context, otro: JSONObject?) {
        if (otro == null) return
        val todo = progresoJson(ctx)
        val k = otro.keys()
        while (k.hasNext()) {
            val clave = k.next()
            val nuevo = otro.optJSONObject(clave) ?: continue
            val viejo = todo.optJSONObject(clave)
            if (viejo == null || nuevo.optLong("t") >= viejo.optLong("t")) todo.put(clave, nuevo)
        }
        prefs(ctx).edit().putString("progresoPeliculas", todo.toString()).apply()
    }
}

/** Pasa la lista de películas a la pantalla del reproductor. */
object CineEstado {
    var lista: List<Pelicula> = emptyList()
    var inicio: Int = 0
}
