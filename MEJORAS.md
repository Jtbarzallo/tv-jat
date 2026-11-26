# Lista de mejoras — TV JAT

## Hecho
- [x] v1.0 (2026-11-17): lista de canales, buscador, grupos, favoritos, reproductor HLS/DASH,
      anterior/siguiente, ajuste de imagen, PiP, abrir archivo/enlace/compartido,
      respaldo automático en Descargas/TVJAT.

- [x] v1.1 (2026-11-17): 🩺 revisión de canales desde el celular (oculta los que no responden),
      se hace sola al cargar una lista; opción mostrar/ocultar caídos; lista "Canales en español";
      en el reproductor, si un canal falla pasa solo al siguiente.
- [x] v1.2 (2026-11-17): botón "☰ Canales" en el reproductor: panel lateral con la lista que se
      desplaza mientras sigue el video; tocar un canal lo cambia; ✕ o atrás lo cierra.
- [x] v1.3 (2026-11-17): menú "Más listas en español latino": Pluto TV México/Argentina/Chile
      y canales de México, Colombia, Argentina, Chile, Perú y Venezuela (listas gratis y legales).
- [x] v1.4 (2026-11-18): 🎬 "Mis Películas" estilo Netflix con ícono propio: busca los videos del
      celular, portada grande, filas "Seguir viendo", "Agregadas recientemente", por carpeta,
      "Ya vistas" y "Todas (A-Z)", buscador, títulos limpios; sigue donde se quedó, barra roja de
      avance, marcar vista/no vista, pasa sola al siguiente video de la carpeta (series).
- [x] v1.5 (2026-11-22): 🚗 Android Auto: TV JAT aparece en el carro como app de audio; los canales
      se escuchan como radio (sin imagen, ahorra datos). Menú: ★ Favoritos, 📺 Todos los canales y
      un menú por grupo; logos de los canales; búsqueda y "Ok Google, pon … en TV JAT"; si un canal
      no responde pasa solo al siguiente; al dar play sigue en el último canal visto.
- [x] v1.6 (2026-11-22): 📡 "Ver en TV" (Google Cast) en canales y en Mis Películas: el celular solo le
      pasa la dirección del canal a la Android TV y la TV lo descarga sola (el celular deja de descargar
      y puede apagar la pantalla). Capa de control: anterior/siguiente, lista de canales, pausa, volver
      al celular. Películas: el celular las presta por el WiFi y siguen desde donde iban (se guarda el
      avance que lleva en la TV). TV JAT también se puede instalar directo en la Android TV.
- [x] v1.7 (2026-11-23): Ver en TV: arreglo de "solo se oye, no se ve". Antes de enviar, la app revisa
      por dentro el canal (TS o fMP4) y se lo dice a la TV.
- [x] v1.8 (2026-11-23): 🚗🖼️ "TV JAT Video" en Android Auto: canales y Mis Películas CON IMAGEN en
      la pantalla del carro (mismo método que Fermata Auto: pantalla libre de las apps de navegación).
      Botones Menú · anterior · pausa · siguiente (en películas: −10 s / +30 s). Menú: Favoritos, Todos,
      por grupo, Mis Películas (seguir viendo, recientes, por carpeta) y Buscar. Sigue en el último canal.
      Seguridad: con el GPS del celular, mientras el carro avanza la imagen se tapa y sigue el sonido.
      Requisito: Android Auto → Ajustes de desarrollador → "Fuentes desconocidas".
- [x] v1.9 (2026-11-23): 👥 "Modo pasajero" en TV JAT Video (activado de fábrica): la imagen se ve también
      con el carro en marcha (el video es para los pasajeros). Se apaga/enciende en Menú → Modo pasajero;
      apagado, vuelve a tapar la imagen al avanzar (GPS).
- [x] PC v1.0 (2026-11-23): 💻 TV JAT para PC (Windows, carpeta pc/, programa Electron): canales con lista,
      grupos, favoritos, buscador, revisión de canales, menú de listas (Ecuador, español, latinas, archivo,
      enlace, recientes), reproductor HLS/TS con panel ☰ Canales y pantalla completa, teclado (↑/↓ canal,
      Espacio pausa, F pantalla completa, C canales); Mis Películas con carpetas del PC, filas estilo Netflix,
      sigue donde se quedó y pasa al siguiente capítulo. Instalador y versión portátil en Releases (pc-v1.0.N).
