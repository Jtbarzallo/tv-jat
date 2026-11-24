package ec.jat.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Instala "TV JAT Video" de forma que Android Auto SÍ la muestre.
 *
 * Por qué hace falta: Android Auto solo muestra las apps hechas con la librería de apps del carro
 * (como TV JAT Video o Fermata Auto) si el celular dice que vinieron de Google Play. El interruptor
 * "Fuentes desconocidas" no basta para ese tipo de app (sí basta para apps de audio como TV JAT).
 * Instalada desde Chrome queda como "instalada por Chrome" y Android Auto la esconde sin avisar.
 *
 * Aquí se le pide al instalador del sistema que la registre como instalada por Google Play
 * (el mismo método "clásico" que usa KingInstaller, el instalador que recomienda Fermata Auto).
 */
object InstaladorVideo {
    const val PAQUETE = "ec.jat.tv.video"
    const val PLAY = "com.android.vending"
    const val URL_APK = "https://github.com/Jtbarzallo/tv-jat/releases/latest/download/TVJAT-Video.apk"
    const val AUTORIDAD = "ec.jat.tv.apk"
    val URI_APK: Uri = Uri.parse("content://$AUTORIDAD/TVJAT-Video.apk")

    class Estado(val instalada: Boolean, val instaladoPor: String?, val pedidoPor: String?) {
        /** true = Android Auto la va a mostrar */
        val lista: Boolean get() = instalada && instaladoPor == PLAY && pedidoPor != "com.android.shell"
    }

    fun archivo(ctx: Context) = File(File(ctx.cacheDir, "apk").apply { mkdirs() }, "TVJAT-Video.apk")

    @Suppress("DEPRECATION")
    fun estado(ctx: Context): Estado {
        val pm = ctx.packageManager
        return try {
            pm.getPackageInfo(PAQUETE, 0)
            if (Build.VERSION.SDK_INT >= 30) {
                val i = pm.getInstallSourceInfo(PAQUETE)
                Estado(true, i.installingPackageName, i.initiatingPackageName)
            } else Estado(true, pm.getInstallerPackageName(PAQUETE), null)
        } catch (e: Exception) {
            Estado(false, null, null)
        }
    }

    fun nombre(p: String?): String = when {
        p == null -> "(nadie)"
        p == PLAY -> "Google Play ✅"
        p == "com.android.chrome" -> "Chrome ❌"
        p.contains("packageinstaller") -> "el instalador del celular ($p)"
        else -> p
    }

    fun resumen(ctx: Context): String {
        val e = estado(ctx)
        if (!e.instalada) return "TV JAT Video: no está instalada"
        return "TV JAT Video: instalada\n  Instalada por: ${nombre(e.instaladoPor)}\n  Pedido por: ${nombre(e.pedidoPor)}\n" +
            (if (e.lista) "  → Android Auto SÍ la muestra ✅" else "  → Android Auto la esconde ❌")
    }

    /** Descarga la última TVJAT-Video.apk de GitHub. Avisa el avance (0-100). */
    fun descargar(ctx: Context, avance: (Int) -> Unit) {
        var url = URL_APK
        var con: HttpURLConnection
        var saltos = 0
        while (true) {
            con = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20000
                readTimeout = 30000
            }
            val c = con.responseCode
            if (c in 300..399 && saltos < 6) {
                url = con.getHeaderField("Location") ?: break
                con.disconnect(); saltos++
            } else break
        }
        if (con.responseCode != 200) throw Exception("GitHub respondió ${con.responseCode}")
        val total = con.contentLength
        val destino = archivo(ctx)
        val temporal = File(destino.parentFile, "descargando.apk")
        con.inputStream.use { ent ->
            temporal.outputStream().use { sal ->
                val buf = ByteArray(64 * 1024)
                var leido = 0L
                while (true) {
                    val n = ent.read(buf)
                    if (n < 0) break
                    sal.write(buf, 0, n)
                    leido += n
                    if (total > 0) avance((leido * 100 / total).toInt())
                }
            }
        }
        if (temporal.length() < 100_000) throw Exception("el archivo bajó incompleto")
        destino.delete()
        temporal.renameTo(destino)
    }
}

/** Entrega el APK descargado al instalador del sistema (solo lectura, sin exponer otros archivos). */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(InstaladorVideo.archivo(context!!), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor {
        val f = InstaladorVideo.archivo(context!!)
        return android.database.MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
