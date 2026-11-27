package ec.jat.tv

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Iniciar sesión de TikTok desde el CELULAR (en el carro no hay teclado cómodo).
 * La sesión queda guardada dentro de JTV Video y el TikTok del carro ya sale con tu cuenta.
 * Se abre desde JTV → ⋮ → "Iniciar sesión de TikTok (carro)".
 */
class TikTokActivity : Activity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Jtv.NEGRO
        val d = resources.displayMetrics.density
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Jtv.Fondo() }
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
        }
        barra.addView(Jtv.logo(this, 26), LinearLayout.LayoutParams(-2, (26 * d).toInt()))
        barra.addView(Jtv.titular(this, "TikTok", 18f).apply { setPadding((10 * d).toInt(), 0, 0, 0) })
        barra.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        barra.addView(Jtv.botonPrimario(this, "Listo ✓") { TikTok.guardarSesion(); finish() })
        raiz.addView(barra)
        raiz.addView(Jtv.etiqueta(this, "Inicia sesión aquí · luego se ve con tu cuenta en el carro").apply {
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), (8 * d).toInt()); letterSpacing = 0.06f
        })
        val w = TikTok.crearWeb(this, TikTok.LOGIN)
        web = w
        raiz.addView(FrameLayout(this).apply { addView(w) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(raiz)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else { TikTok.guardarSesion(); @Suppress("DEPRECATION") super.onBackPressed() }
    }

    override fun onPause() { super.onPause(); TikTok.guardarSesion() }

    override fun onDestroy() {
        web?.destroy(); web = null
        super.onDestroy()
    }
}
