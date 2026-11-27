package ec.jat.tv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pantalla pequeña en el CELULAR para dar a JTV Video sus permisos de una vez:
 * 🎤 micrófono (búsqueda por voz en el carro), 📍 ubicación (modo pasajero) y 🎬 videos (Mis Películas).
 * Se abre desde JTV → ⋮ → "🎤 Activar micrófono del carro".
 */
class PermisosActivity : Activity() {
    private val PEDIR = 7
    private lateinit var texto: TextView

    private fun permisos(): List<String> {
        val l = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION)
        l.add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE)
        return l
    }

    private fun tiene(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Jtv.Fondo()
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        }
        caja.addView(Jtv.logo(this, 44), LinearLayout.LayoutParams(-2, (44 * d).toInt()))
        caja.addView(Jtv.titular(this, "Video (carro)", 22f).apply { gravity = Gravity.CENTER; setPadding(0, (12 * d).toInt(), 0, 0) })
        texto = TextView(this).apply {
            setTextColor(Jtv.TEXTO2); textSize = 18f; gravity = Gravity.CENTER
            setPadding(0, (18 * d).toInt(), 0, (22 * d).toInt())
        }
        caja.addView(texto)
        caja.addView(Jtv.botonPrimario(this, "Abrir ajustes de la app") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, LinearLayout.LayoutParams(-2, -2))
        caja.addView(Jtv.botonSecundario(this, "Listo") { finish() },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = (12 * d).toInt() })
        setContentView(caja)
        pintar()
        val faltan = permisos().filter { !tiene(it) }
        if (faltan.isNotEmpty()) requestPermissions(faltan.toTypedArray(), PEDIR)
    }

    override fun onResume() { super.onResume(); pintar() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pintar()
        Registro.anotar(this, "   🎤 permisos de JTV Video: micrófono ${if (tiene(Manifest.permission.RECORD_AUDIO)) "✅" else "❌"}")
    }

    private fun pintar() {
        val mic = tiene(Manifest.permission.RECORD_AUDIO)
        val gps = tiene(Manifest.permission.ACCESS_FINE_LOCATION) || tiene(Manifest.permission.ACCESS_COARSE_LOCATION)
        val vid = tiene(permisos().last())
        texto.text = (if (mic) "✅" else "❌") + " Micrófono (🎤 Voz en el carro)\n" +
            (if (gps) "✅" else "❌") + " Ubicación (modo pasajero)\n" +
            (if (vid) "✅" else "❌") + " Videos (Mis Películas)\n\n" +
            (if (mic) "¡Listo! Ya puedes usar 🎤 Voz en el carro."
             else "Toca «Permitir» cuando el celular pregunte.\nSi no pregunta, toca «Abrir ajustes de la app» → Permisos → Micrófono → Permitir.")
    }
}
