package ec.jat.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Descarga los logos de los canales (con memoria para no bajarlos dos veces). */
object Logos {
    private val memoria = LruCache<String, Bitmap>(200)
    private val fallidos = HashSet<String>()
    private val hilos = Executors.newFixedThreadPool(4)
    private val principal = Handler(Looper.getMainLooper())

    fun poner(vista: ImageView, url: String) {
        vista.tag = url
        vista.setImageDrawable(null)
        if (url.isBlank() || fallidos.contains(url)) return
        memoria.get(url)?.let { vista.setImageBitmap(it); return }
        hilos.execute {
            val bmp = try {
                val con = URL(url).openConnection() as HttpURLConnection
                con.connectTimeout = 8000
                con.readTimeout = 10000
                con.setRequestProperty("User-Agent", Reproduccion.USER_AGENT)
                val bytes = con.inputStream.use { it.readBytes() }
                val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
                var escala = 1
                while (op.outWidth / escala > 256 || op.outHeight / escala > 256) escala *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = escala })
            } catch (e: Throwable) { null }
            principal.post {
                if (bmp == null) { fallidos.add(url); return@post }
                memoria.put(url, bmp)
                if (vista.tag == url) vista.setImageBitmap(bmp)
            }
        }
    }
}
