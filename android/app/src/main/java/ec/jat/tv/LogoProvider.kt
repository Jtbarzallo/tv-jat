package ec.jat.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Logos de los canales para Android Auto.
 * Android Auto no descarga imágenes de internet (por eso salían triángulos ⚠). Este proveedor las baja,
 * las guarda y se las entrega al carro. Si un canal no tiene logo o no baja, dibuja uno con su nombre.
 * Dirección: content://ec.jat.tv.logos/logo?u=<logo>&n=<nombre>
 */
class LogoProvider : ContentProvider() {

    companion object {
        const val AUTORIDAD = "ec.jat.tv.logos"
        fun uri(logo: String, nombre: String): Uri = Uri.Builder().scheme("content").authority(AUTORIDAD)
            .appendPath("logo").appendQueryParameter("u", logo).appendQueryParameter("n", nombre).build()
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context!!
        val logo = uri.getQueryParameter("u").orEmpty()
        val nombre = uri.getQueryParameter("n").orEmpty()
        val dir = File(ctx.cacheDir, "logos_auto").apply { mkdirs() }
        val f = File(dir, (logo + "|" + nombre).hashCode().toUInt().toString() + ".png")
        if (!f.exists() || f.length() == 0L) {
            val bmp = (if (logo.startsWith("http")) bajar(logo) else null) ?: dibujar(nombre)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Baja el logo y lo pone centrado en un cuadro (para que no se corte). */
    private fun bajar(url: String): Bitmap? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 7000; c.readTimeout = 9000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", Reproduccion.USER_AGENT)
        val orig = c.inputStream.use { BitmapFactory.decodeStream(it) }
        c.disconnect()
        orig?.let { cuadrado(it) }
    } catch (e: Exception) { null }

    private fun cuadrado(b: Bitmap): Bitmap {
        val lado = 256
        val out = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.rgb(13, 31, 74))
        val esc = minOf((lado * 0.86f) / b.width, (lado * 0.86f) / b.height)
        val w = b.width * esc; val h = b.height * esc
        cv.drawBitmap(b, null, RectF((lado - w) / 2, (lado - h) / 2, (lado + w) / 2, (lado + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Cuadro azul con las iniciales del canal (cuando no hay logo). */
    private fun dibujar(nombre: String): Bitmap {
        val lado = 256
        val out = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.rgb(13, 31, 74))
        val limpio = nombre.replace(Regex("\\(.*?\\)|\\[.*?]"), " ").trim()
        val ini = limpio.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.take(1) }.uppercase().ifEmpty { "TV" }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = if (ini.length > 1) 110f else 130f
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        cv.drawText(ini, lado / 2f, lado / 2f - (p.descent() + p.ascent()) / 2, p)
        val r = Paint().apply { color = Color.rgb(229, 57, 53) }
        cv.drawRect(0f, lado - 18f, lado.toFloat(), lado.toFloat(), r)
        return out
    }

    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
