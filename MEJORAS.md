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

## Pendiente / ideas
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
