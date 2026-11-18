package ec.jat.tv

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Todo lo que guarda la app.
 * Regla JAT: lo que guarda la app queda también en un archivo que sobrevive si se
 * desinstala la app: Descargas/TVJAT/respaldo_tv_jat.json (lista completa + favoritos).
 */
object Datos {

    const val LISTA_ECUADOR = "https://iptv-org.github.io/iptv/countries/ec.m3u"
    private const val PREFS = "tvjat"
    private const val ARCHIVO_LISTA = "lista.m3u"
    const val NOMBRE_RESPALDO = "respaldo_tv_jat.json"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- lista actual ----------
    fun textoLista(ctx: Context): String? {
        val f = File(ctx.filesDir, ARCHIVO_LISTA)
        return if (f.exists()) f.readText() else null
    }

    fun nombreLista(ctx: Context): String = prefs(ctx).getString("nombreLista", "") ?: ""
    fun fuenteLista(ctx: Context): String = prefs(ctx).getString("fuente", "") ?: ""

    fun guardarLista(ctx: Context, texto: String, nombre: String, fuente: String) {
        File(ctx.filesDir, ARCHIVO_LISTA).writeText(texto)
        prefs(ctx).edit().putString("nombreLista", nombre).putString("fuente", fuente).apply()
        agregarReciente(ctx, nombre, fuente)
        guardarRespaldo(ctx)
    }

    // ---------- listas usadas antes (solo las que vienen de internet) ----------
    fun recientes(ctx: Context): List<Pair<String, String>> {
        val arr = try { JSONArray(prefs(ctx).getString("recientes", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            o.optString("nombre") to o.optString("fuente")
        }
    }

    private fun agregarReciente(ctx: Context, nombre: String, fuente: String) {
        if (!fuente.startsWith("http")) return
        val lista = recientes(ctx).filter { it.second != fuente }.toMutableList()
        lista.add(0, nombre to fuente)
        val arr = JSONArray()
        lista.take(10).forEach { arr.put(JSONObject().put("nombre", it.first).put("fuente", it.second)) }
        prefs(ctx).edit().putString("recientes", arr.toString()).apply()
    }

    // ---------- favoritos ----------
    fun favoritos(ctx: Context): MutableSet<String> {
        val arr = try { JSONArray(prefs(ctx).getString("favoritos", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.optString(it) }.toMutableSet()
    }

    fun cambiarFavorito(ctx: Context, url: String): Boolean {
        val f = favoritos(ctx)
        val ahora = if (f.contains(url)) { f.remove(url); false } else { f.add(url); true }
        prefs(ctx).edit().putString("favoritos", JSONArray(f.toList()).toString()).apply()
        guardarRespaldo(ctx)
        return ahora
    }

    // ---------- último canal ----------
    fun ultimo(ctx: Context): String = prefs(ctx).getString("ultimo", "") ?: ""
    fun guardarUltimo(ctx: Context, url: String) {
        prefs(ctx).edit().putString("ultimo", url).apply()
    }

    // ---------- respaldo ----------
    fun contenido(ctx: Context): String = JSONObject()
        .put("app", "TV JAT")
        .put("version", 1)
        .put("fecha", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        .put("nombreLista", nombreLista(ctx))
        .put("fuente", fuenteLista(ctx))
        .put("favoritos", JSONArray(favoritos(ctx).toList()))
        .put("recientes", JSONArray(prefs(ctx).getString("recientes", "[]") ?: "[]"))
        .put("ultimo", ultimo(ctx))
        .put("listaM3u", textoLista(ctx) ?: "")
        .toString(2)

    fun exportar(ctx: Context, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(contenido(ctx).toByteArray()) }
    }

    /** Recupera un respaldo. Devuelve cuántos canales trae la lista. */
    fun importar(ctx: Context, uri: Uri): Int {
        val texto = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("No se pudo leer el archivo")
        val json = JSONObject(texto)
        if (json.optString("app") != "TV JAT") throw IllegalArgumentException("Ese archivo no es un respaldo de TV JAT")
        val m3u = json.optString("listaM3u", "")
        if (m3u.isNotBlank()) File(ctx.filesDir, ARCHIVO_LISTA).writeText(m3u)
        val previos = favoritos(ctx)
        val fav = json.optJSONArray("favoritos") ?: JSONArray()
        for (i in 0 until fav.length()) previos.add(fav.optString(i))
        prefs(ctx).edit()
            .putString("nombreLista", json.optString("nombreLista", nombreLista(ctx)))
            .putString("fuente", json.optString("fuente", fuenteLista(ctx)))
            .putString("favoritos", JSONArray(previos.toList()).toString())
            .putString("recientes", (json.optJSONArray("recientes") ?: JSONArray()).toString())
            .putString("ultimo", json.optString("ultimo", ultimo(ctx)))
            .apply()
        guardarRespaldo(ctx)
        return M3u.leer(m3u).size
    }

    /** Copia automática en Descargas/TVJAT (queda aunque se desinstale la app). */
    fun guardarRespaldo(ctx: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val datos = contenido(ctx).toByteArray()
            val guardada = prefs(ctx).getString("uriRespaldo", null)?.let { Uri.parse(it) }
            if (guardada != null) {
                try {
                    ctx.contentResolver.openOutputStream(guardada, "wt")?.use { it.write(datos) }
                    return
                } catch (e: Exception) { /* lo borraron: se crea otro */ }
            }
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, NOMBRE_RESPALDO)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/TVJAT")
            }
            val nueva = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return
            ctx.contentResolver.openOutputStream(nueva, "wt")?.use { it.write(datos) }
            prefs(ctx).edit().putString("uriRespaldo", nueva.toString()).apply()
        } catch (e: Exception) {
        }
    }
}
