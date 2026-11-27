package ec.jat.tv

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** "Mis Películas": las películas del celular al estilo Netflix. */
class PeliculasActivity : Activity() {

    companion object {
        private const val PEDIR_PERMISO = 10
        private const val FONDO = Jtv.FONDO
        private const val ROJO = Jtv.ELECTRICO
    }

    private var todas: List<Pelicula> = emptyList()
    private lateinit var contenido: LinearLayout
    private lateinit var buscar: EditText
    private lateinit var subtitulo: TextView
    private var cargada = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val permiso: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
                else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Jtv.NEGRO
        window.navigationBarColor = FONDO
        construir()
        if (checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED) cargar()
        else requestPermissions(arrayOf(permiso), PEDIR_PERMISO)
    }

    override fun onResume() {
        super.onResume()
        // al volver del reproductor se actualiza "Seguir viendo"
        if (cargada) dibujar()
    }

    override fun onRequestPermissionsResult(code: Int, permisos: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, permisos, res)
        if (code != PEDIR_PERMISO) return
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) cargar()
        else sinPermiso()
    }

    // ---------------- pantalla (diseño JTV) ----------------
    private fun construir() {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Jtv.Fondo()
        }
        val cabecera = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(4))
        }
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val marca = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        marca.addView(Jtv.logo(this, 24), LinearLayout.LayoutParams(-2, dp(24)))
        marca.addView(View(this).apply { background = Jtv.forma(this@PeliculasActivity, 1f, 0f, intArrayOf(Jtv.LINEA)) },
            LinearLayout.LayoutParams(dp(1), dp(20)).apply { setMargins(dp(10), 0, dp(10), 0) })
        marca.addView(Jtv.titular(this, "Películas", 21f))
        textos.addView(marca)
        subtitulo = TextView(this).apply { setTextColor(Jtv.TEXTO2); Jtv.sp(this, 13f); setPadding(0, dp(4), 0, 0) }
        textos.addView(subtitulo)
        cabecera.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
        cabecera.addView(Jtv.botonSecundario(this, "📺  TV") {
            startActivity(Intent(this@PeliculasActivity, MainActivity::class.java))
        }.apply { setPadding(dp(16), 0, dp(16), 0); Jtv.sp(this, 14f) }, LinearLayout.LayoutParams(-2, dp(44)))
        raiz.addView(cabecera)
        Jtv.aparecer(cabecera)

        val fondoBuscar = Jtv.forma(this, 14f, 10f, intArrayOf(Jtv.conAlfa(Jtv.SUPERFICIE, 0.92f)), Jtv.LINEA, 1.2f)
        buscar = EditText(this).apply {
            hint = "🔍  Buscar película"
            setHintTextColor(Jtv.TEXTO3)
            setTextColor(Color.WHITE)
            Jtv.sp(this, 15f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            background = fondoBuscar
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnFocusChangeListener { _, f -> fondoBuscar.borde = if (f) Jtv.ELECTRICO else Jtv.LINEA }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (cargada) dibujar() }
            })
        }
        raiz.addView(buscar, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(16), dp(10), dp(16), dp(8))
        })

        contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(28))
        }
        raiz.addView(ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(contenido)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(raiz)
    }

    private fun aviso(texto: String, boton: String? = null, accion: (() -> Unit)? = null) {
        contenido.removeAllViews()
        contenido.addView(TextView(this).apply {
            text = texto
            setTextColor(Jtv.TEXTO2)
            Jtv.sp(this, 16f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(60), dp(24), dp(18))
        })
        if (boton != null) contenido.addView(Jtv.botonPrimario(this, boton) { accion?.invoke() },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun sinPermiso() = aviso(
        "Para mostrar tus películas, la app necesita permiso para ver los videos del celular.",
        "Dar permiso"
    ) {
        if (shouldShowRequestPermissionRationale(permiso) || checkSelfPermission(permiso) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(permiso), PEDIR_PERMISO)
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
        } catch (e: Exception) { }
    }

    private fun cargar() {
        aviso("Buscando tus películas…")
        thread {
            val lista = try { Peliculas.buscar(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                todas = lista
                cargada = true
                dibujar()
            }
        }
    }

    private fun dibujar() {
        val q = buscar.text.toString().trim().lowercase()
        subtitulo.text = "${todas.size} videos en tu celular"
        if (todas.isEmpty()) {
            aviso("No encontré películas en el celular.\n\nCopia tus películas (mp4, mkv, avi…) a cualquier carpeta, " +
                "por ejemplo Películas o Descargas, y vuelve a abrir.", "🔄 Buscar otra vez") { cargar() }
            return
        }
        contenido.removeAllViews()

        if (q.isNotEmpty()) {
            val res = todas.filter { it.titulo.lowercase().contains(q) || it.archivo.lowercase().contains(q) }
            if (res.isEmpty()) aviso("No hay películas con \"$q\".")
            else cuadricula("Resultados (${res.size})", res)
            return
        }

        val seguir = todas.filter { Peliculas.posicion(this, it) > 0 }
            .sortedByDescending { Peliculas.ultimaVez(this, it) }
        val destacada = seguir.firstOrNull() ?: todas.first()
        portada(destacada, seguir.isNotEmpty())

        if (seguir.isNotEmpty()) fila("Seguir viendo", seguir)
        fila("Agregadas recientemente", todas.take(20))
        todas.groupBy { it.carpeta }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (carpeta, lista) ->
            if (todas.size > 20 || lista.size < todas.size) fila("📁 $carpeta", lista.sortedBy { it.titulo.lowercase() })
        }
        val vistas = todas.filter { Peliculas.vista(this, it) }
        if (vistas.isNotEmpty()) fila("✔ Ya vistas", vistas)
        cuadricula("Todas (A-Z)", todas.sortedBy { it.titulo.lowercase() })
    }

    // Película grande arriba (banner principal con el estilo JTV)
    private fun portada(p: Pelicula, continuar: Boolean) {
        val marco = FrameLayout(this).apply {
            background = Jtv.forma(this@PeliculasActivity, 22f, 20f, intArrayOf(Jtv.SUPERFICIE2, Jtv.NAVY), Jtv.LINEA, 1.2f)
            clipToOutline = true
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        Peliculas.miniatura(this, img, p)
        marco.addView(img, FrameLayout.LayoutParams(-1, -1))
        marco.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, Jtv.conAlfa(Jtv.NAVY, 0.55f), Jtv.conAlfa(Jtv.FONDO, 0.97f)))
        }, FrameLayout.LayoutParams(-1, -1))
        // franja diagonal de luz (como el banner de JTV)
        marco.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BL_TR,
                intArrayOf(Jtv.conAlfa(Jtv.ELECTRICO, 0.0f), Jtv.conAlfa(Jtv.ELECTRICO, 0.28f), Jtv.conAlfa(Jtv.VIOLETA, 0.0f)))
        }, FrameLayout.LayoutParams(-1, -1))
        val abajo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(18))
        }
        abajo.addView(Jtv.etiqueta(this, if (continuar) "Seguir viendo" else "Destacada"))
        abajo.addView(Jtv.titular(this, p.titulo, 24f).apply { maxLines = 2 }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        abajo.addView(TextView(this).apply {
            text = "${Peliculas.duracionTexto(p.duracionMs)}  ·  ${p.carpeta}"
            setTextColor(Jtv.TEXTO2)
            Jtv.sp(this, 13f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val avance = Peliculas.avance(this, p)
        if (continuar && avance > 0f && avance < 1f) {
            val barra = FrameLayout(this).apply { background = Jtv.redondo(this@PeliculasActivity, 2f, intArrayOf(0x44FFFFFF)) }
            barra.addView(View(this).apply { background = Jtv.redondo(this@PeliculasActivity, 2f, intArrayOf(Jtv.CIELO, Jtv.ELECTRICO, Jtv.VIOLETA)) },
                FrameLayout.LayoutParams((dp(180) * avance).toInt().coerceAtLeast(dp(6)), -1))
            abajo.addView(barra, LinearLayout.LayoutParams(dp(180), dp(4)).apply { topMargin = dp(10) })
        }
        abajo.addView(Jtv.botonPrimario(this, if (continuar) "▶  Continuar" else "▶  Reproducir") { reproducir(listOf(p), 0) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        marco.addView(abajo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val ancho = resources.displayMetrics.widthPixels - dp(32)
        val alto = (ancho * 0.62).toInt().coerceAtMost((resources.displayMetrics.heightPixels * 0.55).toInt())
        val env = FrameLayout(this).apply { setPadding(dp(16), dp(6), dp(16), dp(4)) }
        env.addView(marco, FrameLayout.LayoutParams(-1, alto))
        Jtv.sombraAzul(marco, 10f)
        contenido.addView(env)
        Jtv.aparecer(marco, 80)
    }

    private fun tituloFila(t: String): View {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(10))
        }
        caja.addView(View(this).apply { background = Jtv.forma(this@PeliculasActivity, 2f, 2f, intArrayOf(Jtv.CIELO, Jtv.VIOLETA), diagonal = false) },
            LinearLayout.LayoutParams(dp(4), dp(18)).apply { marginEnd = dp(10) })
        caja.addView(Jtv.titular(this, t, 17f))
        return caja
    }

    // Fila que se desliza de lado
    private fun fila(titulo: String, lista: List<Pelicula>) {
        contenido.addView(tituloFila(titulo))
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        lista.forEachIndexed { i, p -> caja.addView(tarjeta(p, lista, i, dp(150))) }
        contenido.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(caja)
        })
    }

    // Cuadrícula de 2 columnas
    private fun cuadricula(titulo: String, lista: List<Pelicula>) {
        contenido.addView(tituloFila(titulo))
        val columnas = if (resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels) 4 else 2
        val ancho = (resources.displayMetrics.widthPixels - dp(24)) / columnas - dp(8)
        lista.chunked(columnas).forEachIndexed { r, grupo ->
            val renglon = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), 0, dp(12), dp(6))
            }
            grupo.forEachIndexed { c, p -> renglon.addView(tarjeta(p, lista, r * columnas + c, ancho)) }
            contenido.addView(renglon)
        }
    }

    private fun tarjeta(p: Pelicula, lista: List<Pelicula>, indice: Int, ancho: Int): View {
        val alto = ancho * 9 / 16
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ancho, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
            setOnClickListener { reproducir(lista, indice) }
            setOnLongClickListener { opciones(p); true }
        }
        val marco = FrameLayout(this).apply {
            background = Jtv.forma(this@PeliculasActivity, 14f, 11f, Jtv.TARJETA, Jtv.LINEA, 1f)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        Jtv.vivo(tarjeta, 0.95f, android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        tarjeta.foreground = null
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        Peliculas.miniatura(this, img, p)
        marco.addView(img, FrameLayout.LayoutParams(-1, -1))
        marco.addView(TextView(this).apply {
            text = Peliculas.duracionTexto(p.duracionMs)
            setTextColor(Color.WHITE)
            textSize = 10f
            typeface = Jtv.textoFuerte(this@PeliculasActivity)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = Jtv.forma(this@PeliculasActivity, 5f, 4f, intArrayOf(Jtv.conAlfa(Jtv.NAVY, 0.85f)))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(5), dp(7)) })
        if (Peliculas.vista(this, p)) marco.addView(TextView(this).apply {
            text = "✔"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = Jtv.forma(this@PeliculasActivity, 5f, 4f, intArrayOf(Jtv.EXITO, 0xFF1FA874.toInt()))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
        val avance = Peliculas.avance(this, p)
        if (avance > 0f && avance < 1f) {
            val barra = FrameLayout(this).apply { setBackgroundColor(0x55FFFFFF) }
            barra.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Jtv.CIELO, Jtv.ELECTRICO, Jtv.VIOLETA)) },
                FrameLayout.LayoutParams((ancho * avance).toInt().coerceAtLeast(dp(4)), -1))
            marco.addView(barra, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
        }
        tarjeta.addView(marco, LinearLayout.LayoutParams(-1, alto))
        tarjeta.addView(TextView(this).apply {
            text = p.titulo
            setTextColor(Jtv.HIELO)
            textSize = 13f
            typeface = Jtv.textoFuerte(this@PeliculasActivity)
            maxLines = 2
            setPadding(dp(4), dp(6), dp(2), dp(8))
        })
        return tarjeta
    }

    private fun opciones(p: Pelicula) {
        val vista = Peliculas.vista(this, p)
        val enCurso = Peliculas.posicion(this, p) > 0
        val ops = mutableListOf("▶ Reproducir desde el inicio")
        ops.add(if (vista) "Marcar como no vista" else "✔ Marcar como vista")
        if (enCurso) ops.add("Quitar de \"Seguir viendo\"")
        ops.add("ℹ️ Información")
        AlertDialog.Builder(this)
            .setTitle(p.titulo)
            .setItems(ops.toTypedArray()) { _, i ->
                when (ops[i]) {
                    "▶ Reproducir desde el inicio" -> { Peliculas.marcar(this, p, false); reproducir(listOf(p), 0) }
                    "✔ Marcar como vista" -> { Peliculas.marcar(this, p, true); dibujar() }
                    "Marcar como no vista", "Quitar de \"Seguir viendo\"" -> { Peliculas.marcar(this, p, false); dibujar() }
                    else -> AlertDialog.Builder(this)
                        .setTitle(p.titulo)
                        .setMessage("Archivo: ${p.archivo}\nCarpeta: ${p.carpeta}\n" +
                            "Duración: ${Peliculas.duracionTexto(p.duracionMs)}\n" +
                            "Tamaño: ${"%.2f".format(p.tamano / 1_073_741_824.0)} GB")
                        .setPositiveButton("OK", null).show()
                }
            }
            .show()
    }

    private fun reproducir(lista: List<Pelicula>, indice: Int) {
        CineEstado.lista = lista
        CineEstado.inicio = indice
        startActivity(Intent(this, PeliculaPlayerActivity::class.java))
    }
}
