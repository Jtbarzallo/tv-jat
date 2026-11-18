package ec.jat.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        private const val PEDIR_LISTA = 1
        private const val PEDIR_EXPORTAR = 2
        private const val PEDIR_IMPORTAR = 3
        private const val TODOS = "Todos los canales"
        private const val FAVORITOS = "★ Favoritos"
        private const val FONDO = 0xFF14182C.toInt()
        private const val TARJETA = 0xFF1E2440.toInt()
        private const val ACENTO = 0xFF4FD1C5.toInt()
    }

    private var canales: List<Canal> = emptyList()
    private var filtrados: List<Canal> = emptyList()
    private var favoritos: MutableSet<String> = mutableSetOf()
    private var caidos: Set<String> = emptySet()
    private var revisando = false
    private var grupos: List<String> = listOf(TODOS, FAVORITOS)

    private lateinit var titulo: TextView
    private lateinit var estado: TextView
    private lateinit var buscar: EditText
    private lateinit var selectorGrupo: Spinner
    private lateinit var lista: ListView
    private val adaptador = Adaptador()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = FONDO
        window.navigationBarColor = FONDO
        construirPantalla()
        favoritos = Datos.favoritos(this)
        caidos = Datos.caidos(this)

        if (!atenderIntent(intent)) {
            val texto = Datos.textoLista(this)
            if (texto != null) {
                mostrarLista(texto)
                if (Datos.fechaRevision(this).isEmpty()) revisarCanales()
            }
            else descargarLista(Datos.LISTA_ECUADOR, "Canales de Ecuador")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        atenderIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        favoritos = Datos.favoritos(this)
        adaptador.notifyDataSetChanged()
    }

    // ---------------- pantalla ----------------
    private fun construirPantalla() {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FONDO)
            setPadding(dp(12), dp(12), dp(12), 0)
        }

        val cabecera = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(this).apply {
            text = "📺 TV JAT"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        titulo = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFB0B8D0.toInt())
            maxLines = 1
        }
        textos.addView(titulo)
        cabecera.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val btnListas = boton("Listas ▾")
        btnListas.setOnClickListener { menuListas(it) }
        cabecera.addView(btnListas)
        val btnMas = boton("⋮")
        btnMas.setOnClickListener { menuMas(it) }
        cabecera.addView(btnMas)
        raiz.addView(cabecera)

        buscar = EditText(this).apply {
            hint = "🔍 Buscar canal"
            setHintTextColor(0xFF7A84A6.toInt())
            setTextColor(Color.WHITE)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            background = redondo(TARJETA)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filtrar()
            })
        }
        raiz.addView(buscar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        selectorGrupo = Spinner(this)
        selectorGrupo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = filtrar()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        raiz.addView(selectorGrupo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        estado = TextView(this).apply {
            setTextColor(0xFFB0B8D0.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
        }
        raiz.addView(estado)

        lista = ListView(this).apply {
            adapter = adaptador
            divider = null
            dividerHeight = dp(6)
            setOnItemClickListener { _, _, pos, _ -> reproducir(pos) }
        }
        raiz.addView(lista, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })

        setContentView(raiz)
    }

    private fun boton(texto: String) = Button(this).apply {
        text = texto
        isAllCaps = false
        setTextColor(FONDO)
        background = redondo(ACENTO)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(14), 0, dp(14), 0)
        layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(8) }
    }

    private fun redondo(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
    }

    private fun mensaje(texto: String?) {
        if (texto == null) estado.visibility = View.GONE
        else { estado.text = texto; estado.visibility = View.VISIBLE }
    }

    // ---------------- menús ----------------
    private fun menuListas(ancla: View) {
        val menu = PopupMenu(this, ancla)
        menu.menu.add(0, 1, 0, "🇪🇨 Canales de Ecuador (gratis)")
        menu.menu.add(0, 4, 0, "🌎 Canales en español (gratis)")
        menu.menu.add(0, 2, 1, "📂 Abrir archivo M3U…")
        menu.menu.add(0, 3, 2, "🔗 Pegar enlace de una lista…")
        val recientes = Datos.recientes(this).filter { it.second != Datos.LISTA_ECUADOR && it.second != Datos.LISTA_ESPANOL }
        recientes.forEachIndexed { i, r -> menu.menu.add(1, 100 + i, 10 + i, "🕘 ${r.first}") }
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> descargarLista(Datos.LISTA_ECUADOR, "Canales de Ecuador")
                4 -> descargarLista(Datos.LISTA_ESPANOL, "Canales en español")
                2 -> abrirArchivo()
                3 -> pedirEnlace()
                else -> recientes.getOrNull(it.itemId - 100)?.let { r -> descargarLista(r.second, r.first) }
            }
            true
        }
        menu.show()
    }

    private fun menuMas(ancla: View) {
        val menu = PopupMenu(this, ancla)
        menu.menu.add(0, 5, 0, "🩺 Revisar qué canales funcionan")
        menu.menu.add(0, 6, 0, if (Datos.ocultarCaidos(this)) "👁 Mostrar también los que no funcionan"
                               else "🙈 Ocultar los que no funcionan")
        menu.menu.add(0, 1, 0, "🔄 Actualizar la lista")
        menu.menu.add(0, 2, 1, "💾 Guardar respaldo como…")
        menu.menu.add(0, 3, 2, "♻️ Recuperar respaldo…")
        menu.menu.add(0, 4, 3, "ℹ️ Acerca de")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    val f = Datos.fuenteLista(this)
                    if (f.startsWith("http")) descargarLista(f, Datos.nombreLista(this))
                    else aviso("Esta lista vino de un archivo. Ábrelo otra vez para actualizarla.")
                }
                2 -> startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, Datos.NOMBRE_RESPALDO)
                }, PEDIR_EXPORTAR)
                3 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, PEDIR_IMPORTAR)
                5 -> revisarCanales()
                6 -> {
                    Datos.setOcultarCaidos(this, !Datos.ocultarCaidos(this))
                    filtrar()
                }
                4 -> AlertDialog.Builder(this)
                    .setTitle("TV JAT")
                    .setMessage("Reproductor de listas M3U.\n\n" +
                        "Respaldo automático en Descargas/TVJAT/${Datos.NOMBRE_RESPALDO} " +
                        "(lista, favoritos y último canal).\n\nVersión " +
                        packageManager.getPackageInfo(packageName, 0).versionName)
                    .setPositiveButton("OK", null)
                    .show()
            }
            true
        }
        menu.show()
    }

    private fun abrirArchivo() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, PEDIR_LISTA)
    }

    private fun pedirEnlace() {
        val campo = EditText(this).apply {
            hint = "https://…/lista.m3u"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Enlace de la lista M3U")
            .setView(campo)
            .setPositiveButton("Abrir") { _, _ ->
                val url = campo.text.toString().trim()
                if (url.startsWith("http")) descargarLista(url, url.substringAfterLast('/').ifEmpty { url })
                else aviso("El enlace debe empezar con http:// o https://")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            PEDIR_LISTA -> leerArchivo(uri)
            PEDIR_EXPORTAR -> try {
                Datos.exportar(this, uri); aviso("Respaldo guardado ✔")
            } catch (e: Exception) { aviso("No se pudo guardar: ${e.message}") }
            PEDIR_IMPORTAR -> try {
                val n = Datos.importar(this, uri)
                favoritos = Datos.favoritos(this)
                caidos = Datos.caidos(this)
                Datos.textoLista(this)?.let { mostrarLista(it) }
                aviso("Respaldo recuperado: $n canales ✔")
            } catch (e: Exception) { aviso("No se pudo recuperar: ${e.message}") }
        }
    }

    // ---------------- cargar listas ----------------
    private fun atenderIntent(i: Intent?): Boolean {
        if (i == null) return false
        if (i.action == Intent.ACTION_VIEW && i.data != null) {
            leerArchivo(i.data!!)
            return true
        }
        if (i.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return false
            val url = Regex("""https?://\S+""").find(t)?.value
            if (url != null) { descargarLista(url, url.substringAfterLast('/')); return true }
            if (t.contains("#EXTINF")) { cargarTexto(t, "Lista compartida", "compartida"); return true }
        }
        return false
    }

    private fun leerArchivo(uri: Uri) {
        try {
            val texto = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            cargarTexto(texto, nombreDe(uri), "archivo")
        } catch (e: Exception) {
            aviso("No se pudo abrir el archivo: ${e.message}")
        }
    }

    private fun nombreDe(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (e: Exception) { }
        return uri.lastPathSegment ?: "Lista"
    }

    private fun descargarLista(url: String, nombre: String) {
        mensaje("Descargando lista…")
        thread {
            try {
                val texto = M3u.descargar(url)
                runOnUiThread { cargarTexto(texto, nombre, url) }
            } catch (e: Exception) {
                runOnUiThread {
                    val previa = Datos.textoLista(this)
                    mensaje(if (previa == null) "No se pudo descargar la lista. Revisa el internet." else null)
                    aviso("No se pudo descargar: ${e.message}")
                }
            }
        }
    }

    private fun cargarTexto(texto: String, nombre: String, fuente: String) {
        val leidos = M3u.leer(texto)
        if (leidos.isEmpty()) {
            mensaje(null)
            aviso("Ese archivo no tiene canales M3U")
            return
        }
        Datos.guardarLista(this, texto, nombre, fuente)
        caidos = emptySet()
        mostrarLista(texto)
        aviso("${leidos.size} canales cargados. Revisando cuáles funcionan…")
        revisarCanales()
    }

    private fun mostrarLista(texto: String) {
        canales = M3u.leer(texto)
        ponerTitulo()
        val nombresGrupo = canales.flatMap { it.grupo.split(';') }
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        grupos = listOf(TODOS, FAVORITOS) + nombresGrupo
        selectorGrupo.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, grupos)
        selectorGrupo.visibility = View.VISIBLE
        filtrar()
    }

    private fun ponerTitulo(progreso: String? = null) {
        val base = "${Datos.nombreLista(this).ifEmpty { "Lista" }} · ${canales.size} canales"
        titulo.text = when {
            progreso != null -> "$base · $progreso"
            Datos.fechaRevision(this).isNotEmpty() -> "$base · ${canales.count { it.url !in caidos }} funcionan"
            else -> base
        }
    }

    private fun revisarCanales() {
        if (revisando || canales.isEmpty()) return
        revisando = true
        val lista = canales
        ponerTitulo("🩺 revisando…")
        thread {
            Revisar.revisar(lista,
                progreso = { h, t -> runOnUiThread { ponerTitulo("🩺 revisando $h/$t…") } },
                fin = { muertos ->
                    runOnUiThread {
                        revisando = false
                        if (lista !== canales) return@runOnUiThread   // cambiaron de lista mientras tanto
                        Datos.guardarRevision(this, muertos)
                        caidos = muertos
                        ponerTitulo()
                        filtrar()
                        val ok = lista.count { it.url !in muertos }
                        aviso("✔ Funcionan $ok de ${lista.size} canales" +
                            if (Datos.ocultarCaidos(this) && muertos.isNotEmpty()) ". Los demás quedaron ocultos." else "")
                    }
                })
            runOnUiThread { revisando = false }
        }
    }

    private fun filtrar() {
        val q = buscar.text.toString().trim().lowercase()
        val g = grupos.getOrNull(selectorGrupo.selectedItemPosition) ?: TODOS
        val ocultar = Datos.ocultarCaidos(this)
        filtrados = canales.filter { c ->
            !(ocultar && caidos.contains(c.url)) &&
            (q.isEmpty() || c.nombre.lowercase().contains(q)) &&
                when (g) {
                    TODOS -> true
                    FAVORITOS -> favoritos.contains(c.url)
                    else -> c.grupo.split(';').any { it.trim() == g }
                }
        }
        adaptador.notifyDataSetChanged()
        mensaje(when {
            canales.isEmpty() -> null
            filtrados.isEmpty() && g == FAVORITOS -> "Aún no tienes favoritos. Toca ☆ junto a un canal."
            filtrados.isEmpty() -> "No hay canales con ese nombre."
            else -> null
        })
    }

    private fun reproducir(pos: Int) {
        if (pos !in filtrados.indices) return
        Reproduccion.canales = filtrados
        Reproduccion.inicio = pos
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // ---------------- filas de la lista ----------------
    private inner class Adaptador : BaseAdapter() {
        override fun getCount() = filtrados.size
        override fun getItem(p: Int) = filtrados[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convertView: View?, parent: ViewGroup?): View {
            val fila = (convertView as? LinearLayout) ?: crearFila()
            val c = filtrados[p]
            val logo = fila.getChildAt(0) as ImageView
            val textos = fila.getChildAt(1) as LinearLayout
            val estrella = fila.getChildAt(2) as TextView
            val caido = caidos.contains(c.url)
            (textos.getChildAt(0) as TextView).text =
                (if (caido) "✖ " else if (c.url == Datos.ultimo(this@MainActivity)) "▶ " else "") + c.nombre
            fila.alpha = if (caido) 0.45f else 1f
            val sub = textos.getChildAt(1) as TextView
            sub.text = if (caido) "No responde (última revisión)" else c.grupo
            sub.visibility = if (sub.text.isEmpty()) View.GONE else View.VISIBLE
            Logos.poner(logo, c.logo)
            val fav = favoritos.contains(c.url)
            estrella.text = if (fav) "★" else "☆"
            estrella.setTextColor(if (fav) 0xFFFFC94D.toInt() else 0xFF7A84A6.toInt())
            estrella.setOnClickListener {
                Datos.cambiarFavorito(this@MainActivity, c.url)
                favoritos = Datos.favoritos(this@MainActivity)
                filtrar()
            }
            return fila
        }

        private fun crearFila(): LinearLayout {
            val fila = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = redondo(TARJETA)
                setPadding(dp(10), dp(8), dp(4), dp(8))
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            fila.addView(ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = redondo(0xFF2A3156.toInt())
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }, LinearLayout.LayoutParams(dp(56), dp(42)))
            val textos = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(4), 0)
            }
            textos.addView(TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE); textSize = 16f; maxLines = 2
            })
            textos.addView(TextView(this@MainActivity).apply {
                setTextColor(0xFF8F98B8.toInt()); textSize = 12f; maxLines = 1
            })
            fila.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
            fila.addView(TextView(this@MainActivity).apply {
                textSize = 26f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            return fila
        }
    }
}
