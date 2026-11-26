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
        private const val PEDIR_PERMISO_INSTALAR = 10
        private const val PEDIR_DESINSTALAR = 11
        private const val PEDIR_INSTALAR = 12
        private var avisoCarroMostrado = false
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
        Registro.instalar(this)
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
        revisarVideoCarro()
    }

    // ---------------- TV JAT Video en el carro ----------------
    /** Si TV JAT Video está instalada pero Android Auto la esconde, ofrece arreglarlo. */
    private fun revisarVideoCarro() {
        if (avisoCarroMostrado) return
        val e = InstaladorVideo.estado(this)
        if (!e.instalada || e.lista) return
        avisoCarroMostrado = true
        AlertDialog.Builder(this)
            .setTitle("🚗 TV JAT Video no sale en el carro")
            .setMessage("Android Auto la esconde porque fue instalada desde Chrome y no desde Google Play.\n\n" +
                "TV JAT la puede reinstalar de la forma correcta. Solo toca los botones que vayan saliendo.")
            .setPositiveButton("Arreglar ahora") { _, _ -> instalarVideo() }
            .setNegativeButton("Después", null)
            .show()
    }

    private fun instalarVideo() {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Paso 1: dar permiso")
                .setMessage("En la pantalla que se abre, activa \"Permitir desde esta fuente\" para TV JAT y luego regresa con la flecha ←.")
                .setPositiveButton("Abrir") { _, _ ->
                    startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")), PEDIR_PERMISO_INSTALAR)
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val e = InstaladorVideo.estado(this)
        if (e.instalada && !e.lista) {
            AlertDialog.Builder(this)
                .setTitle("Paso 2: quitar la versión de Chrome")
                .setMessage("Primero hay que quitar la TV JAT Video que instalaste desde Chrome. " +
                    "En la pantalla que se abre toca \"Aceptar\" o \"Desinstalar\".\n\nTus canales, favoritos y películas NO se borran (están en TV JAT).")
                .setPositiveButton("Continuar") { _, _ ->
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                        Uri.parse("package:${InstaladorVideo.PAQUETE}")).putExtra(Intent.EXTRA_RETURN_RESULT, true),
                        PEDIR_DESINSTALAR)
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        descargarEInstalarVideo()
    }

    private fun descargarEInstalarVideo() {
        val texto = TextView(this).apply { textSize = 18f; setPadding(50, 40, 50, 40); text = "Descargando TV JAT Video… 0 %" }
        val dlg = AlertDialog.Builder(this).setTitle("Paso 3: descargar").setView(texto).setCancelable(false).show()
        thread {
            try {
                InstaladorVideo.descargar(this) { p -> runOnUiThread { texto.text = "Descargando TV JAT Video… $p %" } }
                runOnUiThread { dlg.dismiss(); lanzarInstalador() }
            } catch (ex: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    AlertDialog.Builder(this).setTitle("No se pudo descargar")
                        .setMessage("Revisa que tengas internet y vuelve a intentar.\n\n(${ex.message})")
                        .setPositiveButton("Reintentar") { _, _ -> descargarEInstalarVideo() }
                        .setNegativeButton("Cerrar", null).show()
                }
            }
        }
    }

    private fun lanzarInstalador() {
        val uri = InstaladorVideo.URI_APK
        @Suppress("DEPRECATION")
        val base = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, InstaladorVideo.PLAY)
            putExtra("installerPackageName", InstaladorVideo.PLAY)
            putExtra("android.intent.extra.INSTALL_REASON", 1)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        Registro.anotar(this, "Instalar TV JAT Video: lanzando instalador")
        // Primero el instalador de Google directo (el de Xiaomi a veces no pasa el dato de "Google Play")
        for (p in listOf("com.google.android.packageinstaller", "com.android.packageinstaller")) {
            val i = Intent(base).setPackage(p)
            if (i.resolveActivity(packageManager) != null) {
                try {
                    grantUriPermission(p, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivityForResult(i, PEDIR_INSTALAR)
                    return
                } catch (ex: Exception) {
                    Registro.anotar(this, "Instalador $p falló: ${ex.message}")
                }
            }
        }
        startActivityForResult(base, PEDIR_INSTALAR)
    }

    private fun resultadoVideo() {
        val e = InstaladorVideo.estado(this)
        Registro.anotar(this, InstaladorVideo.resumen(this))
        val b = AlertDialog.Builder(this)
        when {
            e.lista -> b.setTitle("✅ Listo")
                .setMessage("TV JAT Video quedó instalada como de Google Play. Ahora Android Auto sí la muestra.\n\n" +
                    "1. Desconecta el celular del carro.\n2. Vuelve a conectarlo.\n3. Toca los cuadritos ▦ y busca la claqueta roja \"TV JAT Video\".")
                .setPositiveButton("OK", null)
            !e.instalada -> b.setTitle("No se terminó de instalar")
                .setMessage("La instalación se canceló. Vuelve a intentar y toca \"Instalar\" cuando lo pida.")
                .setPositiveButton("Reintentar") { _, _ -> instalarVideo() }
                .setNegativeButton("Cerrar", null)
            else -> b.setTitle("⚠️ Se instaló, pero el celular no la marcó como de Google Play")
                .setMessage(InstaladorVideo.resumen(this) + "\n\nToca \"Compartir\" y mándale este mensaje a Claude.")
                .setPositiveButton("Compartir") { _, _ ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "TV JAT " +
                            packageManager.getPackageInfo(packageName, 0).versionName + " / Android " +
                            android.os.Build.VERSION.RELEASE + " / " + android.os.Build.MANUFACTURER + " " +
                            android.os.Build.MODEL + "\n" + InstaladorVideo.resumen(this@MainActivity) + "\n\n" + Registro.leer(this@MainActivity))
                    }, "Compartir"))
                }
                .setNegativeButton("Cerrar", null)
        }
        b.show()
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

        val btnPelis = boton("🎬")
        btnPelis.setOnClickListener { startActivity(Intent(this, PeliculasActivity::class.java)) }
        cabecera.addView(btnPelis)
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
        val sub = menu.menu.addSubMenu(0, 5, 0, "🌎 Más listas en español latino ▸")
        Datos.LISTAS_LATINAS.forEachIndexed { i, l -> sub.add(2, 200 + i, i, l.first) }
        menu.menu.add(0, 2, 1, "📂 Abrir archivo M3U…")
        menu.menu.add(0, 3, 2, "🔗 Pegar enlace de una lista…")
        val recientes = Datos.recientes(this).filter { r -> r.second != Datos.LISTA_ECUADOR && r.second != Datos.LISTA_ESPANOL &&
                Datos.LISTAS_LATINAS.none { it.second == r.second } }
        recientes.forEachIndexed { i, r -> menu.menu.add(1, 100 + i, 10 + i, "🕘 ${r.first}") }
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> descargarLista(Datos.LISTA_ECUADOR, "Canales de Ecuador")
                4 -> descargarLista(Datos.LISTA_ESPANOL, "Canales en español")
                2 -> abrirArchivo()
                3 -> pedirEnlace()
                5 -> {}
                in 200..299 -> Datos.LISTAS_LATINAS.getOrNull(it.itemId - 200)?.let { l ->
                    descargarLista(l.second, l.first.substringAfter(' ').substringBefore(" ("))
                }
                else -> recientes.getOrNull(it.itemId - 100)?.let { r -> descargarLista(r.second, r.first) }
            }
            true
        }
        menu.show()
    }

    private fun menuMas(ancla: View) {
        val menu = PopupMenu(this, ancla)
        menu.menu.add(0, 8, 0, "🚗 Poner TV JAT Video en el carro")
        menu.menu.add(0, 9, 0, "🎤 Activar micrófono del carro")
        menu.menu.add(0, 5, 0, "🩺 Revisar qué canales funcionan")
        menu.menu.add(0, 6, 0, if (Datos.ocultarCaidos(this)) "👁 Mostrar también los que no funcionan"
                               else "🙈 Ocultar los que no funcionan")
        menu.menu.add(0, 1, 0, "🔄 Actualizar la lista")
        menu.menu.add(0, 2, 1, "💾 Guardar respaldo como…")
        menu.menu.add(0, 3, 2, "♻️ Recuperar respaldo…")
        menu.menu.add(0, 7, 3, "🔧 Diagnóstico Android Auto")
        menu.menu.add(0, 4, 4, "ℹ️ Acerca de")
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
                7 -> verDiagnostico()
                9 -> try {
                    startActivity(Intent().setClassName("ec.jat.tv.video", "ec.jat.tv.PermisosActivity"))
                } catch (e: Exception) {
                    // TV JAT Video viejo (sin esta pantalla): abrir sus ajustes para dar el permiso a mano
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:ec.jat.tv.video")))
                        aviso("En Permisos → Micrófono → Permitir")
                    } catch (e2: Exception) { aviso("Primero instala TV JAT Video: ⋮ → Poner TV JAT Video en el carro") }
                }
                8 -> {
                    val e = InstaladorVideo.estado(this)
                    if (e.lista) AlertDialog.Builder(this).setTitle("🚗 TV JAT Video")
                        .setMessage("Ya está bien instalada (como de Google Play). Debe salir en el carro como la claqueta roja.\n\n" +
                            "¿Quieres reinstalarla igual (por ejemplo, para actualizarla)?")
                        .setPositiveButton("Reinstalar") { _, _ -> descargarEInstalarVideo() }
                        .setNegativeButton("No", null).show()
                    else instalarVideo()
                }
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

    /** Muestra lo que pasó en Android Auto (para saber qué falla) y deja compartirlo. */
    private fun verDiagnostico() {
        val texto = "TV JAT " + packageManager.getPackageInfo(packageName, 0).versionName + "\n" +
            InstaladorVideo.resumen(this) + "\n\n" + Registro.leer(this)
        val vista = TextView(this).apply {
            text = texto
            setTextIsSelectable(true)
            textSize = 14f
            setPadding(40, 20, 40, 20)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(vista) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        AlertDialog.Builder(this)
            .setTitle("🔧 Diagnóstico Android Auto")
            .setView(scroll)
            .setPositiveButton("Compartir") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, texto)
                }, "Compartir diagnóstico"))
            }
            .setNeutralButton("Borrar") { _, _ -> Registro.borrar(this) }
            .setNegativeButton("Cerrar", null)
            .show()
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
        when (requestCode) {
            PEDIR_PERMISO_INSTALAR -> {
                if (android.os.Build.VERSION.SDK_INT >= 26 && packageManager.canRequestPackageInstalls()) instalarVideo()
                else aviso("Falta activar el permiso para que TV JAT pueda instalar")
                return
            }
            PEDIR_DESINSTALAR -> {
                if (!InstaladorVideo.estado(this).instalada) descargarEInstalarVideo()
                else aviso("No se quitó la versión anterior. Vuelve a intentar.")
                return
            }
            PEDIR_INSTALAR -> { resultadoVideo(); return }
        }
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
