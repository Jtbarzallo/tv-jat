package ec.jat.tv

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.ceil

/**
 * Grabador de TV JAT (celular y carro): la "línea de tiempo".
 *
 * Mientras ves un canal (HLS en vivo), TV JAT lo va descargando parte por parte y lo guarda en el celular
 * (hasta 10 minutos). El video se reproduce desde lo guardado, por un pequeño servidor que solo existe
 * dentro del celular (127.0.0.1). Así:
 *  · si el internet baja un momento, el video sigue con lo grabado (no se corta);
 *  · se puede regresar en la barra a lo que ya pasó y volver al EN VIVO;
 *  · en pausa sigue grabando: al volver sigue donde se quedó.
 * Si un canal no se puede grabar (no es HLS, es un video con fin, trae el audio aparte, etc.),
 * se ve como siempre, directo del internet.
 * Lo grabado es temporal (carpeta caché) y se borra al cambiar de canal o cerrar.
 */
object Grabador {
    const val MAX_MS = 10 * 60_000L

    private var servidor: ServerSocket? = null
    private var puerto = 0
    @Volatile private var actual: Grabacion? = null
    private var dirBase: File? = null
    private var app: Context? = null

    fun iniciar(ctx: Context) {
        if (servidor != null) return
        app = ctx.applicationContext
        try {
            val d = File(ctx.cacheDir, "grabacion")
            d.deleteRecursively(); d.mkdirs()
            dirBase = d
            val s = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            servidor = s
            puerto = s.localPort
            thread(name = "TVJAT-servidor", isDaemon = true) {
                while (!s.isClosed) {
                    val cli = try { s.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) { atender(cli) }
                }
            }
        } catch (e: Exception) {
            anotar("⚠ grabador no pudo iniciar: ${e.javaClass.simpleName}")
            servidor = null
        }
    }

    fun anotar(t: String) { app?.let { Registro.anotar(it, "   📼 $t") } }

    fun activo() = servidor != null

    /**
     * Dirección local (dentro del celular) para ver ese canal desde lo grabado, o null si no se puede grabar.
     * Se llama desde el hilo de carga del reproductor (puede tardar unos segundos la primera vez).
     */
    @Synchronized
    fun direccion(url: String, ua: String, referer: String, maxAlto: Int): Uri? {
        if (servidor == null) return null
        val g = actual
        if (g != null && g.url == url && g.viva) return g.uriLocal(puerto)
        detener()
        val base = dirBase ?: return null
        val nueva = Grabacion(url, ua, referer, maxAlto, File(base, System.currentTimeMillis().toString()))
        val ok = try { nueva.preparar() } catch (e: Exception) { anotar("no se puede grabar (${e.javaClass.simpleName}): se ve directo"); false }
        if (!ok) { nueva.parar(); return null }
        actual = nueva
        nueva.arrancar()
        anotar("grabando el canal (línea de tiempo de hasta 10 min)")
        return nueva.uriLocal(puerto)
    }

    @Synchronized
    fun detener() {
        actual?.parar()
        actual = null
    }

    fun cerrar() {
        detener()
        try { servidor?.close() } catch (e: Exception) {}
        servidor = null
    }

    /** Para el diagnóstico. */
    fun info(): String {
        val g = actual ?: return "sin grabar (canal directo)"
        return "grabado ${g.grabadoMs() / 1000} s en ${g.cantidad()} partes" +
            (if (g.fallosSeguidos > 0) " · sin bajar hace ${g.fallosSeguidos} intentos" else "")
    }

