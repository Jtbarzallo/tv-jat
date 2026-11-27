package ec.jat.tv

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnóstico: anota qué pasa en Android Auto (qué pide el carro, qué canal se tocó, errores y cierres
 * inesperados). Se ve en el celular: ⋮ → 🔧 Diagnóstico Android Auto (con botón para compartir).
 */
object Registro {
    private const val ARCHIVO = "diagnostico.txt"
    private const val MAX_LINEAS = 400
    private var instalado = false
    private var app: Context? = null

    @Synchronized
    fun anotar(ctx: Context, texto: String) {
        try {
            val f = File(ctx.applicationContext.filesDir, ARCHIVO)
            val hora = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
            f.appendText("$hora  $texto\n")
            if (f.length() > 120_000) {
                val l = f.readLines()
                f.writeText(l.takeLast(MAX_LINEAS).joinToString("\n", postfix = "\n"))
            }
        } catch (e: Exception) {}
    }

    fun leer(ctx: Context): String = try {
        File(ctx.filesDir, ARCHIVO).takeIf { it.exists() }?.readText()?.ifBlank { null }
    } catch (e: Exception) { null } ?: "Todavía no hay nada anotado.\nConecta el celular al carro, abre JTV y toca un canal; luego vuelve aquí."

    fun borrar(ctx: Context) { try { File(ctx.filesDir, ARCHIVO).delete() } catch (e: Exception) {} }

    /** Si la app se cierra de golpe, deja anotado el motivo. */
    fun instalar(ctx: Context) {
        if (instalado) return
        instalado = true
        app = ctx.applicationContext
        val previo = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, e ->
            val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
            app?.let { anotar(it, "❌ LA APP SE CERRÓ: " + sw.toString().lines().take(25).joinToString("\n    ")) }
            previo?.uncaughtException(hilo, e)
        }
    }
}
