package ec.jat.tv

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Bundle
import org.json.JSONObject

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

    // ---------- avance: se guarda en JTV (mismo respaldo que el celular) ----------
    fun progresoJson(ctx: Context): JSONObject = Datos.progreso(ctx)

    fun posicion(ctx: Context, p: Pelicula): Long {
        val o = progresoJson(ctx).optJSONObject(p.clave) ?: return 0
        return if (o.optBoolean("vista")) 0 else o.optLong("pos")
    }

    fun avance(ctx: Context, p: Pelicula): Float {
        val o = progresoJson(ctx).optJSONObject(p.clave) ?: return 0f
        if (o.optBoolean("vista")) return 1f
        val dur = if (p.duracionMs > 0) p.duracionMs else o.optLong("dur")
        return if (dur > 0) (o.optLong("pos").toFloat() / dur).coerceIn(0f, 1f) else 0f
    }

    fun ultimaVez(ctx: Context, p: Pelicula): Long = progresoJson(ctx).optJSONObject(p.clave)?.optLong("t") ?: 0

    private fun enviar(ctx: Context, p: Pelicula, o: JSONObject, forzar: Boolean) {
        Puente.llamar(ctx, "guardarProgreso", p.clave, Bundle().apply {
            putString("json", o.toString()); putBoolean("forzar", forzar)
        })
        Puente.olvidar()
    }

    fun guardarPosicion(ctx: Context, p: Pelicula, pos: Long, dur: Long, forzarRespaldo: Boolean = false) {
        val terminada = dur > 0 && pos >= dur * 0.95
        enviar(ctx, p, JSONObject().put("pos", if (terminada) 0 else pos).put("dur", dur)
            .put("vista", terminada).put("t", System.currentTimeMillis()).put("titulo", p.titulo), forzarRespaldo)
    }

    fun marcar(ctx: Context, p: Pelicula, vista: Boolean) {
        if (!vista) return
        enviar(ctx, p, JSONObject().put("pos", 0).put("dur", p.duracionMs).put("vista", true)
            .put("t", System.currentTimeMillis()).put("titulo", p.titulo), true)
    }
}
