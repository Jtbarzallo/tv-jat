package ec.jat.tv

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var vista: PlayerView
    private lateinit var etiqueta: TextView
    private lateinit var error: TextView
    private lateinit var btnTamano: TextView
    private lateinit var btnCanales: TextView
    private lateinit var panel: LinearLayout
    private lateinit var listaPanel: ListView
    private val adaptadorPanel = AdaptadorPanel()
    private val canales = Reproduccion.canales
    private val probadosHls = HashSet<String>()
    private val manejador = Handler(Looper.getMainLooper())
    private val ocultarEtiqueta = Runnable { etiqueta.visibility = View.GONE }
    private var modoTamano = 0

    /** Canal que está sonando (para mandar User-Agent / Referer propios del canal). */
    @Volatile private var canalActual: Canal? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (canales.isEmpty()) { finish(); return }

        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        vista = PlayerView(this).apply {
            keepScreenOn = true
            setShowNextButton(true)
            setShowPreviousButton(true)
            setShowFastForwardButton(false)
            setShowRewindButton(false)
            controllerShowTimeoutMs = 3500
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                btnTamano.visibility = v
                btnCanales.visibility = if (panelAbierto()) View.GONE else v
                if (v == View.VISIBLE) mostrarEtiqueta()
            })
        }
        raiz.addView(vista, FrameLayout.LayoutParams(-1, -1))

        etiqueta = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(0xAA000000.toInt()); cornerRadius = dp(10).toFloat() }
        }
        raiz.addView(etiqueta, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(16), dp(16), dp(16), 0)
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

        btnCanales = TextView(this).apply {
            text = "☰ Canales"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(0xAA000000.toInt()); cornerRadius = dp(10).toFloat() }
            setOnClickListener { abrirPanel() }
        }
        raiz.addView(btnCanales, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(16), dp(64), dp(16), 0)
        })

        // Panel lateral con la lista de canales (se desplaza mientras sigue el video)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE614182C.toInt())
            visibility = View.GONE
            isClickable = true
        }
        val cabeceraPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(6), dp(10))
        }
        cabeceraPanel.addView(TextView(this).apply {
            text = "📺 Canales (${canales.size})"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        cabeceraPanel.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setOnClickListener { cerrarPanel() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(cabeceraPanel)
        listaPanel = ListView(this).apply {
            adapter = adaptadorPanel
            divider = null
            dividerHeight = dp(4)
            setPadding(dp(8), 0, dp(8), dp(8))
            clipToPadding = false
            setOnItemClickListener { _, _, pos, _ -> cambiarCanal(pos) }
        }
        panel.addView(listaPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        val anchoPanel = (resources.displayMetrics.widthPixels * 0.42).toInt().coerceAtLeast(dp(260))
        raiz.addView(panel, FrameLayout.LayoutParams(anchoPanel, -1, Gravity.END))

        error = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply { setColor(0xCC8B1E2B.toInt()); cornerRadius = dp(12).toFloat() }
            visibility = View.GONE
        }
        raiz.addView(error, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        setContentView(raiz)
        pantallaCompleta()
        iniciarReproductor()
    }

    private fun iniciarReproductor() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Reproduccion.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val conCabeceras = ResolvingDataSource.Factory(http) { spec ->
            val c = canalActual
            val extra = HashMap<String, String>()
            if (c != null) {
                if (c.userAgent.isNotEmpty()) extra["User-Agent"] = c.userAgent
                if (c.referer.isNotEmpty()) extra["Referer"] = c.referer
            }
            if (extra.isEmpty()) spec else spec.withAdditionalHeaders(extra)
        }
        val fuente = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, conCabeceras))

        val p = ExoPlayer.Builder(this).setMediaSourceFactory(fuente).build()
        player = p
        vista.player = p

        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                error.visibility = View.GONE
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                canalActual = canales.getOrNull(p.currentMediaItemIndex)
                canalActual?.let { Datos.guardarUltimo(this@PlayerActivity, it.url) }
                adaptadorPanel.notifyDataSetChanged()
                mostrarEtiqueta()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) error.visibility = View.GONE
            }

            override fun onPlayerError(e: PlaybackException) {
                val idx = p.currentMediaItemIndex
                val item = p.currentMediaItem
                // canal en vivo que se quedó atrás: volver al directo
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                // la dirección no dice qué formato es: probar como transmisión en vivo (HLS)
                if (item != null && item.localConfiguration?.mimeType == null && probadosHls.add(item.mediaId)) {
                    p.replaceMediaItem(idx, item.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build())
                    p.prepare()
                    p.play()
                    return
                }
                error.visibility = View.VISIBLE
                vista.showController()
                if (p.hasNextMediaItem()) {
                    error.text = "😕 Este canal no está disponible ahora.\nPasando al siguiente…"
                    manejador.postDelayed({
                        if (error.visibility == View.VISIBLE && p.currentMediaItemIndex == idx) {
                            p.seekToNextMediaItem(); p.prepare(); p.play()
                        }
                    }, 3000)
                } else {
                    error.text = "😕 Este canal no está disponible ahora."
                }
            }
        })

        val items = canales.map { c ->
            val b = MediaItem.Builder()
                .setUri(Uri.parse(c.url))
                .setMediaId(c.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(c.nombre).build())
            val u = c.url.lowercase()
            when {
                u.contains(".m3u8") || u.contains("m3u8") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
                u.contains(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
            }
            b.build()
        }
        val inicio = Reproduccion.inicio.coerceIn(0, items.size - 1)
        canalActual = canales[inicio]
        p.setMediaItems(items, inicio, 0L)
        p.prepare()
        p.playWhenReady = true
    }

    private fun panelAbierto() = panel.visibility == View.VISIBLE

    private fun abrirPanel() {
        panel.visibility = View.VISIBLE
        btnCanales.visibility = View.GONE
        vista.hideController()
        adaptadorPanel.notifyDataSetChanged()
        val actual = player?.currentMediaItemIndex ?: 0
        listaPanel.setSelection((actual - 2).coerceAtLeast(0))
    }

    private fun cerrarPanel() {
        panel.visibility = View.GONE
    }

    private fun cambiarCanal(pos: Int) {
        val p = player ?: return
        if (pos == p.currentMediaItemIndex && p.playbackState != Player.STATE_IDLE) return
        p.seekToDefaultPosition(pos)
        p.prepare()
        p.play()
        adaptadorPanel.notifyDataSetChanged()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panelAbierto()) cerrarPanel() else super.onBackPressed()
    }

    private inner class AdaptadorPanel : BaseAdapter() {
        override fun getCount() = canales.size
        override fun getItem(p: Int) = canales[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val fila = (convertView as? LinearLayout) ?: LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                addView(ImageView(this@PlayerActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(48), dp(36)))
                addView(TextView(this@PlayerActivity).apply {
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    maxLines = 2
                    setPadding(dp(10), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val c = canales[pos]
            val actual = pos == player?.currentMediaItemIndex
            Logos.poner(fila.getChildAt(0) as ImageView, c.logo)
            (fila.getChildAt(1) as TextView).apply {
                text = (if (actual) "▶ " else "${pos + 1}. ") + c.nombre
                setTextColor(if (actual) 0xFF4FD1C5.toInt() else Color.WHITE)
                setTypeface(null, if (actual) Typeface.BOLD else Typeface.NORMAL)
            }
            fila.background = GradientDrawable().apply {
                setColor(if (actual) 0xFF2A3156.toInt() else 0x33FFFFFF)
                cornerRadius = dp(8).toFloat()
            }
            return fila
        }
    }

    private fun mostrarEtiqueta() {
        val p = player ?: return
        val c = canales.getOrNull(p.currentMediaItemIndex) ?: return
        etiqueta.text = "📺 ${c.nombre}   ${p.currentMediaItemIndex + 1}/${canales.size}"
        etiqueta.visibility = View.VISIBLE
        manejador.removeCallbacks(ocultarEtiqueta)
        manejador.postDelayed(ocultarEtiqueta, 4000)
    }

    private fun cambiarTamano() {
        modoTamano = (modoTamano + 1) % 3
        vista.resizeMode = when (modoTamano) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        btnTamano.text = when (modoTamano) {
            1 -> "⤢ Zoom"
            2 -> "⤢ Estirar"
            else -> "⤢ Ajustar"
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

    // Control remoto / teclado: canal arriba y abajo
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { p.seekToNextMediaItem(); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { p.seekToPreviousMediaItem(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // Al salir con el botón de inicio, sigue viéndose en una ventanita
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
        if (enPip) {
            etiqueta.visibility = View.GONE; btnTamano.visibility = View.GONE
            btnCanales.visibility = View.GONE; cerrarPanel()
        }
    }

    override fun onStart() {
        super.onStart()
        player?.play()
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
