package ec.jat.tv

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Reproductor de las películas del celular: sigue donde se quedó. */
class PeliculaPlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var vista: PlayerView
    private lateinit var btnTamano: TextView
    private lateinit var titulo: TextView
    private lateinit var btnTv: TextView
    private lateinit var capaTv: LinearLayout
    private lateinit var textoTv: TextView
    /** true mientras la película se ve en la TV. */
    private var enTv = false
    private val oyenteTv = object : OyenteTv() {
        override fun onSessionEnded(s: com.google.android.gms.cast.framework.CastSession, error: Int) {
            if (enTv) salirDeTv(volverAlCelular = false, cerrarSesion = false)
        }
    }
    private val lista = CineEstado.lista
    private val manejador = Handler(Looper.getMainLooper())
    private var modoTamano = 0

    private val guardarCada = object : Runnable {
        override fun run() {
            guardar()
            manejador.postDelayed(this, 5000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (lista.isEmpty()) { finish(); return }
        AutoService.pausarSiSuena()

        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        vista = PlayerView(this).apply {
            keepScreenOn = true
            setShowNextButton(lista.size > 1)
            setShowPreviousButton(lista.size > 1)
            controllerShowTimeoutMs = 3500
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                btnTamano.visibility = v
                titulo.visibility = v
                if (::btnTv.isInitialized && !Transmitir.esTv(this@PeliculaPlayerActivity)) btnTv.visibility = v
            })
        }
        raiz.addView(vista, FrameLayout.LayoutParams(-1, -1))
        // barra de avance con los colores de JTV
        try {
            vista.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
                setPlayedColor(Jtv.ELECTRICO); setScrubberColor(Jtv.CIELO)
                setBufferedColor(Jtv.conAlfa(Jtv.CIELO, 0.35f)); setUnplayedColor(0x33FFFFFF)
            }
        } catch (e: Exception) {}

        titulo = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Jtv.titulo(this@PeliculaPlayerActivity)
            setShadowLayer(8f, 0f, 2f, Jtv.NEGRO)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            maxLines = 1
        }
        raiz.addView(titulo, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(16), dp(16), dp(140), 0)
        })

        btnTamano = TextView(this).apply {
            text = "⤢ Ajustar"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            typeface = Jtv.textoFuerte(this@PeliculaPlayerActivity)
            background = Jtv.forma(this@PeliculaPlayerActivity, 14f, 10f, intArrayOf(Jtv.conAlfa(Jtv.SUPERFICIE2, 0.85f), Jtv.conAlfa(Jtv.NAVY, 0.85f)), Jtv.LINEA, 1.2f)
            setOnClickListener { cambiarTamano() }
            Jtv.vivo(this)
        }
        raiz.addView(btnTamano, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(16), dp(16), dp(16), 0)
        })

        btnTv = TextView(this).apply {
            text = "📡 Ver en TV"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            typeface = Jtv.textoFuerte(this@PeliculaPlayerActivity)
            background = Jtv.forma(this@PeliculaPlayerActivity, 14f, 10f, Jtv.MARCA)
            visibility = if (Transmitir.esTv(this@PeliculaPlayerActivity)) View.GONE else View.VISIBLE
            setOnClickListener { Transmitir.elegirTv(this@PeliculaPlayerActivity) { pasarATv() } }
            Jtv.vivo(this)
        }
        raiz.addView(btnTv, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(16), dp(64), dp(16), 0)
        })

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
            typeface = Jtv.titulo(this@PeliculaPlayerActivity)
            gravity = Gravity.CENTER
        }
        capaTv.addView(textoTv)
        capaTv.addView(TextView(this).apply {
            text = "Puedes apagar la pantalla del celular. Mantén el celular en el mismo WiFi."
            setTextColor(Jtv.TEXTO2)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        })
        val filaTv = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        filaTv.addView(Transmitir.boton(this, "⏯ Pausa") { Transmitir.pausarOSeguir(this) },
            LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        filaTv.addView(Transmitir.boton(this, "📱 Ver en el celular") { salirDeTv(volverAlCelular = true, cerrarSesion = true) },
            LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        capaTv.addView(filaTv)
        raiz.addView(capaTv, FrameLayout.LayoutParams(-1, -1))

        setContentView(raiz)
        pantallaCompleta()
        iniciar()
    }

    private fun actual(): Pelicula? = player?.let { lista.getOrNull(it.currentMediaItemIndex) }

    private fun iniciar() {
        val p = ExoPlayer.Builder(this, androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true).setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)).build()
        player = p
        vista.player = p
        p.addListener(object : Player.Listener {
            private var anterior: Int = -1

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // guardar la que terminó antes de pasar a la siguiente
                lista.getOrNull(anterior)?.let {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                        Peliculas.guardarPosicion(this@PeliculaPlayerActivity, it, it.duracionMs, it.duracionMs, true)
                }
                anterior = p.currentMediaItemIndex
                val peli = actual() ?: return
                titulo.text = peli.titulo
                val pos = Peliculas.posicion(this@PeliculaPlayerActivity, peli)
                if (pos > 10_000 && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    p.seekTo(pos)
                    Toast.makeText(this@PeliculaPlayerActivity,
                        "Continuando desde ${tiempo(pos)}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    actual()?.let { Peliculas.guardarPosicion(this@PeliculaPlayerActivity, it, it.duracionMs, it.duracionMs, true) }
                    finish()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                Toast.makeText(this@PeliculaPlayerActivity,
                    "No se pudo reproducir este video (${e.errorCodeName}). Puede que el formato no sea compatible.",
                    Toast.LENGTH_LONG).show()
            }
        })
        val items = lista.map {
            MediaItem.Builder().setUri(it.uri).setMediaId(it.clave)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.titulo).build()).build()
        }
        p.setMediaItems(items, CineEstado.inicio.coerceIn(0, items.size - 1), 0L)
        p.prepare()
        p.playWhenReady = true
        manejador.postDelayed(guardarCada, 5000)
    }

    // ------------------------------------------------------------ ver en la TV
    private fun pasarATv() {
        val p = player ?: return
        val peli = actual() ?: return
        guardar(true)
        val desde = if (p.currentPosition > 0) p.currentPosition else Peliculas.posicion(this, peli)
        val url = ServidorPeliculas.direccion(this, peli.uri)
        if (url == null) {
            Toast.makeText(this, "Conecta el celular al WiFi de la casa para enviar la película a la TV.", Toast.LENGTH_LONG).show()
            return
        }
        val tipo = contentResolver.getType(peli.uri) ?: "video/mp4"
        enTv = true
        p.pause()
        vista.keepScreenOn = false
        vista.useController = false
        btnTamano.visibility = View.GONE; titulo.visibility = View.GONE; btnTv.visibility = View.GONE
        textoTv.text = "📡 En ${Transmitir.nombreTv(this)}:\n${peli.titulo}"
        capaTv.visibility = View.VISIBLE
        Transmitir.escuchar(this, oyenteTv)
        val ok = Transmitir.enviar(this, url, peli.titulo, peli.carpeta, "", tipo, false, desde) { msg ->
            runOnUiThread {
                if (enTv) textoTv.text = "😕 $msg\nLa TV no acepta este formato de video (mejor si es .mp4)."
            }
        }
        if (!ok) salirDeTv(volverAlCelular = false, cerrarSesion = false)
    }

    private fun salirDeTv(volverAlCelular: Boolean, cerrarSesion: Boolean) {
        val posTv = Transmitir.posicionTv(this)
        if (posTv > 10_000) {
            actual()?.let { Peliculas.guardarPosicion(this, it, posTv, if (it.duracionMs > 0) it.duracionMs else posTv, true) }
            player?.seekTo(posTv)
        }
        enTv = false
        Transmitir.dejarDeEscuchar(this, oyenteTv)
        if (cerrarSesion) Transmitir.detener(this) else ServidorPeliculas.detener(this)
        capaTv.visibility = View.GONE
        vista.useController = true
        vista.keepScreenOn = true
        if (volverAlCelular) player?.play()
        vista.showController()
    }

    private fun guardar(forzar: Boolean = false) {
        if (enTv) {
            // la película avanza en la TV: se guarda por dónde va allá
            val peli = actual() ?: return
            val pos = Transmitir.posicionTv(this)
            if (pos > 10_000) Peliculas.guardarPosicion(this, peli, pos, if (peli.duracionMs > 0) peli.duracionMs else pos, forzar)
            return
        }
        val p = player ?: return
        val peli = actual() ?: return
        if (p.playbackState == Player.STATE_IDLE) return
        val dur = if (p.duration > 0) p.duration else peli.duracionMs
        val pos = p.currentPosition
        if (pos < 10_000) return
        Peliculas.guardarPosicion(this, peli, pos, dur, forzar)
    }

    private fun tiempo(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun cambiarTamano() {
        modoTamano = (modoTamano + 1) % 3
        vista.resizeMode = when (modoTamano) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        btnTamano.text = when (modoTamano) { 1 -> "⤢ Zoom"; 2 -> "⤢ Estirar"; else -> "⤢ Ajustar" }
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
        vista.useController = !enPip
        if (enPip) { btnTamano.visibility = View.GONE; titulo.visibility = View.GONE }
    }

    override fun onPause() {
        super.onPause()
        guardar(true)
    }

    override fun onStop() {
        super.onStop()
        if (!enTv) player?.pause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!enTv) { super.onBackPressed(); return }
        android.app.AlertDialog.Builder(this)
            .setTitle("¿Dejar de ver en la TV?")
            .setPositiveButton("Sí, apagar en la TV") { _, _ -> guardar(true); Transmitir.detener(this); enTv = false; finish() }
            .setNegativeButton("Salir y que siga en la TV") { _, _ -> guardar(true); finish() }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        manejador.removeCallbacksAndMessages(null)
        Transmitir.dejarDeEscuchar(this, oyenteTv)
        player?.release()
        player = null
    }
}