- [x] v1.10 (2026-11-23): arreglo TV JAT Video "se oye pero no se ve": la imagen ahora se dibuja con
      TextureView (antes PlayerView/SurfaceView, que en la pantalla del carro quedaba negra). Nuevas opciones
      en Menú: "Forma de imagen" A (ajustada) / B (directa al carro) y "Diagnóstico" (pantalla, forma, video,
      estado, error) para saber qué falla sin adivinar.
- [x] v1.11 (2026-11-23): Android Auto (TV JAT radio): los logos salían como triángulos ⚠ porque el carro no
      baja imágenes de internet → nuevo LogoProvider que las baja y entrega al carro (si no hay logo, dibuja
      las iniciales del canal). Canales en lista (nombres completos) en vez de cuadritos.
      🔧 Diagnóstico Android Auto en el celular (⋮): anota qué pide el carro, qué canal, estado, errores y
      cierres inesperados de la app; se puede compartir.
- [x] v1.12 (2026-11-23): TV JAT Video pasa a ser una APP APARTE (TVJAT-Video.apk, paquete ec.jat.tv.video):
      Android Auto no mostraba el ícono de video porque en un mismo programa solo muestra la app de audio.
      TV JAT Video no guarda nada propio: pide la lista, favoritos, último canal y avance de películas a TV JAT
      por un puente seguro (DatosProvider, solo apps firmadas con la llave JAT) y guarda allá → mismo respaldo.
      Su diagnóstico también se anota en TV JAT (⋮ → 🔧 Diagnóstico Android Auto).

- [x] v1.13 (2026-11-24): 🚗 ⋮ → "Poner TV JAT Video en el carro": TV JAT descarga TVJAT-Video.apk y la
      instala marcada como de Google Play (sin eso Android Auto esconde las apps del carro con imagen,
      aunque "Fuentes desconocidas" esté activo). Si ya estaba instalada desde Chrome, avisa al abrir TV JAT
      y la reinstala paso a paso. El diagnóstico muestra quién la instaló.
## Pendiente / ideas
- [x] v1.14 (2026-11-24): 🚗 TV JAT Video: video más fluido (colchón de 6 s antes de seguir, en vivo 15 s
      atrás del directo y siempre a velocidad normal, calidad máxima 720p como la pantalla del carro); imagen en
      TODA la pantalla (Completa/Entera en ⚙); panel al costado con canales, grupos, Mis Películas y opciones
      que se toca y se desliza con el dedo mientras sigue el video (botón ☰ Canales o tocar la imagen).
      Diagnóstico: cuadros perdidos y pausas por carga.
- [x] v1.15 (2026-11-24): 🚗 TV JAT Video con interfaz nueva estilo "TV del carro" (según foto de Jorge): barra
      de arriba (📺 Canales · 🎬 Películas · TV JAT · 🔍), lista de canales a la DERECHA con número, logo y etiqueta
      roja EN VIVO (el canal que suena resaltado en azul), botones redondos abajo (☰ Guía · Anterior · Pausa grande
      · Siguiente · ✕ Cerrar TV) y barra de información (canal y grupo, barra de colchón en vivo o de avance en
      películas —se toca para saltar—, ● EN VIVO, ⚙ Ajustes, 📶 Calidad 720p/1080p/480p). Aparece al abrir y al
      tocar la imagen; se esconde sola a los 12 s si está reproduciendo. Se acomoda a la parte que Android Auto
      no tapa.
