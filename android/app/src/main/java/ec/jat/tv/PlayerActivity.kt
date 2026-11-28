package ec.jat.tv

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.TextUtils
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reproductor de canales del celular — igual que JTV Video en el carro:
 * tocar el video muestra/esconde la interfaz (lista a la derecha, botones redondos, barra de información),
 * 🎤 Voz para buscar, ⭐ favorito, línea de tiempo con grabador, calidad, sin internet reintenta el mismo canal.
 * Del celular se conservan: 📡 Ver en TV (Google Cast), ventana flotante (PiP) y control remoto.
 */
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var vista: PlayerView
    private lateinit var aviso: TextView
    private lateinit var capa: FrameLayout
    private lateinit var capaTv: LinearLayout
    private lateinit var textoTv: TextView
    /** true mientras el canal se ve en la TV (el celular no descarga nada). */
    private var enTv = false
    private val oyenteTv = object : OyenteTv() {
        override fun onSessionEnded(s: com.google.android.gms.cast.framework.CastSession, error: Int) {
            if (enTv) salirDeTv(volverAlCelular = false, cerrarSesion = false)
        }
    }
    private val canales = Reproduccion.canales
    private val porUrl: Map<String, Canal> = canales.associateBy { it.url }
    private val probadosHls = HashSet<String>()
    private val manejador = Handler(Looper.getMainLooper())
    private var errorSeguidos = 0

    /** Canal que está sonando (para mandar User-Agent / Referer propios del canal). */
    @Volatile private var canalActual: Canal? = null
    @Volatile private var calidadMem = "auto"

    // colores de JTV (los mismos del carro)
    private val AZUL_SEL = 0xFF1A2A78.toInt()
    private val ACENTO = Jtv.CIELO
    private val ROJO = Jtv.VIVO
    private val GRIS = Jtv.TEXTO2
    private val BOTON = Jtv.conAlfa(Jtv.SUPERFICIE2, 0.85f)

    // piezas de la interfaz
    private lateinit var lista: ListView
    private lateinit var listaTitulo: TextView
    private lateinit var chipCanales: TextView
    private lateinit var chipTv: TextView
    private lateinit var btnPausa: ImageView
    private lateinit var infoNombre: TextView
    private lateinit var infoSub: TextView
    private lateinit var infoTiempo: TextView
    private lateinit var infoVivo: TextView
    private lateinit var infoCalidad: TextView
    private lateinit var estrella: TextView
    private lateinit var barra: BarraTiempo
    private val adaptador = Adaptador()
    private var capaVisible = false
    private var vistaPanel = "canales"     // canales · grupos · voz · opciones
    private var grupoSel: String? = null    // null = todos · "★" = favoritos
    private var vozTexto = ""
    private var vozResultados: List<Canal> = emptyList()
    private var favMem: Set<String>? = null
    private var modoTamano = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (canales.isEmpty()) { finish(); return }
        AutoService.pausarSiSuena()   // que no suenen dos canales a la vez
        calidadMem = Datos.calidad(this)

        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        vista = PlayerView(this).apply {
            keepScreenOn = true
            useController = false            // la interfaz es la nuestra (igual que en el carro)
        }
        raiz.addView(vista, FrameLayout.LayoutParams(-1, -1))
        // tocar el video: muestra / esconde la interfaz
        raiz.addView(View(this).apply { setOnClickListener { alternarCapa() } }, FrameLayout.LayoutParams(-1, -1))

        aviso = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Jtv.titulo(this@PlayerActivity)
            gravity = Gravity.CENTER
            background = Jtv.forma(this@PlayerActivity, 18f, 14f, intArrayOf(Jtv.conAlfa(Jtv.SUPERFICIE2, 0.92f), Jtv.conAlfa(Jtv.NAVY, 0.92f)), Jtv.LINEA, 1.2f)
            setPadding(dp(26), dp(16), dp(26), dp(16))
            visibility = View.GONE
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        armarCapa(raiz)
        armarCapaTv(raiz)

        setContentView(raiz)
        pantallaCompleta()
        iniciarReproductor()
        mostrarCapa()
    }

    // ================================================================ interfaz (igual al carro)
    private fun redondo(color: Int, radio: Float = -1f) = GradientDrawable().apply {
        if (radio < 0) shape = GradientDrawable.OVAL else cornerRadius = radio
        setColor(color)
    }

    private fun degradado(arribaOscuro: Boolean, oscuro: Int) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (arribaOscuro) intArrayOf(oscuro, 0x00000000) else intArrayOf(0x00000000, oscuro)
    )

    private fun chip(txt: String, accion: () -> Unit) = TextView(this).apply {
        text = txt
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Jtv.textoFuerte(this@PlayerActivity)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setOnClickListener { accion(); tocado() }
        Jtv.vivo(this, 0.92f, Jtv.forma(this@PlayerActivity, 12f, 8f, intArrayOf(Color.WHITE)))
    }

    private class Boton(val vista: LinearLayout, val icono: ImageView?, val texto: TextView)

    private fun boton(icono: Int, glifo: String, etiqueta: String, fondo: Int, tinte: Int, tam: Int, accion: () -> Unit): Boton {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { accion(); tocado() }
        }
        // botones con la forma JTV: el principal con degradado de la marca, cerrar en rojo, el resto de vidrio azul
        val circulo = FrameLayout(this).apply {
            background = when (fondo) {
                Color.WHITE -> Jtv.forma(this@PlayerActivity, tam * 0.32f, tam * 0.22f, Jtv.MARCA)
                ROJO -> Jtv.forma(this@PlayerActivity, tam * 0.32f, tam * 0.22f, intArrayOf(Jtv.VIVO, 0xFFB81E4E.toInt()))
                else -> Jtv.forma(this@PlayerActivity, tam * 0.32f, tam * 0.22f, intArrayOf(Jtv.conAlfa(Jtv.SUPERFICIE2, 0.88f), Jtv.conAlfa(Jtv.NAVY, 0.88f)), Jtv.LINEA, 1.2f)
            }
            if (fondo == Color.WHITE) Jtv.sombraAzul(this, 10f)
        }
        val tinteReal = if (fondo == Color.WHITE) Color.WHITE else tinte
        var img: ImageView? = null
        if (icono != 0) {
            img = ImageView(this).apply { setImageResource(icono); setColorFilter(tinteReal) }
            val m = tam / 4
            circulo.addView(img, FrameLayout.LayoutParams(dp(tam - 2 * m), dp(tam - 2 * m), Gravity.CENTER))
        } else {
            circulo.addView(TextView(this).apply {
                text = glifo; setTextColor(tinteReal); gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, tam * 0.42f)
                setTypeface(typeface, Typeface.BOLD)
            }, FrameLayout.LayoutParams(-1, -1))
        }
        caja.addView(circulo, LinearLayout.LayoutParams(dp(tam), dp(tam)))
        Jtv.vivo(circulo, 0.9f)
        circulo.setOnClickListener { accion(); tocado() }
        val t = TextView(this).apply {
            text = etiqueta; setTextColor(Jtv.HIELO); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Jtv.textoFuerte(this@PlayerActivity)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(0, dp(3), 0, 0)
        }
        caja.addView(t)
        return Boton(caja, img, t)
    }

    private fun miniBoton(glifo: String, etiqueta: String, accion: () -> Unit): Pair<View, TextView> {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { accion(); tocado() }
            Jtv.vivo(this, 0.9f, Jtv.forma(this@PlayerActivity, 12f, 8f, intArrayOf(Color.WHITE)))
        }
        caja.addView(TextView(this).apply { text = glifo; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f) })
        val t = TextView(this).apply { text = etiqueta; setTextColor(GRIS); gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f) }
        caja.addView(t)
        return caja to t
    }

    private fun armarCapa(raiz: FrameLayout) {
        val cp = FrameLayout(this).apply {
            visibility = View.GONE
            setOnClickListener { ocultarCapa() }   // tocar la imagen (fuera de botones) esconde todo
        }

        // ---- barra de arriba
        val arriba = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Jtv.conAlfa(Jtv.NEGRO, 0.92f), Jtv.conAlfa(Jtv.NAVY, 0.55f), 0x00000000))
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { tocado() }
        }
        chipCanales = chip("📺 Canales") { vistaPanel = "canales"; pintarPanel() }
        arriba.addView(chipCanales)
        arriba.addView(chip("🎬 Películas") { startActivity(Intent(this, PeliculasActivity::class.java)) })
        // logo oficial de JTV al centro
        val centroLogo = FrameLayout(this)
        centroLogo.addView(Jtv.logo(this, 26), FrameLayout.LayoutParams(-2, dp(26), Gravity.CENTER))
        arriba.addView(centroLogo, LinearLayout.LayoutParams(0, -1, 1f))
        chipTv = chip("📡 Ver en TV") { Transmitir.elegirTv(this) { pasarATv() } }
        if (Transmitir.esTv(this)) chipTv.visibility = View.GONE
        arriba.addView(chipTv)
        arriba.addView(chip("🎤 Voz") { buscarPorVoz() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE); typeface = Jtv.titulo(this@PlayerActivity)
            background = Jtv.forma(this@PlayerActivity, 14f, 10f, Jtv.MARCA)
            Jtv.sombraAzul(this, 6f)
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        cp.addView(arriba, FrameLayout.LayoutParams(-1, dp(52), Gravity.TOP))

        // ---- lista en franja aparte, a la izquierda (no va encima del video; igual que en el carro)
        val pn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Jtv.SUPERFICIE2, Jtv.NAVY, Jtv.FONDO))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { tocado() }
        }
        listaTitulo = TextView(this).apply {
            setTextColor(ACENTO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Jtv.titulo(this@PlayerActivity)
            setPadding(dp(16), dp(12), dp(14), dp(8))
            setOnClickListener {
                vistaPanel = if (vistaPanel == "canales") "grupos" else "canales"
                pintarPanel(); tocado()
            }
        }
        pn.addView(listaTitulo)
        lista = ListView(this).apply {
            adapter = adaptador
            divider = null
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(6), 0, dp(6), dp(6)); clipToPadding = false
            setOnItemClickListener { _, _, pos, _ -> adaptador.filas.getOrNull(pos)?.accion?.invoke(); tocado() }
            setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: android.widget.AbsListView?, s: Int) { tocado() }
                override fun onScroll(v: android.widget.AbsListView?, a: Int, b: Int, c: Int) {}
            })
        }
        pn.addView(lista, LinearLayout.LayoutParams(-1, 0, 1f))
        raiz.addView(pn, FrameLayout.LayoutParams(anchoFranja(), -1, Gravity.START or Gravity.TOP))
        franja = pn

        // ---- abajo: botones redondos + barra de información
        val abajo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setOnClickListener { tocado() } }
        val controles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, Jtv.conAlfa(Jtv.NAVY, 0.55f), Jtv.conAlfa(Jtv.NEGRO, 0.85f)))
            setPadding(dp(8), dp(10), dp(8), dp(6))
        }
        val lpB = LinearLayout.LayoutParams(0, -2, 1f)
        val guia = boton(0, "☰", "Lista", BOTON, Color.WHITE, 46) { alternarLista() }
        val ant = boton(R.drawable.ic_anterior_jat, "", "Anterior", BOTON, Color.WHITE, 46) { anterior() }
        val pau = boton(R.drawable.ic_pausa_jat, "", "Pausa", Color.WHITE, ROJO, 58) { pausaSigue() }
        val sig = boton(R.drawable.ic_siguiente_jat, "", "Siguiente", BOTON, Color.WHITE, 46) { siguiente() }
        val cer = boton(R.drawable.ic_apagar, "", "Apagar", ROJO, Color.WHITE, 46) { apagar() }
        listOf(guia, ant, pau, sig, cer).forEach { controles.addView(it.vista, lpB) }
        btnPausa = pau.icono!!
        abajo.addView(controles)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Jtv.conAlfa(Jtv.NAVY, 0.97f), Jtv.conAlfa(0xFF0F1A52.toInt(), 0.97f), Jtv.conAlfa(Jtv.NAVY, 0.97f)))
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        estrella = TextView(this).apply {
            text = "☆"; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setOnClickListener { cambiarFavoritoActual(); tocado() }
        }
        info.addView(estrella, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(8) })
        val izq = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoNombre = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Jtv.titulo(this@PlayerActivity)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        infoSub = TextView(this).apply {
            setTextColor(GRIS); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        izq.addView(infoNombre); izq.addView(infoSub)
        info.addView(izq, LinearLayout.LayoutParams(0, -2, 1.1f))
        val centro = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0) }
        barra = BarraTiempo(this).apply {
            alTocar = { f ->
                val p = player
                if (p != null) { Salto.ir(p, f) { avisoBreve("No se pudo ir a ese punto. Volví a EN VIVO") }; actualizarCapa() }
                tocado()
            }
        }
        centro.addView(barra, LinearLayout.LayoutParams(-1, dp(22)))
        val filaT = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        infoTiempo = TextView(this).apply { setTextColor(GRIS); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); maxLines = 1 }
        filaT.addView(infoTiempo, LinearLayout.LayoutParams(0, -2, 1f))
        infoVivo = TextView(this).apply {
            text = "● EN VIVO"; setTextColor(ROJO); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Jtv.titulo(this@PlayerActivity)
            setPadding(dp(6), dp(4), dp(2), dp(4))
            setOnClickListener { irAlDirecto(); tocado() }
        }
        filaT.addView(infoVivo)
        centro.addView(filaT)
        info.addView(centro, LinearLayout.LayoutParams(0, -2, 1.4f))
        val (aj, _) = miniBoton("⚙", "Ajustes") { vistaPanel = "opciones"; pintarPanel() }
        val (ca, caTxt) = miniBoton("📶", "Calidad") { cambiarCalidad() }
        infoCalidad = caTxt
        info.addView(aj); info.addView(ca)
        abajo.addView(info)
        cp.addView(abajo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        raiz.addView(cp, FrameLayout.LayoutParams(-1, -1))
        capa = cp
    }

    // Capa "Viendo en la TV": controles del canal mientras el celular descansa
    private fun armarCapaTv(raiz: FrameLayout) {
        capaTv = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Jtv.Fondo()
            visibility = View.GONE
            isClickable = true
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        capaTv.addView(Jtv.logo(this, 40), LinearLayout.LayoutParams(-2, dp(40)).apply { bottomMargin = dp(18) })
        textoTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Jtv.titulo(this@PlayerActivity)
            gravity = Gravity.CENTER
        }
        capaTv.addView(textoTv)
        capaTv.addView(TextView(this).apply {
            text = "Puedes apagar la pantalla del celular: la TV descarga el canal sola."
            setTextColor(Jtv.TEXTO2)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        })
        val filaTv = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun separado(v: View) = filaTv.addView(v, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        separado(Transmitir.boton(this, "◀ Anterior") { player?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() } })
        separado(Transmitir.boton(this, "☰ Canales") { capaTv.visibility = View.GONE; mostrarCapa() })
        separado(Transmitir.boton(this, "Siguiente ▶") { player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } })
        capaTv.addView(filaTv)
        val filaTv2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        filaTv2.addView(Transmitir.boton(this, "⏯ Pausa") { Transmitir.pausarOSeguir(this) },
            LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        filaTv2.addView(Transmitir.boton(this, "📱 Ver en el celular") { salirDeTv(volverAlCelular = true, cerrarSesion = true) },
            LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        capaTv.addView(filaTv2)
        raiz.addView(capaTv, FrameLayout.LayoutParams(-1, -1))
    }

    // ---- mostrar / esconder
    private val ocultarSolo = Runnable { if (player?.isPlaying == true) ocultarCapa() else tocado() }
    private fun tocado() { manejador.removeCallbacks(ocultarSolo); if (capaVisible) manejador.postDelayed(ocultarSolo, 8000); tocarLista() }

    // ---- lista en franja aparte (izquierda), igual que en el carro
    private var franja: View? = null
    private var listaVisible = false
    private val aOcultarLista = Runnable { if (vistaPanel == "voz") tocarLista() else ocultarLista() }

    private fun anchoFranja(): Int {
        val w = resources.displayMetrics.widthPixels
        return (w * 0.34f).toInt().coerceAtLeast(dp(230)).coerceAtMost((w * 0.6f).toInt())
    }

    /** Cada vez que se usa la lista: se esconde sola a los 3 s sin usarla. */
    private fun tocarLista() {
        manejador.removeCallbacks(aOcultarLista)
        if (listaVisible) manejador.postDelayed(aOcultarLista, 3000)
    }

    /** Botón "Lista": la muestra o la esconde. */
    private fun alternarLista() {
        if (listaVisible) { ocultarLista(); return }
        vistaPanel = "canales"
        pintarPanel()
    }

    private fun mostrarLista() {
        val f = franja ?: return
        listaVisible = true
        f.visibility = View.VISIBLE
        ajustarFranja()
        tocarLista()
    }

    private fun ocultarLista() {
        manejador.removeCallbacks(aOcultarLista)
        if (!listaVisible) return
        listaVisible = false
        franja?.visibility = View.GONE
        ajustarFranja()
    }

    /** El video y los controles se corren a la derecha cuando está la lista. */
    private fun ajustarFranja() {
        val w = anchoFranja()
        franja?.let { (it.layoutParams as FrameLayout.LayoutParams).width = w; it.requestLayout() }
        val izq = if (listaVisible) w else 0
        for (v in listOf<View?>(vista, capa)) v?.let {
            val lp = it.layoutParams as FrameLayout.LayoutParams
            lp.setMargins(izq, 0, 0, 0)
            it.layoutParams = lp
        }
    }

    /** Botón "⏻ Apagar": para el video, el sonido y la grabación, y cierra la app. */
    private fun apagar() {
        Registro.anotar(this, "⏻ JTV apagado con el botón Apagar")
        try { if (enTv) { Transmitir.detener(this); enTv = false } } catch (e: Exception) {}
        try { player?.stop() } catch (e: Exception) {}
        Grabador.detener()
        finishAffinity()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        ajustarFranja()
    }

    private val refresco = object : Runnable {
        override fun run() {
            if (!capaVisible) return
            actualizarCapa()
            manejador.postDelayed(this, 1000)
        }
    }

    private fun mostrarCapa() {
        if (isInPictureInPictureMode) return
        capaVisible = true
        actualizarCapa()
        capa.visibility = View.VISIBLE      // solo los controles; la lista sale con el botón Lista
        tocado()
        manejador.removeCallbacks(refresco); manejador.post(refresco)
    }

    private fun ocultarCapa() {
        capa.visibility = View.GONE
        capaVisible = false
        ocultarLista()
        manejador.removeCallbacks(ocultarSolo); manejador.removeCallbacks(refresco)
        if (enTv) capaTv.visibility = View.VISIBLE
    }

    private fun alternarCapa() { if (capaVisible) ocultarCapa() else mostrarCapa() }

    // ---- lista
    private class Fila(val titulo: String, val sub: String = "", val canal: Canal? = null, val numero: Int = 0,
                       val marcada: Boolean = false, val fav: Boolean = false, val accion: () -> Unit)

    private fun favoritos(): Set<String> = favMem ?: Datos.favoritos(this).toSet().also { favMem = it }
    private fun grupoDe(c: Canal) = c.grupo.ifBlank { "Otros" }

    /** Pinta la lista y la muestra (si estaba escondida). */
    private fun pintarPanel() {
        repintarLista()
        if (!capaVisible) mostrarCapa()
        if (!listaVisible) mostrarLista() else tocarLista()
    }

    /** Solo vuelve a pintar la lista (sin mostrarla). */
    private fun repintarLista() {
        favMem = null
        val f = ArrayList<Fila>()
        val fav = favoritos()
        val actual = canalActual?.url
        var marcada = -1
        when (vistaPanel) {
            "grupos" -> {
                listaTitulo.text = "☰ Guía · elige un grupo"
                f.add(Fila("★ Favoritos", "${canales.count { it.url in fav }} canales", marcada = grupoSel == "★") { grupoSel = "★"; vistaPanel = "canales"; pintarPanel() })
                f.add(Fila("📺 Todos", "${canales.size} canales", marcada = grupoSel == null) { grupoSel = null; vistaPanel = "canales"; pintarPanel() })
                f.add(Fila("🎬 Mis Películas", "Videos del celular") { startActivity(Intent(this, PeliculasActivity::class.java)) })
                canales.groupBy { grupoDe(it) }.toSortedMap(compareBy<String>({ it.lowercase() }, { it })).forEach { (g, l) ->
                    f.add(Fila(g, "${l.size} canales", marcada = grupoSel == g) { grupoSel = g; vistaPanel = "canales"; pintarPanel() })
                }
            }
            "voz" -> {
                listaTitulo.text = if (vozTexto.isEmpty()) "🎤 Búsqueda por voz" else "🎤 «$vozTexto»"
                f.add(Fila("🎤 Hablar otra vez") { buscarPorVoz() })
                vozResultados.forEachIndexed { n, c ->
                    f.add(Fila(c.nombre, canal = c, numero = n + 1, fav = c.url in fav) { ponerCanal(c); vistaPanel = "canales"; pintarPanel() })
                }
                if (vozResultados.isEmpty() && vozTexto.isNotEmpty()) f.add(Fila("No encontré ese canal", "Prueba otra vez") {})
            }
            "opciones" -> {
                listaTitulo.text = "⚙ Ajustes"
                f.add(Fila("🖼️ Imagen: " + when (modoTamano) { 1 -> "LLENAR"; 2 -> "ESTIRAR"; else -> "ENTERA" },
                    "Toca para cambiar cómo llena la pantalla") { cambiarTamano(); pintarPanel() })
                f.add(Fila("📶 Calidad: ${calidadTexto()}", when (calidadMem) {
                    "alta" -> "Mejor imagen, gasta más datos"; "ahorro" -> "Menos datos y menos cortes"; else -> "Normal"
                }) { cambiarCalidad() })
                if (!Transmitir.esTv(this)) f.add(Fila("📡 Ver en TV", "Pasar el canal a tu Android TV") { Transmitir.elegirTv(this) { pasarATv() } })
            }
            else -> {
                val l = when (grupoSel) {
                    null -> canales
                    "★" -> canales.filter { it.url in fav }
                    else -> canales.filter { grupoDe(it) == grupoSel }
                }
                listaTitulo.text = "📂 " + when (grupoSel) { null -> "Todos"; "★" -> "★ Favoritos"; else -> grupoSel } + "  ▾ cambiar"
                l.forEachIndexed { i, c ->
                    if (c.url == actual) marcada = i
                    f.add(Fila(c.nombre, canal = c, numero = canales.indexOf(c) + 1, marcada = c.url == actual, fav = c.url in fav) { ponerCanal(c) })
                }
                if (l.isEmpty()) f.add(Fila("No hay canales aquí") {})
            }
        }
        adaptador.filas = f
        adaptador.notifyDataSetChanged()
        lista.setSelection(if (marcada > 1) marcada - 1 else 0)
        actualizarCapa()
    }

    private inner class Adaptador : BaseAdapter() {
        var filas: List<Fila> = emptyList()
        override fun getCount() = filas.size
        override fun getItem(p: Int) = filas[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (filas[p].canal != null) 0 else 1

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val f = filas[pos]
            return if (f.canal != null) vistaCanal(f, convertView as? LinearLayout) else vistaTexto(f, convertView as? TextView)
        }

        private fun vistaCanal(f: Fila, reuso: LinearLayout?): View {
            val fila = reuso ?: LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                val caja = FrameLayout(this@PlayerActivity).apply {
                    background = Jtv.forma(this@PlayerActivity, 10f, 7f, intArrayOf(0xFF1B2766.toInt(), 0xFF0E1745.toInt())); clipToOutline = true
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                }
                caja.addView(TextView(this@PlayerActivity).apply {
                    setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTypeface(typeface, Typeface.BOLD)
                }, FrameLayout.LayoutParams(-1, -1))
                caja.addView(ImageView(this@PlayerActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(-1, -1))
                addView(caja, LinearLayout.LayoutParams(dp(42), dp(42)))
                val textos = LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
                textos.addView(TextView(this@PlayerActivity).apply {
                    setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                textos.addView(TextView(this@PlayerActivity).apply {
                    text = "● EN VIVO"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    typeface = Jtv.titulo(this@PlayerActivity); letterSpacing = 0.06f
                    setPadding(dp(6), dp(1), dp(6), dp(1))
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
                addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val c = f.canal!!
            val caja = fila.getChildAt(0) as FrameLayout
            (caja.getChildAt(0) as TextView).text = iniciales(c.nombre)
            Logos.poner(caja.getChildAt(1) as ImageView, c.logo)
            val textos = fila.getChildAt(1) as LinearLayout
            (textos.getChildAt(0) as TextView).apply {
                text = "${f.numero}. " + (if (f.fav) "★ " else "") + c.nombre
                typeface = if (f.marcada) Jtv.titulo(this@PlayerActivity) else Jtv.texto(this@PlayerActivity)
            }
            (textos.getChildAt(1) as TextView).background = Jtv.forma(this@PlayerActivity, 4f, 3f,
                if (f.marcada) intArrayOf(Jtv.VIVO, 0xFFE0305F.toInt()) else intArrayOf(0xFF7A2346.toInt()))
            fila.background = if (f.marcada) Jtv.forma(this@PlayerActivity, 12f, 9f, intArrayOf(AZUL_SEL, Jtv.conAlfa(Jtv.INDIGO, 0.6f)), Jtv.ELECTRICO, 1.6f) else null
            return fila
        }

        private fun vistaTexto(f: Fila, reuso: TextView?): View {
            val v = reuso ?: TextView(this@PlayerActivity).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(14), dp(12), dp(12), dp(12))
            }
            v.text = if (f.sub.isEmpty()) f.titulo else "${f.titulo}\n${f.sub}"
            v.typeface = if (f.marcada) Jtv.titulo(this@PlayerActivity) else Jtv.texto(this@PlayerActivity)
            v.background = if (f.marcada) Jtv.forma(this@PlayerActivity, 12f, 9f, intArrayOf(AZUL_SEL, Jtv.conAlfa(Jtv.INDIGO, 0.6f)), Jtv.ELECTRICO, 1.6f) else null
            return v
        }
    }

    private fun iniciales(nombre: String): String {
        val limpio = nombre.replace(Regex("\\(.*?\\)|\\[.*?]"), " ").trim()
        return limpio.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.take(1) }.uppercase().ifEmpty { "TV" }
    }

    // ---- barra de información
    private fun hms(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun horaReloj(atrasMs: Long): String {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(System.currentTimeMillis() - atrasMs))
        return if (atrasMs > 50_000) "$hora (−${hms(atrasMs)})" else hora
    }

    private fun actualizarCapa() {
        val p = player ?: return
        if (!capaVisible) return
        btnPausa.setImageResource(if (p.playWhenReady) R.drawable.ic_pausa_jat else R.drawable.ic_play_jat)
        chipCanales.background = if (vistaPanel == "canales" || vistaPanel == "grupos") Jtv.forma(this, 12f, 8f, intArrayOf(AZUL_SEL, Jtv.conAlfa(Jtv.INDIGO, 0.7f)), Jtv.LINEA, 1f) else null
        val i = p.currentMediaItemIndex
        val c = canales.getOrNull(i)
        infoNombre.text = c?.nombre ?: ""
        infoSub.text = if (c != null) "${grupoDe(c)} · Canal ${i + 1} de ${canales.size}" else ""
        val dur = p.duration
        val pos = p.currentPosition
        val colchon = (p.bufferedPosition - pos).coerceAtLeast(0)
        val enVivo = p.isCurrentMediaItemLive || dur == C.TIME_UNSET || dur <= 0
        if (enVivo && dur > 0 && dur != C.TIME_UNSET) {
            barra.colorRelleno = 0x00000000; barra.colorGuardado = ACENTO
            barra.guardado = p.bufferedPosition.toFloat() / dur
            barra.avance = pos.toFloat() / dur
            val atras = (dur - pos).coerceAtLeast(0)
            infoTiempo.text = "Viendo ${horaReloj(atras)} · grabado ${hms(dur)}"
            infoVivo.text = if (atras > 50_000) "● Ir a EN VIVO" else "● EN VIVO"
            infoVivo.visibility = View.VISIBLE
        } else if (enVivo) {
            barra.colorRelleno = 0x00000000; barra.colorGuardado = ACENTO
            barra.guardado = colchon / 60_000f; barra.avance = 0f
            infoTiempo.text = "Guardado: ${colchon / 1000} s"
            infoVivo.text = "● EN VIVO"; infoVivo.visibility = View.VISIBLE
        } else {
            barra.colorRelleno = Jtv.ELECTRICO; barra.colorGuardado = 0x55FFFFFF
            barra.guardado = if (dur > 0) p.bufferedPosition.toFloat() / dur else 0f
            barra.avance = if (dur > 0) pos.toFloat() / dur else 0f
            infoTiempo.text = if (dur > 0) "${hms(pos)} / ${hms(dur)}" else hms(pos)
            infoVivo.visibility = View.GONE
        }
        val fav = c != null && c.url in favoritos()
        estrella.text = if (fav) "★" else "☆"
        estrella.setTextColor(if (fav) 0xFF2A1F00.toInt() else GRIS)
        estrella.background = if (fav) Jtv.forma(this, 12f, 8f, intArrayOf(Jtv.ORO, 0xFFFF9F2E.toInt())) else GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setStroke(dp(2), GRIS)
        }
        infoCalidad.text = "Calidad ${calidadTexto()}"
    }

    // ================================================================ acciones
    private fun ponerCanal(c: Canal) {
        val p = player ?: return
        val pos = canales.indexOf(c).takeIf { it >= 0 } ?: return
        if (enTv) {
            if (pos == p.currentMediaItemIndex) enviarCanalTv() else p.seekToDefaultPosition(pos)
            ocultarCapa(); return
        }
        errorSeguidos = 0
        if (pos == p.currentMediaItemIndex && p.playbackState != Player.STATE_IDLE) return
        p.seekToDefaultPosition(pos)
        p.prepare(); p.play()
    }

    private fun anterior() { val p = player ?: return; if (p.hasPreviousMediaItem()) { errorSeguidos = 0; p.seekToPreviousMediaItem(); p.prepare(); p.play() } }
    private fun siguiente() { val p = player ?: return; if (p.hasNextMediaItem()) { errorSeguidos = 0; p.seekToNextMediaItem(); p.prepare(); p.play() } }

    private fun pausaSigue() {
        val p = player ?: return
        if (p.playWhenReady) p.pause()
        else {
            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) p.prepare()
            p.play()
        }
        actualizarCapa()
    }

    private fun irAlDirecto() { val p = player ?: return; p.seekToDefaultPosition(); p.play(); actualizarCapa() }

    private fun cambiarFavoritoActual() {
        val c = canales.getOrNull(player?.currentMediaItemIndex ?: -1) ?: return
        val ahora = Datos.cambiarFavorito(this, c.url)
        favMem = null
        avisoBreve(if (ahora) "★ ${c.nombre}\nagregado a Favoritos" else "☆ ${c.nombre}\nquitado de Favoritos")
        if (listaVisible) repintarLista() else actualizarCapa()
    }

    private fun calidadTexto() = when (calidadMem) { "alta" -> "1080p"; "ahorro" -> "480p"; else -> "720p" }
    private fun maxAlto() = when (calidadMem) { "alta" -> 1080; "ahorro" -> 480; else -> 720 }

    private fun aplicarCalidad() {
        val p = player ?: return
        val (w, h) = when (calidadMem) { "alta" -> 1920 to 1080; "ahorro" -> 854 to 480; else -> 1280 to 720 }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSize(w, h).build()
    }

    private fun cambiarCalidad() {
        calidadMem = when (calidadMem) { "auto" -> "alta"; "alta" -> "ahorro"; else -> "auto" }
        Datos.setCalidad(this, calidadMem)
        aplicarCalidad()
        avisoBreve("📶 Calidad ${calidadTexto()}")
        if (listaVisible && vistaPanel == "opciones") repintarLista() else actualizarCapa()
    }

    private fun cambiarTamano() {
        modoTamano = (modoTamano + 1) % 3
        vista.resizeMode = when (modoTamano) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private val quitarAviso = Runnable { aviso.visibility = View.GONE }
    private fun ponerAviso(t: String) {
        manejador.removeCallbacks(quitarAviso)
        aviso.text = t
        aviso.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun avisoBreve(t: String) { ponerAviso(t); manejador.postDelayed(quitarAviso, 2500) }

    // ================================================================ voz
    private val PEDIR_VOZ = 41

    private fun buscarPorVoz() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-EC")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Di el nombre del canal")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        player?.volume = 0.1f        // que el micrófono no oiga la TV
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, PEDIR_VOZ)
        } catch (e: ActivityNotFoundException) {
            player?.volume = 1f
            avisoBreve("Este celular no tiene reconocimiento de voz")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PEDIR_VOZ) return
        player?.volume = 1f
        val textos = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) ?: return
        procesarVoz(textos)
    }

    private val palabrasDeMas = setOf("pon", "ponme", "poner", "pone", "quiero", "ver", "canal", "el", "la", "en", "de", "por", "favor", "abre", "abrir")

    private fun normal(t: String): String {
        val sinTildes = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return sinTildes.replace(Regex("\\(.*?\\)|\\[.*?]"), " ").replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+")).filter { it.isNotEmpty() && it !in setOf("hd", "fhd", "sd", "uhd", "4k") }.joinToString(" ")
    }

    private fun distancia(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    private fun parecido(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        return (100 * (1f - distancia(a, b).toFloat() / maxOf(a.length, b.length))).toInt()
    }

    private fun puntaje(dicho: String, nombre: String): Int {
        val d = normal(dicho).split(" ").filter { it !in palabrasDeMas }.joinToString(" ")
        val n = normal(nombre)
        if (d.isEmpty() || n.isEmpty()) return 0
        val dj = d.replace(" ", ""); val nj = n.replace(" ", "")
        if (dj == nj) return 100
        if (nj.startsWith(dj) && dj.length >= 3) return 90
        if (nj.contains(dj) && dj.length >= 3) return 80
        if (dj.contains(nj) && nj.length >= 3) return 75
        val pd = d.split(" "); val pn = n.split(" ")
        if (pd.all { w -> pn.any { it == w || (w.length >= 4 && parecido(w, it) >= 75) } }) return 70
        return parecido(dj, nj)
    }

    private fun procesarVoz(textos: List<String>) {
        vozTexto = textos.firstOrNull().orEmpty()
        Registro.anotar(this, "   🎤 (celular) dijo: ${textos.joinToString(" | ")}")
        val ranking = canales.map { c -> c to (textos.maxOfOrNull { puntaje(it, c.nombre) } ?: 0) }
            .filter { it.second >= 60 }.sortedByDescending { it.second }
        vozResultados = ranking.take(20).map { it.first }
        val primero = ranking.getOrNull(0)
        val segundo = ranking.getOrNull(1)
        if (primero != null && primero.second >= 80 && (segundo == null || segundo.second < primero.second)) {
            ponerCanal(primero.first)
            avisoBreve("🎤 ${primero.first.nombre}")
            vistaPanel = "canales"
            if (listaVisible) repintarLista()
        } else {
            vistaPanel = "voz"
            pintarPanel()
        }
    }

    // ================================================================ reproductor
    private fun hayInternet(): Boolean = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cap = cm.getNetworkCapabilities(cm.activeNetwork)
        cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) { true }

    private fun esDeRed(e: PlaybackException) = e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        e.errorCode == PlaybackException.ERROR_CODE_TIMEOUT

    private val reintento: Runnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!hayInternet()) { ponerAviso("📶 Sin internet.\nReintentando este canal…"); manejador.postDelayed(this, 5000); return }
            avisoBreve("Volvió el internet")
            errorSeguidos = 0
            p.prepare(); p.play()
        }
    }

    /** Antes de cada descarga: canal grabado (línea de tiempo) o directo con sus cabeceras. */
    private fun resolverPedido(spec: DataSpec): DataSpec {
        val pedido = porUrl[spec.uri.toString()]
        if (pedido != null && !enTv) {
            val local = try { Grabador.direccion(pedido.url, pedido.userAgent, pedido.referer, maxAlto()) } catch (e: Exception) { null }
            if (local != null) return spec.withUri(local)
        }
        val c = canalActual
        val extra = HashMap<String, String>()
        if (c != null) {
            if (c.userAgent.isNotEmpty()) extra["User-Agent"] = c.userAgent
            if (c.referer.isNotEmpty()) extra["Referer"] = c.referer
        }
        return if (extra.isEmpty()) spec else spec.withAdditionalHeaders(extra)
    }

    private fun iniciarReproductor() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Reproduccion.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val conCabeceras = ResolvingDataSource.Factory(http) { spec -> resolverPedido(spec) }
        Grabador.iniciar(this)
        val p = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
            .setMediaSourceFactory(FabricaFuentes(DefaultDataSource.Factory(this, conCabeceras)))
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 60_000, 2_500, 6_000)
                .setTargetBufferBytes(48 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build())
            .build()
        player = p
        vista.player = p
        aplicarCalidad()

        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (Audio.revisar(this@PlayerActivity, tracks) == false) avisoBreve("🔇 Este canal trae un audio que no se puede reproducir")
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                aviso.visibility = View.GONE
                canalActual = canales.getOrNull(p.currentMediaItemIndex)
                canalActual?.let { Datos.guardarUltimo(this@PlayerActivity, it.url) }
                if (enTv) { enviarCanalTv(); return }
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                if (listaVisible) repintarLista()
                if (!capaVisible) {
                    canalActual?.let { avisoBreve("📺 ${it.nombre}") }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) { actualizarCapa() }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> { errorSeguidos = 0; if (aviso.text.startsWith("Cargando") || aviso.text.contains("internet")) ponerAviso("") }
                    Player.STATE_BUFFERING -> if (aviso.visibility != View.VISIBLE) ponerAviso("Cargando…")
                    else -> {}
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                val idx = p.currentMediaItemIndex
                val item = p.currentMediaItem
                Registro.anotar(this@PlayerActivity, "   ⚠ (celular) error: ${e.errorCodeName} · ${e.cause?.message ?: e.message}")
                // canal en vivo que se quedó atrás: volver al directo
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition(); p.prepare(); return
                }
                // la dirección no dice qué formato es: probar como transmisión en vivo (HLS)
                if (item != null && item.localConfiguration?.mimeType == null && probadosHls.add(item.mediaId)) {
                    p.replaceMediaItem(idx, item.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build())
                    p.prepare(); p.play(); return
                }
                // sin internet: no es el canal; esperar y reintentar el MISMO canal
                if (esDeRed(e) && !hayInternet()) {
                    ponerAviso("📶 Sin internet.\nReintentando este canal…")
                    manejador.removeCallbacks(reintento)
                    manejador.postDelayed(reintento, 5000)
                    return
                }
                errorSeguidos++
                if (p.hasNextMediaItem() && errorSeguidos <= 10) {
                    ponerAviso("😕 Este canal no está disponible ahora.\nPasando al siguiente…")
                    manejador.postDelayed({
                        if (p.currentMediaItemIndex == idx) { p.seekToNextMediaItem(); p.prepare(); p.play() }
                    }, 2500)
                } else ponerAviso("😕 Este canal no está disponible ahora.")
            }
        })

        val items = canales.map { c ->
            val b = MediaItem.Builder()
                .setUri(Uri.parse(c.url))
                .setMediaId(c.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(c.nombre).build())
                // en vivo: velocidad normal siempre (acelerar/frenar da saltitos)
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
            val u = c.url.lowercase()
            when {
                u.contains("m3u8") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
                u.contains(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
            }
            b.build()
        }
        val inicio = Reproduccion.inicio.coerceIn(0, items.size - 1)
        canalActual = canales[inicio]
        p.setMediaItems(items, inicio, C.TIME_UNSET)
        p.prepare()
        p.playWhenReady = true
    }

    // ================================================================ ver en la TV
    private fun pasarATv() {
        val p = player ?: return
        enTv = true
        p.stop()   // el celular deja de descargar el video: ahorra batería y datos
        Grabador.detener()
        vista.keepScreenOn = false
        aviso.visibility = View.GONE
        ocultarCapa()
        capaTv.visibility = View.VISIBLE
        Transmitir.escuchar(this, oyenteTv)
        enviarCanalTv()
    }

    private fun enviarCanalTv() {
        val p = player ?: return
        val c = canales.getOrNull(p.currentMediaItemIndex) ?: return
        textoTv.text = "📡 En ${Transmitir.nombreTv(this)}:\n${c.nombre}   ${p.currentMediaItemIndex + 1}/${canales.size}"
        val ok = Transmitir.enviar(this, c.url, c.nombre, c.grupo, c.logo, Transmitir.tipoDe(c.url), true,
            ua = c.userAgent, referer = c.referer) { msg ->
            runOnUiThread {
                if (enTv && canalActual?.url == c.url)
                    textoTv.text = "😕 $msg\nEste canal no deja verse en la TV. Prueba con Siguiente ▶"
            }
        }
        if (!ok) salirDeTv(volverAlCelular = false, cerrarSesion = false)
    }

    private fun salirDeTv(volverAlCelular: Boolean, cerrarSesion: Boolean) {
        enTv = false
        Transmitir.dejarDeEscuchar(this, oyenteTv)
        if (cerrarSesion) Transmitir.detener(this)
        capaTv.visibility = View.GONE
        vista.keepScreenOn = true
        val p = player ?: return
        if (volverAlCelular) { p.prepare(); p.play() }
        mostrarCapa()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (listaVisible) { ocultarLista(); return }
        if (enTv) {
            AlertDialog.Builder(this)
                .setTitle("¿Dejar de ver en la TV?")
                .setPositiveButton("Sí, apagar en la TV") { _, _ -> Transmitir.detener(this); enTv = false; finish() }
                .setNegativeButton("Salir y que siga en la TV") { _, _ -> finish() }
                .setNeutralButton("Cancelar", null)
                .show()
        } else if (capaVisible) ocultarCapa()
        else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @Suppress("DEPRECATION")
    private fun pantallaCompleta() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pantallaCompleta()
    }

    // Control remoto / teclado: canal arriba y abajo; OK muestra la interfaz
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { siguiente(); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { anterior(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { pausaSigue(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU ->
                if (!capaVisible) { mostrarCapa(); true } else super.onKeyDown(keyCode, event)
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // Al salir con el botón de inicio, sigue viéndose en una ventanita
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && !enTv) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (e: Exception) { }
        }
    }

    override fun onPictureInPictureModeChanged(enPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(enPip, newConfig)
        if (enPip) { ocultarCapa(); aviso.visibility = View.GONE }
    }

    override fun onStart() {
        super.onStart()
        if (!enTv) player?.play()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        manejador.removeCallbacksAndMessages(null)
        Transmitir.dejarDeEscuchar(this, oyenteTv)
        Grabador.cerrar()
        player?.release()
        player = null
    }
}

/**
 * Igual que la fábrica normal, pero para canales HLS espera mucho más antes de dar el canal por "trabado"
 * cuando no llegan partes nuevas (sin internet sigue con lo grabado en vez de cortarse a los pocos segundos).
 */
class FabricaFuentes(ds: DataSource.Factory) : MediaSource.Factory {
    private val normal = DefaultMediaSourceFactory(ds)
    private val hls = HlsMediaSource.Factory(ds).setPlaylistTrackerFactory { a, b, c -> DefaultHlsPlaylistTracker(a, b, c, 40.0) }

    override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider): MediaSource.Factory {
        normal.setDrmSessionManagerProvider(p); hls.setDrmSessionManagerProvider(p); return this
    }
    override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy): MediaSource.Factory {
        normal.setLoadErrorHandlingPolicy(p); hls.setLoadErrorHandlingPolicy(p); return this
    }
    override fun getSupportedTypes(): IntArray = normal.supportedTypes
    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        if (mediaItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) hls.createMediaSource(mediaItem)
        else normal.createMediaSource(mediaItem)
}

