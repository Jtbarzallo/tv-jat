package ec.jat.tv

import android.animation.ValueAnimator
import ec.jat.tv.youtube.R
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView

/**
 * Identidad visual de JTV: colores, letras, formas y botones (la usan todas las pantallas).
 *
 * · Colores del logo y del banner: azul eléctrico, cobalto, índigo y violeta sobre navy profundo.
 * · Forma propia: esquinas con corte diagonal (como las franjas del banner y la "V" del logo).
 * · Letras: Sora (títulos, tecnológica) y Manrope (textos, muy legible).
 * El logo nunca se dibuja ni se modifica: se usa la imagen oficial (jtv_logo_color / jtv_logo_blanco).
 */
object Jtv {
    // ------------------------------------------------------------------ colores
    const val NEGRO = 0xFF02040C.toInt()
    const val FONDO = 0xFF050A1C.toInt()
    const val NAVY = 0xFF081030.toInt()
    const val SUPERFICIE = 0xFF0C1640.toInt()
    const val SUPERFICIE2 = 0xFF14205A.toInt()
    const val LINEA = 0x4D4F7BFF
    const val ELECTRICO = 0xFF2F6BFF.toInt()
    const val COBALTO = 0xFF1B3BE8.toInt()
    const val CIELO = 0xFF7DB8FF.toInt()
    const val HIELO = 0xFFE3EAFF.toInt()
    const val INDIGO = 0xFF3B22C8.toInt()
    const val VIOLETA = 0xFF7B4DFF.toInt()
    const val TEXTO2 = 0xFFB5C3F0.toInt()
    const val TEXTO3 = 0xFF7380B6.toInt()
    const val VIVO = 0xFFFF3D6E.toInt()
    const val ORO = 0xFFFFC94D.toInt()
    const val EXITO = 0xFF3DDC97.toInt()

    /** Degradado principal (botones, selección): eléctrico → violeta. */
    val MARCA = intArrayOf(ELECTRICO, VIOLETA)
    /** Degradado del logo: cielo → cobalto. */
    val LOGO = intArrayOf(CIELO, ELECTRICO, COBALTO)
    val TARJETA = intArrayOf(SUPERFICIE2, SUPERFICIE)

    // ------------------------------------------------------------------ letras
    private var tTitulo: Typeface? = null
    private var tTituloFuerte: Typeface? = null
    private var tTexto: Typeface? = null
    private var tTextoFuerte: Typeface? = null

    fun titulo(c: Context): Typeface = tTitulo ?: cargar(c, R.font.sora_700, Typeface.DEFAULT_BOLD).also { tTitulo = it }
    fun tituloFuerte(c: Context): Typeface = tTituloFuerte ?: cargar(c, R.font.sora_800, Typeface.DEFAULT_BOLD).also { tTituloFuerte = it }
    fun texto(c: Context): Typeface = tTexto ?: cargar(c, R.font.manrope_500, Typeface.DEFAULT).also { tTexto = it }
    fun textoFuerte(c: Context): Typeface = tTextoFuerte ?: cargar(c, R.font.manrope_700, Typeface.DEFAULT_BOLD).also { tTextoFuerte = it }

    private fun cargar(c: Context, id: Int, respaldo: Typeface): Typeface =
        try { c.resources.getFont(id) } catch (e: Exception) { respaldo }