- [x] v1.16 (2026-11-25): 🚗 TV JAT Video:
      · ☰ Lista: la lista de canales sale ENCIMA del video (antes, en carros que no pasan los toques, se iba a
        otra pantalla); ✕ o tocar la imagen la quita. Botones ▲ ▼ ✓ al costado para moverse por la lista con un
        cuadro blanco sin tocar la pantalla.
      · 🎤 Voz en vez del teclado: dices el nombre del canal (micrófono del carro; si no se puede, el del celular),
        busca parecidos aunque no sea exacto (sin tildes, "pon Ecuavisa", "tele amazonas") y lo pone solo; si hay
        varios, los muestra para elegir. El teclado sigue en "⌨ Escribir con teclado". Mientras escucha, baja el
        volumen de la TV.
      · Línea de tiempo: guarda hasta 2 min adelante y 90 s ya vistos atrás; el en vivo va 30 s detrás del directo
        (colchón); espera 8 s guardados antes de seguir si se corta. Barra: azul = guardado, punto blanco = lo que
        ves, hora que estás viendo; se toca para regresar y "● Ir a EN VIVO" vuelve al directo.
      · ⭐ Estrella junto al nombre del canal (y en los botones del carro): agrega/quita de Favoritos sin salir.
- [x] v1.17 (2026-11-25): 🚗 TV JAT Video más fácil y fluido:
      · 📼 GRABADOR (línea de tiempo de verdad): mientras ves un canal HLS en vivo, TV JAT lo va bajando y guardando en
        el celular (hasta 10 min, carpeta temporal que se borra al cambiar de canal). El video se ve desde lo grabado
        (servidor solo dentro del celular): si el internet baja, sigue con lo grabado; la barra deja regresar y
        "● Ir a EN VIVO" vuelve; en pausa sigue grabando. Elige la calidad ≤ la de 📶 Calidad. Si el canal no se
        puede grabar (no es HLS, tiene fin o trae el audio aparte) se ve directo como antes.
      · Se quitó la elección "Forma A/B": siempre forma normal (con lista encima); si la imagen sale negra la app
        pasa sola a la directa.
      · Sin internet: ya no salta de canal en canal; avisa "📶 Sin internet" y reintenta el MISMO canal cada 5 s.
      · Decodificador de respaldo (canales 1080p que daban "Decoder init failed").
      · Menos lag: favoritos y calidad en memoria (antes se preguntaba a TV JAT cada segundo); se quitó el retraso
        de 30 s que causaba "BEHIND_LIVE_WINDOW" (cortes y recargas).
- [x] v1.18 (2026-11-25): 📱 El reproductor de canales del CELULAR funciona igual que el del carro: tocar el video
      muestra/esconde la interfaz (lista a la derecha con número, logo y EN VIVO; botones redondos Guía · Anterior ·
      Pausa · Siguiente · Cerrar; barra de información con ⭐, línea de tiempo, ● EN VIVO/Ir a EN VIVO, ⚙ Ajustes,
      📶 Calidad); 🎤 Voz (reconocimiento de voz de Google, sin teclado); grabador (línea de tiempo hasta 10 min);
      sin internet reintenta el mismo canal; decodificador de respaldo. Se conservan 📡 Ver en TV, ventana flotante
      (PiP), control remoto (canal +/−, OK muestra la interfaz) y 🎬 Películas.
- [x] v1.19 (2026-11-26): 🚗 Video fluido en el carro: si no hay nada encima (lista, avisos, voz), el video va DIRECTO
      a la pantalla del carro (sin pantalla intermedia, que perdía cuadros); al tocar se pasa a la pantalla intermedia
      para mostrar la lista y, 1,5 s después de esconderse, vuelve a directo. El diagnóstico anota cada minuto los
      cuadros perdidos, el tamaño del video y la forma (directo / con lista).
- [ ] Grabador: grabar también los canales que traen el audio aparte (hoy se ven directo, sin línea de tiempo).
- [ ] Ver en TV: canales que piden User-Agent/Referer especial no abren en la TV (Google Cast no lo
      permite); para esos, instalar TV JAT directo en la Android TV.
- [ ] Mis Películas: subtítulos .srt con el mismo nombre del video.
- [ ] Mis Películas: carátulas y sinopsis desde internet.
- [ ] Guía de programación (EPG) si la lista trae `url-tvg`.
- [ ] Varias listas guardadas a la vez (no solo la actual).
- [ ] PC: portadas (cuadro del video) en Mis Películas; "Ver en TV" (Google Cast) desde el PC.
- [ ] Sincronizar solo entre celular y PC (hoy se pasa con el archivo de respaldo).

