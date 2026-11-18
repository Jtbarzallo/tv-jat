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

## Pendiente / ideas
- [ ] Mis Películas: subtítulos .srt con el mismo nombre del video.
- [ ] Mis Películas: carátulas y sinopsis desde internet.
- [ ] Guía de programación (EPG) si la lista trae `url-tvg`.
- [ ] Varias listas guardadas a la vez (no solo la actual).

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