    // ------------------------------------------------------------------ servidor local
    private fun atender(s: Socket) {
        try {
            s.soTimeout = 15_000
            val ent = s.getInputStream()
            val linea = leerLinea(ent) ?: return
            var rango: Long = -1
            while (true) {
                val h = leerLinea(ent) ?: break
                if (h.isEmpty()) break
                if (h.lowercase().startsWith("range:")) {
                    rango = Regex("bytes=(\\d+)-").find(h)?.groupValues?.get(1)?.toLongOrNull() ?: -1
                }
            }
            val partes = linea.split(" ")
            val ruta = if (partes.size >= 2) partes[1] else "/"
            val sal = s.getOutputStream()
            val g = actual
            val m = Regex("^/g/([0-9a-z]+)/(.+)$").find(ruta)
            if (g == null || m == null || m.groupValues[1] != g.id) { responder(sal, 404, "text/plain", "no".toByteArray()); return }
            val recurso = m.groupValues[2]
            if (recurso == "lista.m3u8") {
                responder(sal, 200, "application/vnd.apple.mpegurl", g.lista(puerto).toByteArray())
                return
            }
            val f = g.archivoDe(recurso)
            if (f == null || !f.exists()) { responder(sal, 404, "text/plain", "no".toByteArray()); return }
            enviarArchivo(sal, f, rango, g.tipo(recurso))
        } catch (e: Exception) {
        } finally {
            try { s.close() } catch (e: Exception) {}
        }
    }

