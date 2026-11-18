# Lista de mejoras — TV JAT

## Hecho
- [x] v1.0 (2026-11-17): lista de canales, buscador, grupos, favoritos, reproductor HLS/DASH,
      anterior/siguiente, ajuste de imagen, PiP, abrir archivo/enlace/compartido,
      respaldo automático en Descargas/TVJAT.

- [x] v1.1 (2026-11-17): 🩺 revisión de canales desde el celular (oculta los que no responden),
      se hace sola al cargar una lista; opción mostrar/ocultar caídos; lista "Canales en español";
      en el reproductor, si un canal falla pasa solo al siguiente.

## Pendiente / ideas
- [ ] Guía de programación (EPG) si la lista trae `url-tvg`.
- [ ] Varias listas guardadas a la vez (no solo la actual).

## Bitácora
- 2026-11-17: Creación de la app (pedido de Jorge: "app para reproducir m3u").
  Respaldo: Descargas/TVJAT/respaldo_tv_jat.json.
- 2026-11-17: Jorge reporta que la mayoría de canales no abren. Causa: la lista de iptv-org
  tiene muchas retransmisiones no oficiales que se caen. Se agrega revisión de canales (v1.1).
  Respaldo: la revisión (canales caídos, fecha) también va en respaldo_tv_jat.json.
