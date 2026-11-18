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

        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        vista = PlayerView(this).apply {
            keepScreenOn = true
            setShowNextButton(lista.size > 1)
            setShowPreviousButton(lista.size > 1)
            controllerShowTimeoutMs = 3500
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                btnTamano.visibility = v
                titulo.visibility = v
            })
        }
        raiz.addView(vista, FrameLayout.LayoutParams(-1, -1))

        titulo = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
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
            background = GradientDrawable().apply { setColor(0xAA000000.toInt()); cornerRadius = dp(10).toFloat() }
            setOnClickListener { cambiarTamano() }
        }
        raiz.addView(btnTamano, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(16), dp(16), dp(16), 0)
        })

        setContentView(raiz)
        pantallaCompleta()
        iniciar()
    }

    private fun actual(): Pelicula? = player?.let { lista.getOrNull(it.currentMediaItemIndex) }

    private fun iniciar() {
        val p = ExoPlayer.Builder(this).build()
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

    private fun guardar(forzar: Boolean = false) {
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
        if (player?.isPlaying == true) {
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
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        manejador.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}
