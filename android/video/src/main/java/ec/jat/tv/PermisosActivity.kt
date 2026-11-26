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
 * Pantalla pequeña en el CELULAR para dar a TV JAT Video sus permisos de una vez:
 * 🎤 micrófono (búsqueda por voz en el carro), 📍 ubicación (modo pasajero) y 🎬 videos (Mis Películas).
 * Se abre desde TV JAT → ⋮ → "🎤 Activar micrófono del carro".
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
            setBackgroundColor(0xFF0B0E1A.toInt())
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        }
        caja.addView(TextView(this).apply {
            text = "TV JAT Video (carro)"
            setTextColor(Color.WHITE); textSize = 24f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        })
        texto = TextView(this).apply {
            setTextColor(0xFFDDE3F0.toInt()); textSize = 19f; gravity = Gravity.CENTER
            setPadding(0, (18 * d).toInt(), 0, (18 * d).toInt())
        }
        caja.addView(texto)
        caja.addView(Button(this).apply {
            text = "Abrir ajustes de la app"; textSize = 17f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
        caja.addView(Button(this).apply {
            text = "Listo"; textSize = 17f
            setOnClickListener { finish() }
        })
        setContentView(caja)
        pintar()
        val faltan = permisos().filter { !tiene(it) }
        if (faltan.isNotEmpty()) requestPermissions(faltan.toTypedArray(), PEDIR)
    }

    override fun onResume() { super.onResume(); pintar() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pintar()
        Registro.anotar(this, "   🎤 permisos de TV JAT Video: micrófono ${if (tiene(Manifest.permission.RECORD_AUDIO)) "✅" else "❌"}")
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
