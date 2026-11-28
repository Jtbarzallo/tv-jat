package ec.jat.tv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Tracks

/**
 * Revisa el audio de cada canal y lo anota en el diagnóstico (por ejemplo "audio ac-3 · 6 canales · ✅ se oye").
 * Algunos canales de cable traen audio Dolby (AC-3 / E-AC-3) que muchos celulares no traen: para esos JTV
 * lleva su propio decodificador (FFmpeg). Si aun así no se puede, lo dice.
 */
object Audio {
    private var ultimo = ""

    /** true = hay audio que se puede oír · false = el canal trae audio pero no se puede reproducir · null = sin audio. */
    fun revisar(ctx: Context, t: Tracks): Boolean? {
        val grupos = t.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (grupos.isEmpty()) return null
        val partes = ArrayList<String>()
        var alguno = false
        for (g in grupos) for (i in 0 until g.length) {
            val f = g.getTrackFormat(i)
            val ok = g.isTrackSupported(i)
            if (ok) alguno = true
            partes.add("${(f.sampleMimeType ?: "?").substringAfter('/')} · ${f.channelCount} canales · " + if (ok) "✅ se oye" else "❌ no se puede")
        }
        val texto = "   🔊 audio: " + partes.distinct().joinToString(" | ")
        if (texto != ultimo) { ultimo = texto; Registro.anotar(ctx, texto) }
        return alguno
    }
}
