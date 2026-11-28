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
 * Instala "JTV Video" de forma que Android Auto SÍ la muestre.
 *
 * Por qué hace falta: Android Auto solo muestra las apps hechas con la librería de apps del carro
 * (como JTV Video o Fermata Auto) si el celular dice que vinieron de Google Play. El interruptor
 * "Fuentes desconocidas" no basta para ese tipo de app (sí basta para apps de audio como JTV).
 * Instalada desde Chrome queda como "instalada por Chrome" y Android Auto la esconde sin avisar.
 *
 * Aquí se le pide al instalador del sistema que la registre como instalada por Google Play
 * (el mismo método "clásico" que usa KingInstaller, el instalador que recomienda Fermata Auto).
 */
/** Una app del carro que JTV instala "como de Google Play" (JTV Video, JTV YouTube). */
class AppCarro(val paquete: String, val archivoApk: String, val nombre: String) {
    val urlApk: String get() = "https://github.com/Jtbarzallo/tv-jat/releases/latest/download/$archivoApk"
    val uriApk: Uri get() = Uri.parse("content://${InstaladorVideo.AUTORIDAD}/$archivoApk")

    class Estado(val instalada: Boolean, val instaladoPor: String?, val pedidoPor: String?) {
        /** true = Android Auto la va a mostrar */
        val lista: Boolean get() = instalada && instaladoPor == InstaladorVideo.PLAY && pedidoPor != "com.android.shell"
    }

    fun archivo(ctx: Context) = File(File(ctx.cacheDir, "apk").apply { mkdirs() }, archivoApk)

    @Suppress("DEPRECATION")
    fun estado(ctx: Context): Estado {
        val pm = ctx.packageManager
        return try {
            pm.getPackageInfo(paquete, 0)
            if (Build.VERSION.SDK_INT >= 30) {
                val i = pm.getInstallSourceInfo(paquete)
                Estado(true, i.installingPackageName, i.initiatingPackageName)
            } else Estado(true, pm.getInstallerPackageName(paquete), null)
        } catch (e: Exception) {
            Estado(false, null, null)
        }
    }

    fun resumen(ctx: Context): String {
        val e = estado(ctx)
        if (!e.instalada) return "$nombre: no está instalada"
        return "$nombre: instalada\n  Instalada por: ${InstaladorVideo.nombre(e.instaladoPor)}\n  Pedido por: ${InstaladorVideo.nombre(e.pedidoPor)}\n" +
            (if (e.lista) "  → Android Auto SÍ la muestra ✅" else "  → Android Auto la esconde ❌")
    }

    /** Descarga la última versión del APK de GitHub. Avisa el avance (0-100). */
    fun descargar(ctx: Context, avance: (Int) -> Unit) {
        var url = urlApk
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

object InstaladorVideo {
    const val PLAY = "com.android.vending"
    const val AUTORIDAD = "ec.jat.tv.apk"
    val VIDEO = AppCarro("ec.jat.tv.video", "TVJAT-Video.apk", "JTV Video")
    val YOUTUBE = AppCarro("ec.jat.tv.youtube", "TVJAT-YouTube.apk", "JTV YouTube")
    val TODAS = listOf(VIDEO, YOUTUBE)
    /** La propia app JTV (para el botón 🔄 Actualizar). */
    val PROPIA = AppCarro("ec.jat.tv", "TVJAT.apk", "JTV")

    // lo de siempre (JTV Video)
    val PAQUETE get() = VIDEO.paquete
    fun estado(ctx: Context) = VIDEO.estado(ctx)
    fun resumen(ctx: Context) = TODAS.joinToString("\n") { it.resumen(ctx) }

    fun nombre(p: String?): String = when {
        p == null -> "(nadie)"
        p == PLAY -> "Google Play ✅"
        p == "com.android.chrome" -> "Chrome ❌"
        p.contains("packageinstaller") -> "el instalador del celular ($p)"
        else -> p
    }

    /** El APK descargado que pide el instalador (por su nombre). */
    fun archivoPorNombre(ctx: Context, nombre: String?): File =
        ((TODAS + PROPIA).firstOrNull { it.archivoApk == nombre } ?: VIDEO).archivo(ctx)

    /** Número de la versión instalada de un paquete ("1.0.30" → 30); -1 si no está. */
    fun numeroInstalado(ctx: Context, paquete: String): Int = try {
        ctx.packageManager.getPackageInfo(paquete, 0).versionName?.substringAfterLast('.')?.toIntOrNull() ?: -1
    } catch (e: Exception) { -1 }

    /** Número de la última versión publicada en GitHub (v1.0.N → N). Lo pide por internet. */
    fun numeroUltimo(): Int {
        val con = (URL("https://api.github.com/repos/Jtbarzallo/tv-jat/releases?per_page=10").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (con.responseCode != 200) throw Exception("GitHub respondió ${con.responseCode}")
            val txt = con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = org.json.JSONArray(txt)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tag = o.optString("tag_name")
                if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue
                if (!tag.startsWith("v1.")) continue          // las de la PC son "pc-v…"
                return tag.substringAfterLast('.').toIntOrNull() ?: continue
            }
            throw Exception("no se encontró la versión del celular")
        } finally { con.disconnect() }
    }
}

/** Entrega el APK descargado al instalador del sistema (solo lectura, sin exponer otros archivos). */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(InstaladorVideo.archivoPorNombre(context!!, uri.lastPathSegment), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor {
        val f = InstaladorVideo.archivoPorNombre(context!!, u.lastPathSegment)
        return android.database.MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