    fun dp(c: Context, v: Float): Float = v * c.resources.displayMetrics.density
    fun dpi(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ forma JTV
    /**
     * Rectángulo con dos esquinas cortadas en diagonal (arriba-izquierda y abajo-derecha) y dos redondeadas,
     * relleno con degradado y borde opcional. Con corte = 0 es un rectángulo redondeado normal.
     */
    class Forma(
        private val radio: Float,
        private val corte: Float,
        colores: IntArray,
        private val diagonal: Boolean = true,
        borde: Int = 0,
        private val grosor: Float = 0f,
    ) : Drawable() {
        var colores: IntArray = colores
            set(v) { field = v; armar(); invalidateSelf() }
        var borde: Int = borde
            set(v) { field = v; pBorde.color = v; invalidateSelf() }
        private val pRelleno = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = grosor; color = borde }
        private val camino = Path()
        private var opacidad = 255

        override fun onBoundsChange(bounds: Rect) { super.onBoundsChange(bounds); armar() }

        private fun armar() {
            val b = bounds
            if (b.isEmpty) return
            val m = grosor / 2
            val l = b.left + m; val t = b.top + m; val r = b.right - m; val bo = b.bottom - m
            val c = minOf(corte, (bo - t) / 2, (r - l) / 2)
            val rr = minOf(radio, (bo - t) / 2, (r - l) / 2)
            camino.reset()
            camino.moveTo(l + c, t)
            camino.lineTo(r - rr, t); camino.quadTo(r, t, r, t + rr)
            camino.lineTo(r, bo - c); camino.lineTo(r - c, bo)
            camino.lineTo(l + rr, bo); camino.quadTo(l, bo, l, bo - rr)
            camino.lineTo(l, t + c); camino.close()
            pRelleno.shader = if (colores.size == 1) null else
                if (diagonal) LinearGradient(l, t, r, bo, colores, null, Shader.TileMode.CLAMP)
                else LinearGradient(l, t, l, bo, colores, null, Shader.TileMode.CLAMP)
            pRelleno.color = colores[0]
        }

        override fun draw(cv: Canvas) {
            pRelleno.alpha = opacidad
            cv.drawPath(camino, pRelleno)
            if (grosor > 0f && Color.alpha(borde) > 0) cv.drawPath(camino, pBorde)
        }

        override fun getOutline(outline: Outline) {
            if (Build.VERSION.SDK_INT >= 30) outline.setPath(camino)
            else @Suppress("DEPRECATION") outline.setConvexPath(camino)
        }

        /** Copia de la misma forma (para la máscara del brillo al tocar). */
        fun copia() = Forma(radio, corte, intArrayOf(Color.WHITE), diagonal, 0, 0f)

        override fun setAlpha(alpha: Int) { opacidad = alpha; invalidateSelf() }
        override fun setColorFilter(cf: ColorFilter?) { pRelleno.colorFilter = cf; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun forma(c: Context, radioDp: Float, corteDp: Float, colores: IntArray, borde: Int = 0, grosorDp: Float = 0f, diagonal: Boolean = true) =
        Forma(dp(c, radioDp), dp(c, corteDp), colores, diagonal, borde, dp(c, grosorDp))

    /** Rectángulo redondeado simple (cuando no va el corte diagonal). */
    fun redondo(c: Context, radioDp: Float, colores: IntArray, borde: Int = 0, grosorDp: Float = 0f) =
        forma(c, radioDp, 0f, colores, borde, grosorDp)

    // ------------------------------------------------------------------ fondo de pantalla
    /** Fondo de las pantallas: navy profundo con franjas diagonales de luz (como el banner). */
    class Fondo(private val intensidad: Float = 1f) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val franja = Path()

        override fun draw(cv: Canvas) {
            val b = bounds
            val w = b.width().toFloat(); val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return
            p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(NEGRO, FONDO, 0xFF07113A.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            cv.drawRect(b, p)
            // brillo azul abajo (como el banner del logo)
            p.shader = RadialGradient(w * 0.5f, h * 1.05f, maxOf(w, h) * 0.75f,
                intArrayOf(conAlfa(COBALTO, 0.38f * intensidad), 0x00000000), null, Shader.TileMode.CLAMP)
            cv.drawRect(b, p)
            val s = minOf(w, h)
            // franjas diagonales arriba a la derecha y abajo a la izquierda
            dibujarFranja(cv, w - s * 0.62f, -s * 0.10f, s * 0.95f, s * 0.20f, intArrayOf(conAlfa(ELECTRICO, 0.34f * intensidad), conAlfa(VIOLETA, 0.10f * intensidad)))
            dibujarFranja(cv, w - s * 0.30f, s * 0.20f, s * 0.70f, s * 0.11f, intArrayOf(conAlfa(VIOLETA, 0.26f * intensidad), conAlfa(INDIGO, 0.05f * intensidad)))
            dibujarFranja(cv, -s * 0.40f, h - s * 0.32f, s * 0.85f, s * 0.16f, intArrayOf(conAlfa(INDIGO, 0.05f * intensidad), conAlfa(ELECTRICO, 0.30f * intensidad)))
            p.shader = null
        }

        /** Franja inclinada 45° con puntas redondeadas. */
        private fun dibujarFranja(cv: Canvas, x: Float, y: Float, largo: Float, grueso: Float, cols: IntArray) {
            franja.reset()
            val d = grueso   // inclinación
            val r = grueso * 0.35f
            franja.moveTo(x + d, y)
            franja.lineTo(x + largo + d - r, y)
            franja.quadTo(x + largo + d, y, x + largo + d - r * 0.7f, y + r * 0.7f)
            franja.lineTo(x + largo, y + grueso)
            franja.lineTo(x + r, y + grueso)
            franja.quadTo(x, y + grueso, x + r * 0.7f, y + grueso - r * 0.7f)
            franja.close()
            p.shader = LinearGradient(x, y, x + largo, y + grueso, cols, null, Shader.TileMode.CLAMP)
            cv.save(); cv.rotate(-45f, x + largo / 2, y + grueso / 2)
            cv.drawPath(franja, p)
            cv.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    fun conAlfa(color: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    // ------------------------------------------------------------------ vida (animaciones)
    /**
     * Microinteracción de JTV: al presionar se hunde un poco y brilla; al soltar rebota.
     * Con mouse (PC, Chromebook, tablet) se agranda al pasar por encima.
     */
    fun vivo(v: View, hundir: Float = 0.94f, mascara: Drawable? = null) {
        val m = mascara ?: (v.background as? Forma)?.copia() ?: android.graphics.drawable.ColorDrawable(Color.WHITE)
        v.foreground = RippleDrawable(ColorStateList.valueOf(conAlfa(CIELO, 0.30f)), null, m)
        v.setOnTouchListener { vista, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> vista.animate().scaleX(hundir).scaleY(hundir).alpha(0.92f)
                    .setDuration(110).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> vista.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(320).setInterpolator(OvershootInterpolator(2.4f)).start()
            }
            false
        }
        v.setOnHoverListener { vista, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> vista.animate().scaleX(1.04f).scaleY(1.04f).translationZ(dp(vista.context, 6f)).setDuration(180).start()
                MotionEvent.ACTION_HOVER_EXIT -> vista.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(200).start()
            }
            false
        }
    }

    /** Pequeño salto al cambiar de estado (por ejemplo, al marcar la estrella). */
    fun pop(v: View) {
        v.animate().cancel()
        v.scaleX = 0.7f; v.scaleY = 0.7f
        v.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
    }

    /** Aparece desde abajo, suave. */
    fun aparecer(v: View, retraso: Long = 0) {
        v.alpha = 0f; v.translationY = dp(v.context, 14f)
        v.animate().alpha(1f).translationY(0f).setStartDelay(retraso).setDuration(420).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    /** Brillo que respira (para el punto EN VIVO). */
    fun latir(v: View) {
        val a = ValueAnimator.ofFloat(1f, 0.35f).apply {
            duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { v.alpha = it.animatedValue as Float }
        }
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) { if (!a.isStarted) a.start() }
            override fun onViewDetachedFromWindow(x: View) { a.cancel() }
        })
        if (v.isAttachedToWindow) a.start()
    }

    // ------------------------------------------------------------------ piezas
    fun sp(t: TextView, v: Float) = t.setTextSize(TypedValue.COMPLEX_UNIT_SP, v)

    fun titular(c: Context, texto: String, tamSp: Float = 20f, color: Int = Color.WHITE) = TextView(c).apply {
        text = texto; typeface = titulo(c); setTextColor(color); sp(this, tamSp)
        letterSpacing = -0.01f
    }

    /** Etiqueta chica en mayúsculas (sobre los títulos). */
    fun etiqueta(c: Context, texto: String, color: Int = CIELO) = TextView(c).apply {
        text = texto.uppercase(); typeface = textoFuerte(c); setTextColor(color); sp(this, 11f)
        letterSpacing = 0.18f
    }

    /** Botón principal: degradado eléctrico → violeta, esquinas cortadas. */
    fun botonPrimario(c: Context, texto: String, accion: () -> Unit) = TextView(c).apply {
        text = texto; typeface = titulo(c); setTextColor(Color.WHITE); sp(this, 15f)
        gravity = Gravity.CENTER
        setPadding(dpi(c, 20), dpi(c, 12), dpi(c, 20), dpi(c, 12))
        background = forma(c, 14f, 10f, MARCA)
        elevation = dp(c, 6f)
        outlineSpotShadowColorSeguro(this, ELECTRICO)
        isClickable = true; isFocusable = true
        setOnClickListener { accion() }
        vivo(this)
    }

    /** Botón secundario: navy translúcido con borde azul. */
    fun botonSecundario(c: Context, texto: String, accion: () -> Unit) = TextView(c).apply {
        text = texto; typeface = titulo(c); setTextColor(HIELO); sp(this, 15f)
        gravity = Gravity.CENTER
        setPadding(dpi(c, 18), dpi(c, 12), dpi(c, 18), dpi(c, 12))
        background = forma(c, 14f, 10f, intArrayOf(conAlfa(SUPERFICIE2, 0.85f), conAlfa(SUPERFICIE, 0.85f)), LINEA, 1.2f)
        isClickable = true; isFocusable = true
        setOnClickListener { accion() }
        vivo(this)
    }

    /** Botón cuadrado de ícono (texto/emoji o imagen), con esquinas cortadas. */
    fun botonIcono(c: Context, glifo: String, tamDp: Int = 44, primario: Boolean = false, accion: () -> Unit) = TextView(c).apply {
        text = glifo; gravity = Gravity.CENTER; setTextColor(Color.WHITE); sp(this, tamDp * 0.40f)
        typeface = titulo(c)
        background = if (primario) forma(c, tamDp * 0.32f, tamDp * 0.20f, MARCA)
                     else forma(c, tamDp * 0.32f, tamDp * 0.20f, intArrayOf(conAlfa(SUPERFICIE2, 0.9f), conAlfa(SUPERFICIE, 0.9f)), LINEA, 1.2f)
        isClickable = true; isFocusable = true
        setOnClickListener { accion() }
        vivo(this, 0.9f)
    }

    /** Chip de categoría. pintarChip cambia su estado. */
    fun chip(c: Context, texto: String, activo: Boolean, accion: () -> Unit) = TextView(c).apply {
        text = texto; gravity = Gravity.CENTER; sp(this, 14f)
        setPadding(dpi(c, 16), dpi(c, 8), dpi(c, 16), dpi(c, 8))
        isClickable = true; isFocusable = true
        setOnClickListener { accion() }
        pintarChip(this, activo)
        vivo(this, 0.92f, forma(c, 12f, 8f, intArrayOf(Color.WHITE)))
    }

    fun pintarChip(t: TextView, activo: Boolean) {
        val c = t.context
        t.typeface = if (activo) titulo(c) else textoFuerte(c)
        t.setTextColor(if (activo) Color.WHITE else TEXTO2)
        t.background = if (activo) forma(c, 12f, 8f, MARCA)
                       else forma(c, 12f, 8f, intArrayOf(conAlfa(SUPERFICIE, 0.9f)), LINEA, 1f)
        t.elevation = if (activo) dp(c, 4f) else 0f
    }

    /** Insignia "EN VIVO" con el punto que late. */
    fun insigniaVivo(c: Context, chica: Boolean = false) = TextView(c).apply {
        text = "●  EN VIVO"; typeface = titulo(c); setTextColor(Color.WHITE); sp(this, if (chica) 9.5f else 11f)
        letterSpacing = 0.08f
        setPadding(dpi(c, 8), dpi(c, 2), dpi(c, 9), dpi(c, 2))
        background = forma(c, 5f, 4f, intArrayOf(VIVO, 0xFFE0305F.toInt()))
    }

    /** Logo oficial (imagen, sin cambios). */
    fun logo(c: Context, altoDp: Int, blanco: Boolean = false) = ImageView(c).apply {
        setImageResource(if (blanco) R.drawable.jtv_logo_blanco else R.drawable.jtv_logo_color)
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "JTV"
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dpi(c, altoDp))
    }

    /** Barra fina de degradado (acento bajo los títulos). */
    fun acento(c: Context) = View(c).apply { background = forma(c, 2f, 0f, intArrayOf(CIELO, ELECTRICO, VIOLETA)) }

    private fun outlineSpotShadowColorSeguro(v: View, color: Int) {
        if (Build.VERSION.SDK_INT >= 28) { v.outlineSpotShadowColor = color; v.outlineAmbientShadowColor = color }
    }

    /** Sombra de color azul (Android 9+). */
    fun sombraAzul(v: View, elevDp: Float = 8f) {
        v.elevation = dp(v.context, elevDp)
        outlineSpotShadowColorSeguro(v, ELECTRICO)
    }
}