/** Barra de la línea de tiempo (en vivo) o de avance; se toca para saltar. */
class BarraTiempo(c: Context) : View(c) {
    var avance = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var colorRelleno = Color.WHITE
        set(v) { field = v; invalidate() }
    var guardado = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var colorGuardado = 0x66FFFFFF
        set(v) { field = v; invalidate() }
    var alTocar: ((Float) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val mitad = height / 2f
        val grueso = height * 0.24f
        val r = height * 0.32f
        val x0 = r; val x1 = width - r
        p.color = 0x55FFFFFF
        cv.drawRoundRect(x0, mitad - grueso / 2, x1, mitad + grueso / 2, grueso, grueso, p)
        // los colores de la marca (cielo / eléctrico) se pintan con el degradado de JTV
        val deg = android.graphics.LinearGradient(x0, 0f, x1, 0f, intArrayOf(Jtv.CIELO, Jtv.ELECTRICO, Jtv.VIOLETA), null, android.graphics.Shader.TileMode.CLAMP)
        if (guardado > 0f) {
            p.color = colorGuardado
            if (colorGuardado == Jtv.CIELO) p.shader = deg
            cv.drawRoundRect(x0, mitad - grueso / 2, x0 + (x1 - x0) * guardado, mitad + grueso / 2, grueso, grueso, p)
            p.shader = null
        }
        val xf = x0 + (x1 - x0) * avance
        p.color = colorRelleno
        if (colorRelleno == Jtv.ELECTRICO) p.shader = deg
        cv.drawRoundRect(x0, mitad - grueso / 2, xf, mitad + grueso / 2, grueso, grueso, p)
        p.shader = null
        p.color = Jtv.conAlfa(Jtv.CIELO, 0.35f)
        cv.drawCircle(xf, mitad, r * 1.45f, p)
        p.color = Color.WHITE
        cv.drawCircle(xf, mitad, r, p)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val f = alTocar ?: return false
        if (e.action == MotionEvent.ACTION_UP) {
            val r = height * 0.32f
            f(((e.x - r) / (width - 2 * r)).coerceIn(0f, 1f))
        }
        return true
    }
}
