// TV JAT para PC — pantalla
'use strict';

const LISTA_ECUADOR = 'https://iptv-org.github.io/iptv/countries/ec.m3u';
const LISTA_ESPANOL = 'https://iptv-org.github.io/iptv/languages/spa.m3u';
const LISTAS_LATINAS = [
  ['🍿 Pluto TV México (cine, series, novelas)', 'https://raw.githubusercontent.com/BuddyChewChew/pluto/main/pluto_mx.m3u'],
  ['🍿 Pluto TV Argentina', 'https://raw.githubusercontent.com/BuddyChewChew/pluto/main/pluto_ar.m3u'],
  ['🍿 Pluto TV Chile', 'https://raw.githubusercontent.com/BuddyChewChew/pluto/main/pluto_cl.m3u'],
  ['🇲🇽 Canales de México', 'https://raw.githubusercontent.com/iptv-org/iptv/master/streams/mx.m3u'],
  ['🇨🇴 Canales de Colombia', 'https://raw.githubusercontent.com/iptv-org/iptv/master/streams/co.m3u'],
  ['🇦🇷 Canales de Argentina', 'https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ar.m3u'],
  ['🇨🇱 Canales de Chile', 'https://raw.githubusercontent.com/iptv-org/iptv/master/streams/cl.m3u'],
  ['🇵🇪 Canales de Perú', 'https://raw.githubusercontent.com/iptv-org/iptv/master/streams/pe.m3u'],
  ['🇻🇪 Canales de Venezuela', 'https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ve.m3u']
];

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

