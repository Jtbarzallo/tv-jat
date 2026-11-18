package ec.jat.tv

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prueba desde el celular qué canales responden de verdad
 * (lista del canal → calidad → que tenga partes de video).
 */
object Revisar {

    @Volatile var cancelar = false

    fun revisar(
        canales: List<Canal>,
        progreso: (hechos: Int, total: Int) -> Unit,
        fin: (caidos: Set<String>) -> Unit
    ) {
        cancelar = false
        val urls = canales.distinctBy { it.url }
        val caidos = java.util.Collections.synchronizedSet(HashSet<String>())
        val hechos = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(10)
        for (c in urls) {
            pool.execute {
                if (!cancelar && !funciona(c)) caidos.add(c.url)
                progreso(hechos.incrementAndGet(), urls.size)
            }
        }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.MINUTES)
        if (!cancelar) fin(HashSet(caidos))
    }

    private fun funciona(c: Canal): Boolean = try {
        val (codigo, tipo, texto, url) = pedir(c.url, c)
        when {
            codigo !in 200..299 -> false
            texto.contains("#EXTM3U") -> revisarHls(texto, url, c, 0)
            texto.contains("<MPD", ignoreCase = true) -> true
            tipo.startsWith("video") || tipo.contains("octet") || tipo.contains("mp2t") -> true
            else -> false
        }
    } catch (e: Throwable) { false }

    /** Una lista HLS sirve si llega hasta las partes de video y la primera parte responde. */
    private fun revisarHls(texto: String, base: URL, c: Canal, nivel: Int): Boolean {
        val lineas = texto.lines().map { it.trim() }
        if (texto.contains("#EXT-X-STREAM-INF") && nivel < 3) {
            val i = lineas.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            val sub = lineas.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
            val (codigo, _, t2, u2) = pedir(URL(base, sub).toString(), c)
            return codigo in 200..299 && t2.contains("#EXTM3U") && revisarHls(t2, u2, c, nivel + 1)
        }
        if (!texto.contains("#EXTINF")) return false
        val parte = lineas.lastOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
        val (codigo, _, _, _) = pedir(URL(base, parte).toString(), c, soloInicio = true)
        return codigo in 200..299
    }

    private data class Respuesta(val codigo: Int, val tipo: String, val texto: String, val url: URL)

    private fun pedir(direccion: String, c: Canal, soloInicio: Boolean = false): Respuesta {
        var url = URL(direccion)
        var saltos = 0
        while (true) {
            val con = url.openConnection() as HttpURLConnection
            con.connectTimeout = 8000
            con.readTimeout = 10000
            con.instanceFollowRedirects = false
            con.setRequestProperty("User-Agent", c.userAgent.ifEmpty { Reproduccion.USER_AGENT })
            if (c.referer.isNotEmpty()) con.setRequestProperty("Referer", c.referer)
            if (soloInicio) con.setRequestProperty("Range", "bytes=0-2047")
            val codigo = con.responseCode
            if (codigo in 300..399 && saltos < 6) {
                val destino = con.getHeaderField("Location")
                con.disconnect()
                if (destino == null) return Respuesta(codigo, "", "", url)
                url = URL(url, destino)   // también sigue saltos de http a https
                saltos++
                continue
            }
            val tipo = (con.contentType ?: "").lowercase()
            val texto = if (codigo in 200..299 && !soloInicio) {
                val datos = con.inputStream.use { ent ->
                    val buf = ByteArray(65536)
                    var total = 0
                    while (total < buf.size) {
                        val n = ent.read(buf, total, buf.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    buf.copyOf(total)
                }
                String(datos, Charsets.UTF_8)
            } else ""
            con.disconnect()
            return Respuesta(codigo, tipo, texto, url)
        }
    }
}
