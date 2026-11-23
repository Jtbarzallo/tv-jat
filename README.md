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
- 📡 **Ver en TV** (Android TV / Chromecast): en el reproductor toca *📡 Ver en TV* y elige la TV.
  La TV descarga el canal sola; el celular puede apagar la pantalla y casi no gasta batería.
  También funciona con *Mis Películas* (el celular presta el archivo por el WiFi).
- 📺 Se puede instalar **directo en la Android TV** (aparece en su pantalla de inicio).
- 🚗 **Android Auto** (dos íconos en el carro):
  - **TV JAT**: los canales se escuchan como radio (ahorra datos). Favoritos, grupos y búsqueda por voz.
  - **TV JAT Video**: canales y *Mis Películas* **con imagen** en la pantalla del carro (como Fermata Auto).
    Modo pasajero (activado): la imagen se ve también en marcha. Se puede apagar en Menú → Modo pasajero.
  Como la app no viene de Play Store: en Android Auto → Ajustes → tocar 10 veces "Versión" →
  ⋮ Configuración para desarrolladores → activar **Fuentes desconocidas**.

## 💻 TV JAT para PC (Windows)
Descarga en **Releases** la versión `pc-v1.0.N`:
- **TVJAT-PC-Instalador.exe**: se instala con ícono en el escritorio y el menú Inicio.
- **TVJAT-PC.exe**: portátil, se abre sin instalar.

Tiene canales (listas, grupos, favoritos, buscador, revisión) y Mis Películas (carpetas del PC).
Teclas: ↑/↓ cambiar canal · Espacio pausa · F pantalla completa · C lista de canales · Ctrl+F buscar.
Respaldo automático en **Documentos\TVJAT\respaldo_tv_jat.json** (mismo formato que el del celular).

## Respaldo (regla JAT)
Todo se copia solo en **Descargas/TVJAT/respaldo_tv_jat.json** (lista completa, favoritos,
listas usadas y último canal). Si se desinstala la app: ⋮ → *Recuperar respaldo…*

## Compilación
GitHub Actions compila y publica el APK en cada cambio dentro de `android/`.
Si falla, el error queda en la rama `registro-compilacion` (archivo `errores.txt`).
