package ec.jat.tv

/** Pasa la lista de canales de la pantalla principal al reproductor. */
object Reproduccion {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    var canales: List<Canal> = emptyList()
    var inicio: Int = 0
}
