package ec.jat.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * Puente con la app "TV JAT Video" (Android Auto con imagen).
 * Solo responde a apps firmadas con la misma llave de JAT. Así los datos siguen en un solo lugar
 * (esta app) y entran en el mismo respaldo de Descargas/TVJAT.
 */
class DatosProvider : ContentProvider() {

    override fun onCreate() = true

    private fun autorizado(): Boolean {
        val ctx = context ?: return false
        val quien = callingPackage ?: return false
        return quien == ctx.packageName ||
            ctx.packageManager.checkSignatures(ctx.packageName, quien) == PackageManager.SIGNATURE_MATCH
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!autorizado()) throw SecurityException("TV JAT: app no autorizada")
        val ctx = context!!
        val b = Bundle()
        when (method) {
            "estado" -> {
                val f = File(ctx.filesDir, "lista.m3u")
                b.putLong("marcaLista", if (f.exists()) f.lastModified() xor f.length() else 0L)
                b.putStringArrayList("favoritos", ArrayList(Datos.favoritos(ctx)))
                b.putStringArrayList("caidos", ArrayList(Datos.caidos(ctx)))
                b.putBoolean("ocultarCaidos", Datos.ocultarCaidos(ctx))
                b.putString("ultimo", Datos.ultimo(ctx))
                b.putBoolean("modoPasajero", Datos.modoPasajero(ctx))
                b.putString("formaImagen", Datos.formaImagen(ctx))
                b.putString("ajusteImagen", Datos.ajusteImagen(ctx))
                b.putString("calidad", Datos.calidad(ctx))
                b.putString("progreso", Peliculas.progresoJson(ctx).toString())
            }
            "guardarUltimo" -> Datos.guardarUltimo(ctx, arg.orEmpty())
            "modoPasajero" -> Datos.setModoPasajero(ctx, arg == "1")
            "formaImagen" -> Datos.setFormaImagen(ctx, if (arg == "B") "B" else "A")
            "ajusteImagen" -> Datos.setAjusteImagen(ctx, if (arg == "entera") "entera" else "completa")
            "favorito" -> if (!arg.isNullOrEmpty()) b.putBoolean("es", Datos.cambiarFavorito(ctx, arg))
            "calidad" -> Datos.setCalidad(ctx, if (arg == "alta" || arg == "ahorro") arg else "auto")
            "guardarProgreso" -> {
                val json = extras?.getString("json")
                if (!arg.isNullOrEmpty() && json != null)
                    Peliculas.guardarClave(ctx, arg, JSONObject(json), extras.getBoolean("forzar"))
            }
            "anotar" -> Registro.anotar(ctx, arg.orEmpty())
            "pausarRadio" -> Handler(Looper.getMainLooper()).post { AutoService.pausarSiSuena() }
        }
        return b
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!autorizado()) throw SecurityException("TV JAT: app no autorizada")
        val f = File(context!!.filesDir, "lista.m3u")
        if (!f.exists()) throw FileNotFoundException("Sin lista")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "audio/x-mpegurl"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
