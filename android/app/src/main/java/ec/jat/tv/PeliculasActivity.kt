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
        private const val FONDO = 0xFF0B0B0F.toInt()
        private const val ROJO = 0xFFE50914.toInt()
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
        window.statusBarColor = FONDO
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

    // ---------------- pantalla ----------------
    private fun construir() {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FONDO)
        }
        val cabecera = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(4))
        }
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(this).apply {
            text = "MIS PELÍCULAS"
            setTextColor(ROJO)
            textSize = 24f
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            letterSpacing = 0.05f
        })
        subtitulo = TextView(this).apply { setTextColor(0xFF9A9AA5.toInt()); textSize = 13f }
        textos.addView(subtitulo)
        cabecera.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
        cabecera.addView(Button(this).apply {
            text = "📺 TV"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(0xFF26262E.toInt()); cornerRadius = dp(8).toFloat() }
            setOnClickListener {
                startActivity(Intent(this@PeliculasActivity, MainActivity::class.java))
            }
        }, LinearLayout.LayoutParams(-2, dp(40)))
        raiz.addView(cabecera)

        buscar = EditText(this).apply {
            hint = "🔍 Buscar película"
            setHintTextColor(0xFF6E6E7A.toInt())
            setTextColor(Color.WHITE)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply { setColor(0xFF1C1C22.toInt()); cornerRadius = dp(8).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (cargada) dibujar() }
            })
        }
        raiz.addView(buscar, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(16), dp(8), dp(16), dp(6))
        })

        contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        raiz.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(contenido)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(raiz)
    }

    private fun aviso(texto: String, boton: String? = null, accion: (() -> Unit)? = null) {
        contenido.removeAllViews()
        contenido.addView(TextView(this).apply {
            text = texto
            setTextColor(0xFFCCCCD5.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(60), dp(24), dp(16))
        })
        if (boton != null) contenido.addView(Button(this).apply {
            text = boton
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(ROJO); cornerRadius = dp(8).toFloat() }
            setOnClickListener { accion?.invoke() }
        }, LinearLayout.LayoutParams(-2, dp(46)).apply { gravity = Gravity.CENTER_HORIZONTAL })
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

    // Película grande arriba
    private fun portada(p: Pelicula, continuar: Boolean) {
        val marco = FrameLayout(this)
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1C1C22.toInt()) }
        Peliculas.miniatura(this, img, p)
        marco.addView(img, FrameLayout.LayoutParams(-1, -1))
        marco.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xEE0B0B0F.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        val abajo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(14))
        }
        abajo.addView(TextView(this).apply {
            text = p.titulo
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            maxLines = 2
        })
        abajo.addView(TextView(this).apply {
            text = "${Peliculas.duracionTexto(p.duracionMs)} · ${p.carpeta}"
            setTextColor(0xFFBBBBC5.toInt())
            textSize = 13f
        })
        abajo.addView(Button(this).apply {
            text = if (continuar) "▶  Continuar" else "▶  Reproducir"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(6).toFloat() }
            setOnClickListener { reproducir(listOf(p), 0) }
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(10) })
        marco.addView(abajo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        contenido.addView(marco, LinearLayout.LayoutParams(-1, (resources.displayMetrics.widthPixels * 0.62).toInt()))
    }

    private fun tituloFila(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 17f
        setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        setPadding(dp(16), dp(18), dp(16), dp(8))
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
            background = GradientDrawable().apply { setColor(0xFF1C1C22.toInt()); cornerRadius = dp(6).toFloat() }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        Peliculas.miniatura(this, img, p)
        marco.addView(img, FrameLayout.LayoutParams(-1, -1))
        marco.addView(TextView(this).apply {
            text = Peliculas.duracionTexto(p.duracionMs)
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(dp(5), dp(1), dp(5), dp(1))
            background = GradientDrawable().apply { setColor(0xAA000000.toInt()); cornerRadius = dp(4).toFloat() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(5), dp(7)) })
        if (Peliculas.vista(this, p)) marco.addView(TextView(this).apply {
            text = "✔"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = GradientDrawable().apply { setColor(0xCC2E7D32.toInt()); cornerRadius = dp(4).toFloat() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
        val avance = Peliculas.avance(this, p)
        if (avance > 0f && avance < 1f) {
            val barra = FrameLayout(this).apply { setBackgroundColor(0x88FFFFFF.toInt()) }
            barra.addView(View(this).apply { setBackgroundColor(ROJO) },
                FrameLayout.LayoutParams((ancho * avance).toInt().coerceAtLeast(dp(4)), -1))
            marco.addView(barra, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
        }
        tarjeta.addView(marco, LinearLayout.LayoutParams(-1, alto))
        tarjeta.addView(TextView(this).apply {
            text = p.titulo
            setTextColor(0xFFE5E5EA.toInt())
            textSize = 13f
            maxLines = 2
            setPadding(dp(2), dp(4), dp(2), dp(6))
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