## Bitácora
- 2026-11-17: Creación de la app (pedido de Jorge: "app para reproducir m3u").
  Respaldo: Descargas/TVJAT/respaldo_tv_jat.json.
- 2026-11-17: Jorge reporta que la mayoría de canales no abren. Causa: la lista de iptv-org
  tiene muchas retransmisiones no oficiales que se caen. Se agrega revisión de canales (v1.1).
  Respaldo: la revisión (canales caídos, fecha) también va en respaldo_tv_jat.json.
- 2026-11-17: Jorge confirma que funciona bien (v1.1). Pide ver/desplazar la lista de canales
  mientras ve la TV → panel lateral (v1.2). Sin datos nuevos que respaldar.
- 2026-11-17: Jorge pide listas con canales en español latino que funcionen → v1.3.
  No se agregan listas piratas (canales de pago/películas sin permiso).
- 2026-11-18: Jorge pide "algo así como Netflix pero con mis películas del celular" → v1.4.
  Respaldo: el avance de cada película (minuto, vista) va en respaldo_tv_jat.json
  ("progresoPeliculas", clave = nombre de archivo + tamaño, sobrevive a reinstalar).
- 2026-11-22: Jorge pide que TV JAT funcione en Android Auto → v1.5. Android Auto no permite video
  en la pantalla del carro (solo apps de audio), así que los canales se escuchan. Usa la misma lista,
  favoritos y canales ocultos de la app; el último canal escuchado se guarda como "ultimo" (ya va en
  respaldo_tv_jat.json). Sin datos nuevos que respaldar.
- 2026-11-22: Jorge pide que TV JAT se transmita a la Android TV sin que el celular sufra con la
  batería → v1.6 con Google Cast (la TV descarga el canal; el celular no). Respaldo: el avance de las
  películas vistas en la TV se guarda en "progresoPeliculas" de respaldo_tv_jat.json; nada nuevo más.
- 2026-11-23: Jorge reporta que en la TV los canales solo se oyen, sin imagen. Causa: Google Cast supone
  otro formato de video si no se le dice; casi todos los canales M3U usan segmentos TS → v1.7 detecta
  el formato y lo envía. Sin datos nuevos que respaldar.
- 2026-11-23: Jorge pregunta por Fermata Auto y pide "arreglar eso con TV JAT" (video en Android Auto) → v1.8
  "TV JAT Video" (CarAppService de navegación que dibuja el video en la pantalla del carro). TV JAT (solo
  sonido) sigue igual. Respaldo: el avance de las películas vistas en el carro va en "progresoPeliculas" y el
  último canal en "ultimo" de respaldo_tv_jat.json; nada nuevo más.
- 2026-11-23: Jorge aclara que el video es para el pasajero (sus hijos) y decide que la imagen no se tape en
  marcha → v1.9 "Modo pasajero" activado de fábrica, con opción en el menú del carro. Respaldo: "modoPasajero"
  se guarda en respaldo_tv_jat.json.
- 2026-11-23: Jorge pide que TV JAT funcione en PC → TV JAT para PC v1.0 (Windows). Respaldo: automático en
  Documentos\TVJAT\respaldo_tv_jat.json, mismo formato que el del celular (se recupera uno en el otro con
  💾 Respaldo → Recuperar); el avance de películas usa la misma clave (nombre + tamaño), así que una película
  sigue en el mismo minuto si es el mismo archivo. Dato nuevo solo del PC: "pc.carpetas" (carpetas de películas).
- 2026-11-23: Jorge reporta que en el carro se oye pero no se ve (también estacionado). Cambio a TextureView,
  forma B de respaldo y diagnóstico. Respaldo: "formaImagen" va en respaldo_tv_jat.json.