// ===================================================================== datos + respaldo
// Mismo formato que el respaldo del celular (respaldo_tv_jat.json): se puede pasar de uno a otro.
let D = nuevoDatos();
function nuevoDatos() {
  return {
    app: 'TV JAT', version: 1, fecha: '', nombreLista: '', fuente: '', favoritos: [], recientes: [],
    ultimo: '', caidos: [], fechaRevision: '', ocultarCaidos: true, modoPasajero: true,
    progresoPeliculas: {}, listaM3u: '', pc: { carpetas: [] }
  };
}
let tGuardar = null;
function guardar(inmediato) {
  clearTimeout(tGuardar);
  const hacer = () => {
    D.fecha = fechaTexto(new Date());
    const texto = JSON.stringify(D, null, 2);
    try { localStorage.setItem('tvjat', texto); } catch (e) {}
    window.pc.guardarRespaldo(texto);   // Documentos\TVJAT\respaldo_tv_jat.json
  };
  if (inmediato) hacer(); else tGuardar = setTimeout(hacer, 800);
}
function fechaTexto(d) {
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
function unirDatos(j, reemplazarTodo) {
  if (j.app !== 'TV JAT') throw new Error('Ese archivo no es un respaldo de TV JAT');
  const base = reemplazarTodo ? nuevoDatos() : D;
  if (j.listaM3u) base.listaM3u = j.listaM3u;
  base.favoritos = [...new Set([...(reemplazarTodo ? [] : base.favoritos), ...(j.favoritos || [])])];
  for (const k of ['nombreLista', 'fuente', 'ultimo', 'fechaRevision']) if (j[k] !== undefined) base[k] = j[k];
  if (Array.isArray(j.recientes)) base.recientes = j.recientes;
  if (Array.isArray(j.caidos)) base.caidos = j.caidos;
  if (typeof j.ocultarCaidos === 'boolean') base.ocultarCaidos = j.ocultarCaidos;
  if (typeof j.modoPasajero === 'boolean') base.modoPasajero = j.modoPasajero;
  // avance de películas: lo más reciente manda
  const pp = j.progresoPeliculas || {};
  for (const k of Object.keys(pp)) {
    const v = base.progresoPeliculas[k];
    if (!v || (pp[k].t || 0) >= (v.t || 0)) base.progresoPeliculas[k] = pp[k];
  }
  if (j.pc && Array.isArray(j.pc.carpetas)) base.pc = { carpetas: [...new Set([...(base.pc?.carpetas || []), ...j.pc.carpetas])] };
  if (!base.pc) base.pc = { carpetas: [] };
  D = base;
}

// ===================================================================== lista M3U
function leerM3u(texto) {
  const canales = [];
  let nombre = '', logo = '', grupo = '', ua = '', ref = '', hayInfo = false;
  for (const crudo of String(texto || '').split(/\r?\n/)) {
    const linea = crudo.trim().replace(/^﻿/, '');
    if (!linea) continue;
    if (/^#EXTINF/i.test(linea)) {
      hayInfo = true;
      let comillas = false, corte = -1;
      for (let i = 0; i < linea.length; i++) {
        const c = linea[i];
        if (c === '"') comillas = !comillas; else if (c === ',' && !comillas) corte = i;
      }
      const cab = corte >= 0 ? linea.slice(0, corte) : linea;
      nombre = corte >= 0 ? linea.slice(corte + 1).trim() : '';
      logo = ''; grupo = '';
      for (const m of cab.matchAll(/([\w-]+)="([^"]*)"/g)) {
        const k = m[1].toLowerCase();
        if (k === 'tvg-logo') logo = m[2].trim();
        else if (k === 'group-title') grupo = m[2].trim();
        else if (k === 'tvg-name' && !nombre) nombre = m[2].trim();
      }
    } else if (/^#EXTGRP:/i.test(linea)) grupo = linea.slice(linea.indexOf(':') + 1).trim();
    else if (/^#EXTVLCOPT:/i.test(linea)) {
      const op = linea.slice(linea.indexOf(':') + 1);
      if (/^http-user-agent=/i.test(op)) ua = op.slice(op.indexOf('=') + 1).trim();
      else if (/^http-referr?er=/i.test(op)) ref = op.slice(op.indexOf('=') + 1).trim();
    } else if (linea.startsWith('#')) { /* otras etiquetas */ }
    else {
      let url = linea;
      if (url.includes('|')) {
        const extra = url.slice(url.indexOf('|') + 1);
        url = url.slice(0, url.indexOf('|'));
        for (const p of extra.split('&')) {
          const k = p.split('=')[0].trim().toLowerCase();
          const v = p.includes('=') ? p.slice(p.indexOf('=') + 1).trim() : '';
          if (k === 'user-agent') ua = v;
          if (k === 'referer' || k === 'referrer') ref = v;
        }
      }
      const n = hayInfo && nombre ? nombre : (url.split('/').pop() || url);
      canales.push({ nombre: n, url, logo, grupo, ua, referer: ref });
      nombre = ''; logo = ''; grupo = ''; ua = ''; ref = ''; hayInfo = false;
    }
  }
  return canales;
}

let canales = [];
const grupoDe = c => c.grupo || 'Otros';

function ponerLista(texto, nombre, fuente) {
  const nuevos = leerM3u(texto);
  if (!nuevos.length) { toast('Esa lista no tiene canales'); return false; }
  D.listaM3u = texto; D.nombreLista = nombre; D.fuente = fuente;
  D.caidos = []; D.fechaRevision = '';
  if (/^https?:/i.test(fuente)) {
    D.recientes = [{ nombre, fuente }, ...(D.recientes || []).filter(r => r.fuente !== fuente)].slice(0, 10);
  }
  canales = nuevos;
  guardar(true);
  pintarGrupos(); pintarCanales();
  toast(`✔ ${nuevos.length} canales cargados. Revisando cuáles funcionan…`);
  revisarCanales(true);
  return true;
}

async function descargarLista(url, nombre) {
  aviso(`Descargando “${nombre}”…`);
  try {
    const t = await window.pc.descargar(url);
    aviso('');
    ponerLista(t, nombre, url);
  } catch (e) { aviso(''); toast('No se pudo descargar la lista: ' + (e.message || e)); }
}

// ===================================================================== pantalla de canales
function visibles() {
  const caidos = new Set(D.caidos);
  return D.ocultarCaidos ? canales.filter(c => !caidos.has(c.url)) : canales;
}
function pintarGrupos() {
  const sel = $('grupo');
  const antes = sel.value;
  const grupos = [...new Set(visibles().map(grupoDe))].sort((a, b) => a.localeCompare(b, 'es'));
  sel.innerHTML = `<option value="*">📺 Todos los canales</option><option value="★">★ Favoritos</option>` +
    grupos.map(g => `<option value="g:${esc(g)}">${esc(g)}</option>`).join('');
  sel.value = [...sel.options].some(o => o.value === antes) ? antes : '*';
  const n = canales.length, ocultos = canales.length - visibles().length;
  $('infoLista').textContent = n ? `${D.nombreLista || 'Lista'} · ${n} canales` + (ocultos ? ` (${ocultos} no funcionan, ocultos)` : '') +
    (D.fechaRevision ? ` · revisada ${D.fechaRevision}` : '') : 'Sin lista: usa 📋 Listas';
}
function filtrados() {
  const g = $('grupo').value, q = $('buscar').value.trim().toLowerCase();
  const fav = new Set(D.favoritos);
  let l = visibles();
  if (g === '★') l = l.filter(c => fav.has(c.url));
  else if (g.startsWith('g:')) l = l.filter(c => grupoDe(c) === g.slice(2));
  if (q) l = l.filter(c => c.nombre.toLowerCase().includes(q) || c.grupo.toLowerCase().includes(q));
  return l;
}
function filaCanal(c, i) {
  const fav = D.favoritos.includes(c.url);
  const caido = D.caidos.includes(c.url);
  const logo = /^https?:/.test(c.logo) ? `<img loading="lazy" src="${esc(c.logo)}" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'sinLogo',textContent:'📺'}))">` : '<div class="sinLogo">📺</div>';
  return `<div class="canal${actual && actual.url === c.url ? ' actual' : ''}${caido ? ' caido' : ''}" data-i="${i}">${logo}
    <div class="txt"><div class="nom">${esc(c.nombre)}</div><div class="gru">${esc(grupoDe(c))}${caido ? ' · no responde' : ''}</div></div>
    <button class="estrella" data-f="${i}" title="Favorito">${fav ? '★' : '☆'}</button></div>`;
}
let listaMostrada = [];
function pintarCanales() {
  listaMostrada = filtrados();
  const html = listaMostrada.length ? listaMostrada.map(filaCanal).join('')
    : `<div class="vacio">${canales.length ? 'No hay canales aquí' : 'Elige una lista en<br><b>📋 Listas</b>'}</div>`;
  $('listaCanales').innerHTML = html;
  $('panelLista').innerHTML = html;
  marcarActual();
}
function marcarActual() {
  for (const cont of [$('listaCanales'), $('panelLista')]) {
    cont.querySelectorAll('.canal').forEach(el => el.classList.toggle('actual', !!actual && listaMostrada[+el.dataset.i]?.url === actual.url));
  }
  const el = $('listaCanales').querySelector('.canal.actual');
  if (el) el.scrollIntoView({ block: 'nearest' });
  $('cFav').textContent = actual && D.favoritos.includes(actual.url) ? '★' : '☆';
}
function clicLista(e) {
  const f = e.target.closest('[data-f]');
  if (f) { e.stopPropagation(); cambiarFavorito(listaMostrada[+f.dataset.f]); return; }
  const fila = e.target.closest('.canal');
  if (fila) verCanal(listaMostrada, +fila.dataset.i);
}
function cambiarFavorito(c) {
  if (!c) return;
  const s = new Set(D.favoritos);
  s.has(c.url) ? s.delete(c.url) : s.add(c.url);
  D.favoritos = [...s];
  guardar();
  pintarCanales();
}

// ===================================================================== reproductor
const video = $('video');
let hls = null, ts = null;
let modo = 'canales';          // 'canales' | 'peli'
let actual = null;             // canal actual
let colaCanales = [], idxCanal = 0, erroresSeguidos = 0;
let intentos = [], tAvisoTitulo = null;

function detener() {
  if (hls) { try { hls.destroy(); } catch (e) {} hls = null; }
  if (ts) { try { ts.destroy(); } catch (e) {} ts = null; }
  video.onerror = null;
  video.removeAttribute('src');
  try { video.load(); } catch (e) {}
}
function mostrarTitulo(t) {
  const el = $('tituloVideo');
  el.textContent = t; el.style.opacity = 1;
  clearTimeout(tAvisoTitulo);
  tAvisoTitulo = setTimeout(() => (el.style.opacity = 0), 5000);
}
function aviso(t) {
  const el = $('avisoVideo');
  el.textContent = t;
  el.classList.toggle('oculta', !t);
}

function verCanal(lista, i) {
  if (!lista.length) return;
  if (modo === 'peli') cerrarPeli(true);
  colaCanales = lista; idxCanal = (i + lista.length) % lista.length;
  actual = lista[idxCanal];
  D.ultimo = actual.url; guardar();
  window.pc.cabeceras({ ua: actual.ua, referer: actual.referer });
  const u = actual.url.toLowerCase();
  // orden de intentos según la dirección
  if (/\.(mp4|webm|mkv|mov)(\?|$)/.test(u)) intentos = ['directo', 'hls'];
  else if (/\.ts(\?|$)/.test(u) || /\/(live|play)\/[^.]*$/.test(u) && !u.includes('m3u8')) intentos = ['ts', 'hls', 'directo'];
  else intentos = ['hls', 'ts', 'directo'];
  mostrarTitulo(actual.nombre);
  marcarActual();
  probarSiguienteForma();
}

function probarSiguienteForma() {
  detener();
  const forma = intentos.shift();
  if (!forma) { canalFallo(); return; }
  aviso('Cargando…');
  const url = actual.url;
  if (forma === 'hls' && window.Hls && Hls.isSupported()) {
    let recuperado = false;
    hls = new Hls({ enableWorker: true, lowLatencyMode: false, manifestLoadingMaxRetry: 2, levelLoadingMaxRetry: 2, fragLoadingMaxRetry: 3 });
    hls.on(Hls.Events.MANIFEST_PARSED, () => { video.play().catch(() => {}); });
    hls.on(Hls.Events.ERROR, (ev, d) => {
      if (!d.fatal) return;
      if (d.type === Hls.ErrorTypes.MEDIA_ERROR && !recuperado) { recuperado = true; hls.recoverMediaError(); return; }
      probarSiguienteForma();
    });
    hls.loadSource(url);
    hls.attachMedia(video);
  } else if (forma === 'ts' && window.mpegts && mpegts.isSupported()) {
    ts = mpegts.createPlayer({ type: 'mpegts', isLive: true, url }, { enableStashBuffer: true, liveBufferLatencyChasing: false });
    ts.on(mpegts.Events.ERROR, () => probarSiguienteForma());
    ts.attachMediaElement(video);
    ts.load();
    const p = ts.play(); if (p && p.catch) p.catch(() => {});
  } else if (forma === 'directo') {
    video.onerror = () => probarSiguienteForma();
    video.src = url;
    video.play().catch(() => {});
  } else probarSiguienteForma();
}

function canalFallo() {
  erroresSeguidos++;
  if (colaCanales.length > 1 && erroresSeguidos <= 10) {
    aviso(`“${actual.nombre}” no responde. Pasando al siguiente…`);
    setTimeout(() => { if (modo === 'canales') verCanal(colaCanales, idxCanal + 1); }, 1500);
  } else aviso(`“${actual.nombre}” no responde.`);
}

video.addEventListener('playing', () => { aviso(''); if (modo === 'canales') erroresSeguidos = 0; });
video.addEventListener('waiting', () => aviso('Cargando…'));

function anterior() { if (modo === 'peli') { video.currentTime = Math.max(0, video.currentTime - 10); return; } erroresSeguidos = 0; verCanal(colaCanales, idxCanal - 1); }
function siguiente() { if (modo === 'peli') { video.currentTime = video.currentTime + 30; return; } erroresSeguidos = 0; verCanal(colaCanales, idxCanal + 1); }
function pausa() { if (video.paused) video.play().catch(() => {}); else video.pause(); }
function pantallaCompleta() {
  if (document.fullscreenElement) document.exitFullscreen(); else $('reproductor').requestFullscreen().catch(() => {});
}

// ===================================================================== revisar canales
let revisando = false;
async function revisarCanales(automatico) {
  if (revisando || !canales.length) return;
  revisando = true;
  const urls = [...new Map(canales.map(c => [c.url, c])).values()];
  const caidos = new Set();
  let hechos = 0, cancelar = false;
  if (!automatico) {
    modal(`<h3>🩺 Revisando canales…</h3><div id="txtRev">0 de ${urls.length}</div><div class="progreso"><div id="barRev"></div></div>
      <div class="botones"><button id="cancRev">Cancelar</button></div>`);
    $('cancRev').onclick = () => { cancelar = true; };
  }
  const trabajar = async () => {
    while (!cancelar && urls.length) {
      const c = urls.shift();
      const ok = await window.pc.probar(c);
      if (!ok) caidos.add(c.url);
      hechos++;
      if (!automatico && $('txtRev')) {
        const total = hechos + urls.length;
        $('txtRev').textContent = `${hechos} de ${total} · ${caidos.size} no responden`;
        $('barRev').style.width = (100 * hechos / total) + '%';
      }
    }
  };
  await Promise.all(Array.from({ length: 10 }, trabajar));
  revisando = false;
  if (!automatico) cerrarModal();
  if (cancelar) return;
  D.caidos = [...caidos]; D.fechaRevision = fechaTexto(new Date());
  guardar();
  pintarGrupos(); pintarCanales();
  toast(`✔ Revisión lista: ${hechos - caidos.size} funcionan, ${caidos.size} no responden`);
}

// ===================================================================== Mis Películas
let pelis = [];
let peliCola = [], peliIdx = 0, tAvance = null;
const clavePeli = p => `${p.archivo}|${p.tamano}`;

const basura = /\b(480p|720p|1080p|2160p|4k|uhd|hdr|x264|x265|h264|h265|hevc|xvid|web-?dl|webrip|web|bluray|blu-ray|brrip|bdrip|hdrip|dvdrip|hdtv|cam|ts|aac|ac3|dts|5\.1|dual|multi|latino|castellano|español|subtitulado|sub|yts|rarbg)\b.*/i;
function limpiarTitulo(archivo) {
  const sinExt = archivo.replace(/\.[^.]+$/, '');
  let t = sinExt.replace(/[\[{].*?[\]}]/g, ' ').replace(/[._]/g, ' ').replace(basura, '');
  const m = t.match(/\(?\b(19[3-9]\d|20[0-4]\d)\b\)?/);
  if (m && m.index > 0) t = t.slice(0, m.index).trim() + ` (${m[1]})`;
  t = t.replace(/\s+/g, ' ').trim().replace(/^[-\s]+|[-\s]+$/g, '');
  return t || sinExt;
}
function progreso(p) { return D.progresoPeliculas[clavePeli(p)]; }
function avance(p) {
  const o = progreso(p); if (!o) return 0;
  if (o.vista) return 1;
  return o.dur > 0 ? Math.min(1, Math.max(0, o.pos / o.dur)) : 0;
}
function durTexto(ms) { const m = Math.round(ms / 60000); return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m} min`; }

async function cargarPelis() {
  const carpetas = D.pc.carpetas || [];
  $('carpetas').innerHTML = carpetas.map((c, i) => `<span class="chip">📁 ${esc(c)} <button data-q="${i}" title="Quitar">✕</button></span>`).join('');
  pelis = carpetas.length ? (await window.pc.escanear(carpetas)).map(p => ({ ...p, titulo: limpiarTitulo(p.archivo) })) : [];
  pintarPelis();
}
function tarjeta(p) {
  const o = progreso(p), a = avance(p);
  const det = [o?.dur ? durTexto(o.dur) : '', a >= 1 ? '✓ Vista' : a > 0 ? `Vas en ${Math.round(a * 100)} %` : ''].filter(Boolean).join(' · ');
  return `<div class="tarjeta" data-k="${esc(clavePeli(p))}"><div class="portada">🎬</div>
    <div class="barraAvance"><div style="width:${Math.round(a * 100)}%"></div></div>
    <div class="tt">${esc(p.titulo)}</div><div class="dd">${esc(det || p.carpeta)}</div></div>`;
}
function pintarPelis() {
  const q = $('buscarPeli').value.trim().toLowerCase();
  const cont = $('filasPelis');
  if (!(D.pc.carpetas || []).length) {
    cont.innerHTML = `<div class="vacio">Toca <b>📁 Agregar carpeta de películas</b> y elige la carpeta donde guardas tus videos.<br>Si ya viste una película en el celular, sigue desde el mismo minuto.</div>`;
    return;
  }
  if (!pelis.length) { cont.innerHTML = '<div class="vacio">No se encontraron videos en esas carpetas.</div>'; return; }
  const l = q ? pelis.filter(p => p.titulo.toLowerCase().includes(q) || p.carpeta.toLowerCase().includes(q)) : pelis;
  const filas = [];
  const fila = (titulo, lista) => { if (lista.length) filas.push(`<div class="fila"><h2>${esc(titulo)}</h2><div class="tarjetas">${lista.map(tarjeta).join('')}</div></div>`); };
  if (q) fila(`Resultados (${l.length})`, l);
  else {
    fila('▶ Seguir viendo', l.filter(p => { const a = avance(p); return a > 0 && a < 1; }).sort((a, b) => (progreso(b)?.t || 0) - (progreso(a)?.t || 0)));
    fila('🆕 Agregadas recientemente', [...l].sort((a, b) => b.fecha - a.fecha).slice(0, 30));
    const porCarpeta = {};
    for (const p of l) (porCarpeta[p.carpeta] ||= []).push(p);
    Object.keys(porCarpeta).sort((a, b) => a.localeCompare(b, 'es')).forEach(c =>
      fila('📁 ' + c, porCarpeta[c].sort((a, b) => a.archivo.localeCompare(b.archivo, 'es', { numeric: true }))));
    fila('✓ Ya vistas', l.filter(p => avance(p) >= 1));
    fila('🔤 Todas (A-Z)', [...l].sort((a, b) => a.titulo.localeCompare(b.titulo, 'es')));
  }
  cont.innerHTML = filas.join('') || '<div class="vacio">No se encontró</div>';
}
function guardarAvance() {
  if (modo !== 'peli') return;
  const p = peliCola[peliIdx];
  if (!p || !isFinite(video.duration) || video.currentTime < 3) return;
  const pos = Math.round(video.currentTime * 1000), dur = Math.round(video.duration * 1000);
  const terminada = dur > 0 && pos >= dur * 0.95;
  D.progresoPeliculas[clavePeli(p)] = { pos: terminada ? 0 : pos, dur, vista: terminada, t: Date.now(), titulo: p.titulo };
  guardar();
}
function verPeli(cola, i) {
  if (modo === 'canales') { detener(); actual = null; marcarActual(); }
  modo = 'peli';
  peliCola = cola; peliIdx = i;
  const p = cola[i];
  $('vistaPelis').classList.add('conVideo');
  $('vistaPelis').appendChild($('reproductor'));
  ponerControles();
  detener();
  window.pc.cabeceras({});
  const o = progreso(p);
  video.src = window.pc.urlArchivo(p.ruta);
  video.onloadedmetadata = () => { if (o && !o.vista && o.pos > 5000) video.currentTime = o.pos / 1000; };
  video.onerror = () => aviso('Windows no puede reproducir este video (formato no compatible).');
  video.play().catch(() => {});
  mostrarTitulo(p.titulo);
  clearInterval(tAvance);
  tAvance = setInterval(() => { if (!video.paused) guardarAvance(); }, 10000);
}
function cerrarPeli(sinVolver) {
  guardarAvance(); guardar(true);
  clearInterval(tAvance);
  detener();
  modo = 'canales';
  $('vistaPelis').classList.remove('conVideo');
  $('zonaVideo').appendChild($('reproductor'));
  ponerControles();
  if (!sinVolver) pintarPelis();
}
video.addEventListener('pause', guardarAvance);
video.addEventListener('ended', () => {
  if (modo !== 'peli') return;
  const p = peliCola[peliIdx];
  D.progresoPeliculas[clavePeli(p)] = { pos: 0, dur: Math.round((video.duration || 0) * 1000), vista: true, t: Date.now(), titulo: p.titulo };
  guardar();
  if (peliIdx + 1 < peliCola.length) verPeli(peliCola, peliIdx + 1);   // siguiente capítulo
  else cerrarPeli();
});
function ponerControles() {
  const peli = modo === 'peli';
  $('cAnterior').textContent = peli ? '⏪ 10 s' : '⏮';
  $('cSiguiente').textContent = peli ? '30 s ⏩' : '⏭';
  $('cPanel').classList.toggle('oculta', peli);
  $('cFav').classList.toggle('oculta', peli);
  $('cCerrarPeli').classList.toggle('oculta', !peli);
  $('panelCanales').classList.add('oculta');
}

// ===================================================================== menús y ventanas
function menu(boton, opciones) {
  const m = $('menu');
  m.innerHTML = opciones.map((o, i) => o === '-' ? '<hr>' : `<button data-o="${i}">${esc(o[0])}</button>`).join('');
  const r = boton.getBoundingClientRect();
  m.style.top = (r.bottom + 6) + 'px';
  m.style.left = Math.min(r.left, window.innerWidth - 360) + 'px';
  m.classList.remove('oculta');
  m.onclick = (e) => { const b = e.target.closest('[data-o]'); if (!b) return; m.classList.add('oculta'); opciones[+b.dataset.o][1](); };
  setTimeout(() => document.addEventListener('click', function fuera(e) {
    if (!m.contains(e.target)) { m.classList.add('oculta'); document.removeEventListener('click', fuera); }
  }), 0);
}
function modal(html) { $('cajaModal').innerHTML = html; $('modal').classList.remove('oculta'); }
function cerrarModal() { $('modal').classList.add('oculta'); }
let tToast = null;
function toast(t) { const el = $('toast'); el.textContent = t; el.classList.remove('oculta'); clearTimeout(tToast); tToast = setTimeout(() => el.classList.add('oculta'), 4500); }
function pedirTexto(titulo, ejemplo, alAceptar) {
  modal(`<h3>${esc(titulo)}</h3><input id="txtPedir" placeholder="${esc(ejemplo)}"><div class="botones"><button id="noPedir">Cancelar</button><button id="siPedir">Aceptar</button></div>`);
  const ok = () => { const v = $('txtPedir').value.trim(); cerrarModal(); if (v) alAceptar(v); };
  $('siPedir').onclick = ok; $('noPedir').onclick = cerrarModal;
  $('txtPedir').onkeydown = e => { if (e.key === 'Enter') ok(); if (e.key === 'Escape') cerrarModal(); };
  $('txtPedir').focus();
}

$('btnListas').onclick = (e) => {
  e.stopPropagation();
  const op = [
    ['🇪🇨 Canales de Ecuador (gratis)', () => descargarLista(LISTA_ECUADOR, 'Canales de Ecuador')],
    ['🌎 Canales en español (gratis)', () => descargarLista(LISTA_ESPANOL, 'Canales en español')],
    ...LISTAS_LATINAS.map(([n, u]) => [n, () => descargarLista(u, n.replace(/^\S+\s/, '').replace(/ \(.*$/, ''))]),
    '-',
    ['📂 Abrir archivo M3U…', async () => { const r = await window.pc.abrirM3u(); if (r) ponerLista(r.texto, r.nombre, r.ruta); }],
    ['🔗 Pegar enlace de una lista…', () => pedirTexto('Enlace de la lista M3U', 'https://…/lista.m3u', (u) => {
      const url = (u.match(/https?:\/\/\S+/) || [])[0];
      if (!url) { toast('El enlace debe empezar con http:// o https://'); return; }
      descargarLista(url, url.split('/').pop() || url);
    })]
  ];
  if (D.fuente && /^https?:/.test(D.fuente)) op.push(['🔄 Actualizar la lista actual', () => descargarLista(D.fuente, D.nombreLista)]);
  if ((D.recientes || []).length) { op.push('-'); D.recientes.forEach(r => op.push(['🕘 ' + r.nombre, () => descargarLista(r.fuente, r.nombre)])); }
  menu(e.currentTarget, op);
};
$('btnRevisar').onclick = (e) => {
  e.stopPropagation();
  menu(e.currentTarget, [
    ['🩺 Revisar qué canales funcionan', () => revisarCanales(false)],
    [D.ocultarCaidos ? '👁 Mostrar también los que no funcionan' : '🙈 Ocultar los que no funcionan', () => {
      D.ocultarCaidos = !D.ocultarCaidos; guardar(); pintarGrupos(); pintarCanales();
    }]
  ]);
};
$('btnRespaldo').onclick = (e) => {
  e.stopPropagation();
  menu(e.currentTarget, [
    ['💾 Guardar respaldo como…', async () => { guardar(true); const r = await window.pc.exportar(JSON.stringify(D, null, 2)); if (r) toast('✔ Respaldo guardado'); }],
    ['📥 Recuperar respaldo (del PC o del celular)…', async () => {
      const t = await window.pc.importar(); if (!t) return;
      try {
        unirDatos(JSON.parse(t), false);
        canales = leerM3u(D.listaM3u);
        guardar(true); pintarGrupos(); pintarCanales(); cargarPelis();
        toast(`✔ Respaldo recuperado: ${canales.length} canales`);
      } catch (err) { toast(err.message || 'No se pudo leer el respaldo'); }
    }],
    ['📁 Abrir la carpeta del respaldo automático', () => window.pc.carpetaRespaldo()]
  ]);
};

// pestañas
function verPestana(cual) {
  $('tabCanales').classList.toggle('activa', cual === 'canales');
  $('tabPelis').classList.toggle('activa', cual === 'pelis');
  $('vistaCanales').classList.toggle('oculta', cual !== 'canales');
  $('vistaPelis').classList.toggle('oculta', cual !== 'pelis');
  $('btnListas').classList.toggle('oculta', cual !== 'canales');
  $('btnRevisar').classList.toggle('oculta', cual !== 'canales');
}
$('tabCanales').onclick = () => {
  if (modo === 'peli') cerrarPeli();
  verPestana('canales');
  if (actual && video.paused && !video.currentSrc && !hls && !ts) verCanal(colaCanales, idxCanal);
};
$('tabPelis').onclick = () => { verPestana('pelis'); if (modo === 'canales') { detener(); aviso(''); } pintarPelis(); };

$('listaCanales').onclick = clicLista;
$('panelLista').onclick = clicLista;
$('buscar').oninput = pintarCanales;
$('grupo').onchange = pintarCanales;
$('cAnterior').onclick = anterior;
$('cSiguiente').onclick = siguiente;
$('cPausa').onclick = pausa;
$('cGrande').onclick = pantallaCompleta;
$('cFav').onclick = () => cambiarFavorito(actual);
$('cPanel').onclick = () => { $('panelCanales').classList.toggle('oculta'); marcarActual(); };
$('cerrarPanel').onclick = () => $('panelCanales').classList.add('oculta');
$('cCerrarPeli').onclick = () => { if (document.fullscreenElement) document.exitFullscreen(); cerrarPeli(); };
video.ondblclick = pantallaCompleta;

$('btnCarpeta').onclick = async () => {
  const c = await window.pc.elegirCarpeta(); if (!c) return;
  D.pc.carpetas = [...new Set([...(D.pc.carpetas || []), c])];
  guardar(); cargarPelis();
};
$('btnActualizarPelis').onclick = cargarPelis;
$('carpetas').onclick = (e) => {
  const b = e.target.closest('[data-q]'); if (!b) return;
  D.pc.carpetas.splice(+b.dataset.q, 1); guardar(); cargarPelis();
};
$('buscarPeli').oninput = pintarPelis;
$('filasPelis').onclick = (e) => {
  const t = e.target.closest('.tarjeta'); if (!t) return;
  const p = pelis.find(x => clavePeli(x) === t.dataset.k); if (!p) return;
  // se reproduce con las demás de su carpeta, en orden (series)
  const cola = pelis.filter(x => x.carpeta === p.carpeta).sort((a, b) => a.archivo.localeCompare(b.archivo, 'es', { numeric: true }));
  verPeli(cola, cola.indexOf(p));
};

// teclado
document.addEventListener('keydown', (e) => {
  const escribiendo = ['INPUT', 'SELECT', 'TEXTAREA'].includes(document.activeElement?.tagName);
  if (e.ctrlKey && e.key.toLowerCase() === 'f') { e.preventDefault(); (modo === 'peli' || !$('vistaPelis').classList.contains('oculta') ? $('buscarPeli') : $('buscar')).focus(); return; }
  if (escribiendo) { if (e.key === 'Escape') document.activeElement.blur(); return; }
  if (e.key === 'ArrowUp' || e.key === 'PageUp') { e.preventDefault(); anterior(); }
  else if (e.key === 'ArrowDown' || e.key === 'PageDown') { e.preventDefault(); siguiente(); }
  else if (e.key === ' ') { e.preventDefault(); pausa(); }
  else if (e.key.toLowerCase() === 'f') pantallaCompleta();
  else if (e.key.toLowerCase() === 'c' && modo === 'canales') $('cPanel').click();
});
window.addEventListener('beforeunload', () => { guardarAvance(); guardar(true); });

// ===================================================================== inicio
(async function inicio() {
  let texto = await window.pc.leerRespaldo();
  if (!texto) { try { texto = localStorage.getItem('tvjat'); } catch (e) {} }
  if (texto) { try { unirDatos(JSON.parse(texto), true); } catch (e) {} }
  canales = leerM3u(D.listaM3u);
  $('zonaVideo').appendChild($('reproductor'));
  ponerControles();
  pintarGrupos(); pintarCanales();
  cargarPelis();
  if (canales.length) {
    const l = visibles();
    const i = Math.max(0, l.findIndex(c => c.url === D.ultimo));
    if (l.length) verCanal(l, i);
  } else aviso('Elige una lista de canales en 📋 Listas');
})();
