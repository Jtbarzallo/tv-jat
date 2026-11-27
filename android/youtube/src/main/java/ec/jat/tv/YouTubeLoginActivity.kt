package ec.jat.tv

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Iniciar sesión de YouTube desde el CELULAR (en el carro no hay teclado cómodo).
 * La sesión queda guardada dentro de JTV YouTube y el YouTube del carro ya sale con tu cuenta.
 * Se abre desde JTV → ⋮ → "Iniciar sesión de YouTube (carro)".
 * (Google a veces no deja iniciar sesión desde navegadores dentro de apps; si pasa, YouTube igual funciona sin cuenta.)
 */
class YouTubeLoginActivity : Activity() {
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
        barra.addView(Jtv.titular(this, "YouTube", 18f).apply { setPadding((10 * d).toInt(), 0, 0, 0) })
        barra.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        barra.addView(Jtv.botonPrimario(this, "Listo ✓") { YouTubeWeb.guardarSesion(); finish() })
        raiz.addView(barra)
        raiz.addView(Jtv.etiqueta(this, "Inicia sesión aquí · luego se ve con tu cuenta en el carro").apply {
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), (8 * d).toInt()); letterSpacing = 0.06f
        })
        val w = YouTubeWeb.crear(this, YouTubeWeb.LOGIN)
        web = w
        raiz.addView(FrameLayout(this).apply { addView(w) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(raiz)
        // ubicación: solo se usa si el "modo pasajero" está apagado, para tapar la imagen al manejar
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else { YouTubeWeb.guardarSesion(); @Suppress("DEPRECATION") super.onBackPressed() }
    }

    override fun onPause() { super.onPause(); YouTubeWeb.guardarSesion() }

    override fun onDestroy() {
        web?.destroy(); web = null
        super.onDestroy()
    }
}