- 2026-11-23: Jorge manda foto: en el carro (TV JAT radio) los logos salen ⚠ y al tocar no suena nada. Arreglo de
  logos + diagnóstico para ver la causa exacta del silencio. Respaldo: sin datos nuevos (el diagnóstico es
  solo un registro temporal).
- 2026-11-23: Jorge: en el carro solo aparece TV JAT (radio), no TV JAT Video. Causa: Android Auto muestra una sola
  app del carro por programa → se separó TV JAT Video en su propia APK. Respaldo: sin datos nuevos; todo sigue
  en respaldo_tv_jat.json de TV JAT.
- 2026-11-24: Jorge: TV JAT Video sigue sin salir en el carro (Fuentes desconocidas activo, Android Auto
  reiniciado). Causa real: Android Auto solo muestra apps de la librería del carro si figuran instaladas
  por Google Play; instalada desde Chrome la esconde. → v1.13 instalador interno en TV JAT que la registra
  como de Google Play (método de KingInstaller). Respaldo: sin datos nuevos (el APK descargado es temporal).
- 2026-11-24: Jorge confirma que TV JAT Video ya sale en el carro y funciona. Pide video fluido "sin detenerse",
  imagen en toda la pantalla y opciones/canales a un costado → v1.14. Respaldo: nuevo dato "ajusteImagen"
  (completa/entera) en respaldo_tv_jat.json.
- 2026-11-24: Jorge manda una foto de una app de TV para carro ("DriveTV") y pide "arregla jat tv, haz una interfaz
  similar" → v1.15 interfaz nueva en TV JAT Video. Respaldo: nuevo dato "calidad" (auto/alta/ahorro) en
  respaldo_tv_jat.json (se guarda en TV JAT por el puente, igual que los demás ajustes).

- 2026-11-25: Jorge confirma que v1.15 funciona. Reporta: 1) buscar abre teclado, incómodo en el carro → pide botón de
  voz; 2) para ver los canales tiene que salir de la pantalla del video (causa: el botón ☰ abría el menú de Android
  Auto cuando el carro no pasa los toques) → pide que la lista salga al tocar el video y se quite al tocar otra vez;
  3) video de corrido, sin pausas ni congelamientos, guardando en una línea de tiempo; 4) estrella para marcar
  favorito desde el video. Aprobó las imágenes de cómo quedaría → v1.16. Respaldo: los favoritos marcados con la
  estrella se guardan en TV JAT (misma lista "favoritos" de respaldo_tv_jat.json); el colchón/línea de tiempo y la
  voz no guardan datos (son temporales). Permiso nuevo en TV JAT Video: micrófono.
- 2026-11-25: Jorge prueba v1.16: "no funciona, se laguea, no hay línea de tiempo". Diagnóstico: estaba en Forma B
  (directa, sin lista ni barra encima del video), errores BEHIND_LIVE_WINDOW repetidos (el retraso de 30 s que puse
  era mayor que lo que guardan muchos canales), saltos de canal en canal cuando no había internet, y "Decoder init
  failed" en canales 1080p. Jorge pide que sea lo más fácil de usar → v1.17. Respaldo: sin datos nuevos (lo grabado
  es temporal en la caché; "formaImagen" ya no se usa).
- 2026-11-25: Jorge pide que la app del teléfono funcione igual a la del auto (aprobó la imagen) → v1.18: nuevo
  PlayerActivity con la misma interfaz, voz, estrella, grabador y arreglos del carro. Respaldo: favoritos y calidad
  ya van en respaldo_tv_jat.json; lo grabado es temporal (caché). Sin datos nuevos.
- 2026-11-26: Jorge: "funciona mejor, pero el video no es fluido". Diagnóstico: el grabador funciona (Golden, Ecuavisa
  grabados sin una sola pausa por carga) → no es el internet; es el dibujo del video en la pantalla intermedia
  (Presentation/TextureView) que pierde cuadros → v1.19 salida directa cuando no hay nada encima. Los correos de
  "Run failed" eran: tv-jat 62ff028 (ya corregido en 08194f4) y JAT Control Suite PC 46b3a95 (versión vieja re-ejecutada;
  las nuevas compilan bien). Respaldo: sin datos nuevos.