    private fun leerLinea(ent: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ent.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8000) return sb.toString()
        }
    }

    private fun responder(sal: OutputStream, cod: Int, tipo: String, cuerpo: ByteArray) {
        val est = if (cod == 200) "OK" else "Not Found"
        sal.write(("HTTP/1.1 $cod $est\r\nContent-Type: $tipo\r\nContent-Length: ${cuerpo.size}\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
        sal.write(cuerpo)
        sal.flush()
    }

    private fun enviarArchivo(sal: OutputStream, f: File, rango: Long, tipo: String) {
        val largo = f.length()
        val desde = if (rango in 1 until largo) rango else 0L
        val cab = if (desde > 0)
            "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $desde-${largo - 1}/$largo\r\n"
        else "HTTP/1.1 200 OK\r\n"
        sal.write((cab + "Content-Type: $tipo\r\nContent-Length: ${largo - desde}\r\nAccept-Ranges: bytes\r\n" +
            "Connection: close\r\n\r\n").toByteArray())
        f.inputStream().use { inp ->
            if (desde > 0) inp.skip(desde)
            inp.copyTo(sal, 64 * 1024)
        }
        sal.flush()
    }
}

/** Una grabación de un canal. */
class Grabacion(val url: String, private val ua: String, private val referer: String,
                private val maxAlto: Int, private val dir: File) {

    val id: String = java.lang.Long.toString(System.nanoTime() and 0xFFFFFFFFL, 36)
    @Volatile var viva = true
    @Volatile var fallosSeguidos = 0
    private var listaUrl = url
    private var objetivo = 6
    private var version = 3
    private var ultimoN = -1L          // número (de la fuente) de la última parte bajada
    private var local = 0L             // numeración propia (sin huecos)
    private var discQuitadas = 0       // cortes que ya salieron de la ventana
    private var hilo: Thread? = null

    private class Seg(val n: Long, val url: String, val dur: Double, val disc: Boolean, val key: String?, val map: String?)
    private class Parte(val local: Long, val dur: Double, val archivo: File, val disc: Boolean,
                        val key: String?, val map: String?, val ext: String)
    private val partes = ArrayList<Parte>()

    fun uriLocal(puerto: Int): Uri = Uri.parse("http://127.0.0.1:$puerto/g/$id/lista.m3u8")

    fun cantidad() = synchronized(partes) { partes.size }
    fun grabadoMs() = synchronized(partes) { (partes.sumOf { it.dur } * 1000).toLong() }

    // ---------------------------------------------------------------- red
    private fun abrir(u: String): HttpURLConnection {
        var dir = u
        repeat(6) {
            val c = URL(dir).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", ua.ifEmpty { Reproduccion.USER_AGENT })
            if (referer.isNotEmpty()) c.setRequestProperty("Referer", referer)
            val cod = c.responseCode
            if (cod in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw java.io.IOException("redirección sin destino")
                dir = URL(URL(dir), loc).toString()
                c.disconnect()
            } else if (cod in 200..299) {
                return c
            } else { c.disconnect(); throw java.io.IOException("HTTP $cod") }
        }
        throw java.io.IOException("demasiadas redirecciones")
    }

    private fun bajarTexto(u: String): Pair<String, String> {
        val c = abrir(u)
        try {
            val txt = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return c.url.toString() to txt
        } finally { c.disconnect() }
    }

    private fun resolver(base: String, rel: String): String = try { URI(base).resolve(rel.trim()).toString() } catch (e: Exception) { URL(URL(base), rel.trim()).toString() }

    private fun absolutizarUri(linea: String, base: String): String =
        Regex("URI=\"([^\"]*)\"").replace(linea) { m -> "URI=\"" + resolver(base, m.groupValues[1]) + "\"" }

    // ---------------------------------------------------------------- preparar
    /** Revisa que se pueda grabar y baja las primeras partes. */
    fun preparar(): Boolean {
        val (fin, txt) = bajarTexto(url)
        if (!txt.trimStart().startsWith("#EXTM3U")) return false
        if (txt.contains("#EXT-X-STREAM-INF")) {
            // lista de calidades: elegir la mejor que no pase la calidad elegida
            if (Regex("#EXT-X-MEDIA:[^\n]*TYPE=AUDIO[^\n]*URI=").containsMatchIn(txt)) {
                Grabador.anotar("el canal trae el audio aparte: se ve directo"); return false
            }
            val lineas = txt.lines()
            data class Var(val bw: Long, val alto: Int, val u: String)
            val vars = ArrayList<Var>()
            for (i in lineas.indices) {
                val l = lineas[i]
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue
                val sig = lineas.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                val alto = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                vars.add(Var(bw, alto, resolver(fin, sig)))
            }
            if (vars.isEmpty()) return false
            val caben = vars.filter { it.alto == 0 || it.alto <= maxAlto }
            val elegida = if (caben.isNotEmpty()) caben.maxByOrNull { it.bw }!! else vars.minByOrNull { it.bw }!!
            listaUrl = elegida.u
        } else listaUrl = fin
        dir.mkdirs()
        val segs = leerLista() ?: return false
        if (segs.isEmpty()) return false
        // empezar cerca del directo: las últimas 3 partes
        val inicio = segs.takeLast(3)
        ultimoN = inicio.first().n - 1
        bajarPendientes(inicio)
        return cantidad() > 0
    }

    /** Lee la lista de la fuente. null = no se puede grabar (video con fin o por pedazos). */
    private fun leerLista(): List<Seg>? {
        val (fin, txt) = bajarTexto(listaUrl)
        listaUrl = fin
        if (!txt.trimStart().startsWith("#EXTM3U")) throw java.io.IOException("lista inválida")
        if (txt.contains("#EXT-X-ENDLIST") || txt.contains("#EXT-X-BYTERANGE")) return null
        val segs = ArrayList<Seg>()
        var seq = 0L; var dur = 0.0; var disc = false
        var key: String? = null; var map: String? = null
        for (l0 in txt.lines()) {
            val l = l0.trim()
            when {
                l.isEmpty() -> {}
                l.startsWith("#EXT-X-TARGETDURATION:") -> objetivo = l.substringAfter(':').trim().toDoubleOrNull()?.let { ceil(it).toInt() } ?: objetivo
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-VERSION:") -> version = l.substringAfter(':').trim().toIntOrNull() ?: version
                l.startsWith("#EXT-X-KEY:") -> key = if (l.contains("METHOD=NONE")) null else absolutizarUri(l, fin)
                l.startsWith("#EXT-X-MAP:") -> map = absolutizarUri(l, fin)
                l.startsWith("#EXT-X-DISCONTINUITY") && !l.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> disc = true
                l.startsWith("#EXTINF:") -> dur = l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: objetivo.toDouble()
                l.startsWith("#") -> {}
                else -> {
                    segs.add(Seg(seq, resolver(fin, l), dur, disc, key, map))
                    seq++; disc = false
                }
            }
        }
        return segs
    }

    // ---------------------------------------------------------------- grabar
    fun arrancar() {
        hilo = thread(name = "TVJAT-grabador", isDaemon = true) {
            while (viva) {
                val t0 = System.currentTimeMillis()
                try {
                    val segs = leerLista()
                    if (segs != null) bajarPendientes(segs)
                    fallosSeguidos = 0
                } catch (e: Exception) {
                    fallosSeguidos++
                    if (fallosSeguidos == 1) Grabador.anotar("no se pudo bajar (${e.javaClass.simpleName}); sigue con lo grabado y reintenta")
                }
                val espera = (objetivo * 1000L / 2).coerceIn(1000, 5000) - (System.currentTimeMillis() - t0)
                if (espera > 0) try { Thread.sleep(espera) } catch (e: InterruptedException) { break }
            }
        }
    }

    private fun bajarPendientes(segs: List<Seg>) {
        for (s in segs) {
            if (!viva) return
            if (s.n <= ultimoN) continue
            val hueco = ultimoN >= 0 && s.n > ultimoN + 1        // se perdieron partes (internet muy lento)
            val ext = s.url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "ts")
                .lowercase().takeIf { it.length in 2..4 } ?: "ts"
            val archivo = File(dir, "$local.$ext")
            var ok = false
            for (intento in 0 until 2) {
                try {
                    val c = abrir(s.url)
                    try {
                        val tmp = File(dir, "$local.tmp")
                        c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it, 64 * 1024) } }
                        tmp.renameTo(archivo)
                        ok = true
                    } finally { c.disconnect() }
                    break
                } catch (e: Exception) {
                    if (!viva) return
                }
            }
            if (!ok) throw java.io.IOException("parte ${s.n} no bajó")
            synchronized(partes) {
                partes.add(Parte(local, s.dur, archivo, s.disc || hueco, s.key, s.map, ext))
                local++
                ultimoN = s.n
                // guardar solo los últimos 10 minutos
                while (partes.size > 3 && partes.sumOf { it.dur } * 1000 > Grabador.MAX_MS) {
                    val q = partes.removeAt(0)
                    if (q.disc) discQuitadas++
                    q.archivo.delete()
                }
            }
        }
    }

    /** La lista que ve el reproductor: todo lo grabado (la ventana crece hasta 10 min). */
    fun lista(puerto: Int): String {
        val sb = StringBuilder("#EXTM3U\n")
        synchronized(partes) {
            val hayMap = partes.any { it.map != null }
            val maxDur = partes.maxOfOrNull { it.dur } ?: objetivo.toDouble()
            sb.append("#EXT-X-VERSION:").append(maxOf(version, if (hayMap) 6 else 3)).append('\n')
            sb.append("#EXT-X-TARGETDURATION:").append(maxOf(objetivo, ceil(maxDur).toInt())).append('\n')
            sb.append("#EXT-X-MEDIA-SEQUENCE:").append(partes.firstOrNull()?.local ?: 0).append('\n')
            sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discQuitadas).append('\n')
            var key: String? = null; var map: String? = null
            partes.forEachIndexed { i, p ->
                if (p.disc && i > 0) sb.append("#EXT-X-DISCONTINUITY\n")
                if (p.key != key) { sb.append(p.key ?: "#EXT-X-KEY:METHOD=NONE").append('\n'); key = p.key }
                if (p.map != null && p.map != map) { sb.append(p.map).append('\n'); map = p.map }
                sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", p.dur)).append(",\n")
                sb.append("http://127.0.0.1:").append(puerto).append("/g/").append(id).append('/')
                    .append(p.local).append('.').append(p.ext).append('\n')
            }
        }
        return sb.toString()
    }

    fun archivoDe(nombre: String): File? {
        val n = nombre.substringBefore('.').toLongOrNull() ?: return null
        return synchronized(partes) { partes.firstOrNull { it.local == n }?.archivo }
    }

    fun tipo(nombre: String): String = when (nombre.substringAfterLast('.')) {
        "aac" -> "audio/aac"
        "mp4", "m4s", "m4v", "cmfv" -> "video/mp4"
        else -> "video/mp2t"
    }

    fun parar() {
        viva = false
        hilo?.interrupt()
        try { dir.deleteRecursively() } catch (e: Exception) {}
    }
}
