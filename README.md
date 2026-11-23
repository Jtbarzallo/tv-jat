# 📺 TV JAT

App Android para ver canales de TV desde listas **M3U / M3U8** (IPTV).

## Descargar
Última versión (APK): **Releases → TVJAT.apk**
https://github.com/Jtbarzallo/tv-jat/releases/latest

## Qué hace
- Trae lista de **canales de Ecuador** (gratuitos, proyecto iptv-org) al abrirla por primera vez.
- Abre cualquier lista M3U: desde un **archivo**, un **enlace** o compartida desde otra app.
- Buscador, grupos, **favoritos ★** y marca del último canal visto (▶).
- Reproductor a pantalla completa: canal anterior/siguiente, ajustar/zoom/estirar imagen,
  ventanita flotante (PiP) al salir con el botón de inicio.
- Respeta `User-Agent` y `Referer` de cada canal (`#EXTVLCOPT` o `url|User-Agent=…`).
- 🚗 **Android Auto**: en el carro los canales se escuchan como radio (Android Auto no permite video).
  Menú de Favoritos, Todos los canales y grupos, con búsqueda por voz.
  Como la app no viene de Play Store: en Android Auto → Ajustes → tocar 10 veces "Versión" →
  ⋮ Configuración para desarrolladores → activar **Fuentes desconocidas**.

## Respaldo (regla JAT)
Todo se copia solo en **Descargas/TVJAT/respaldo_tv_jat.json** (lista completa, favoritos,
listas usadas y último canal). Si se desinstala la app: ⋮ → *Recuperar respaldo…*

## Compilación
GitHub Actions compila y publica el APK en cada cambio dentro de `android/`.
Si falla, el error queda en la rama `registro-compilacion` (archivo `errores.txt`).
