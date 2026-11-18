package ec.jat.tv

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Un canal de la lista M3U. */
data class Canal(
    val nombre: String,
    val url: String,
    val logo: String = "",
    val grupo: String = "",
    val userAgent: String = "",
    val referer: String = ""
)

object M3u {

    private val atributo = Regex("""([\w-]+)="([^"]*)"""")

    /** Lee el texto de una lista M3U y devuelve los canales. */
    fun leer(texto: String): List<Canal> {
        val canales = ArrayList<Canal>()
        var nombre = ""
        var logo = ""
        var grupo = ""
        var ua = ""
        var ref = ""
        var hayInfo = false

        for (crudo in texto.lineSequence()) {
            val linea = crudo.trim().removePrefix("﻿")
            if (linea.isEmpty()) continue
            when {
                linea.startsWith("#EXTINF", ignoreCase = true) -> {
                    hayInfo = true
                    // el nombre va después de la última coma que está fuera de comillas
                    var comillas = false
                    var corte = -1
                    for (i in linea.indices) {
                        val c = linea[i]
                        if (c == '"') comillas = !comillas
                        else if (c == ',' && !comillas) corte = i
                    }
                    val cabecera = if (corte >= 0) linea.substring(0, corte) else linea
                    nombre = if (corte >= 0) linea.substring(corte + 1).trim() else ""
                    logo = ""; grupo = ""
                    for (m in atributo.findAll(cabecera)) {
                        when (m.groupValues[1].lowercase()) {
                            "tvg-logo" -> logo = m.groupValues[2].trim()
                            "group-title" -> grupo = m.groupValues[2].trim()
                            "tvg-name" -> if (nombre.isEmpty()) nombre = m.groupValues[2].trim()
                        }
                    }
                }
                linea.startsWith("#EXTGRP:", ignoreCase = true) -> grupo = linea.substringAfter(':').trim()
                linea.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val op = linea.substringAfter(':')
                    when {
                        op.startsWith("http-user-agent=", true) -> ua = op.substringAfter('=').trim()
                        op.startsWith("http-referrer=", true) -> ref = op.substringAfter('=').trim()
                        op.startsWith("http-referer=", true) -> ref = op.substringAfter('=').trim()
                    }
                }
                linea.startsWith("#") -> { /* otras etiquetas: se ignoran */ }
                else -> {
                    // es la dirección del canal
                    var url = linea
                    // formato "url|User-Agent=...&Referer=..."
                    if (url.contains('|')) {
                        val extra = url.substringAfter('|')
                        url = url.substringBefore('|')
                        for (p in extra.split('&')) {
                            val k = p.substringBefore('=').trim().lowercase()
                            val v = p.substringAfter('=', "").trim()
                            if (k == "user-agent") ua = v
                            if (k == "referer" || k == "referrer") ref = v
                        }
                    }
                    val n = if (hayInfo && nombre.isNotEmpty()) nombre else url.substringAfterLast('/').ifEmpty { url }
                    canales.add(Canal(n, url, logo, grupo, ua, ref))
                    nombre = ""; logo = ""; grupo = ""; ua = ""; ref = ""; hayInfo = false
                }
            }
        }
        return canales
    }

    /** Descarga el texto de una lista desde internet (no llamar en el hilo principal). */
    fun descargar(direccion: String): String {
        var url = URL(direccion)
        var saltos = 0
        while (true) {
            val con = url.openConnection() as HttpURLConnection
            con.connectTimeout = 15000
            con.readTimeout = 30000
            con.instanceFollowRedirects = true
            con.setRequestProperty("User-Agent", Reproduccion.USER_AGENT)
            val codigo = con.responseCode
            if (codigo in 300..399 && saltos < 5) {
                val destino = con.getHeaderField("Location") ?: throw Exception("Redirección sin destino")
                url = URL(url, destino)
                saltos++
                con.disconnect()
                continue
            }
            if (codigo !in 200..299) throw Exception("El servidor respondió $codigo")
            return BufferedReader(InputStreamReader(con.inputStream, Charsets.UTF_8)).use { it.readText() }
        }
    }
}
