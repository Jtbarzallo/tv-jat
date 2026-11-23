// TV JAT para PC (Windows) — proceso principal
const { app, BrowserWindow, ipcMain, dialog, session, shell, Menu } = require('electron');
const path = require('path');
const fs = require('fs');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36';
// Respaldo (regla JAT): en Documentos\TVJAT — queda aunque se desinstale la app
const CARPETA = path.join(app.getPath('documents'), 'TVJAT');
const RESPALDO = path.join(CARPETA, 'respaldo_tv_jat.json');
const VIDEOS = ['.mp4', '.mkv', '.webm', '.m4v', '.mov', '.avi', '.ts', '.wmv', '.mpg', '.mpeg', '.3gp'];

// cabeceras especiales del canal que se está viendo (User-Agent / Referer de la lista)
let cabeceras = { ua: '', referer: '' };

function crearVentana() {
  Menu.setApplicationMenu(null);
  const win = new BrowserWindow({
    width: 1300, height: 820, minWidth: 900, minHeight: 600,
    backgroundColor: '#0b0f1a', title: 'TV JAT', icon: path.join(__dirname, 'icono.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      sandbox: false,
      webSecurity: false,   // los canales de TV no traen permisos para navegadores
      autoplayPolicy: 'no-user-gesture-required'
    }
  });
  win.loadFile(path.join(__dirname, 'index.html'));
  win.webContents.on('before-input-event', (e, input) => {
    if (input.type === 'keyDown' && input.key === 'F12') win.webContents.toggleDevTools();
  });
}

app.whenReady().then(() => {
  const s = session.defaultSession;
  s.setUserAgent(UA);
  s.webRequest.onBeforeSendHeaders((d, cb) => {
    const h = d.requestHeaders;
    if (/^https?:/i.test(d.url)) {
      if (cabeceras.ua) h['User-Agent'] = cabeceras.ua;
      if (cabeceras.referer) h['Referer'] = cabeceras.referer;
    }
    cb({ requestHeaders: h });
  });
  crearVentana();
});
app.on('window-all-closed', () => app.quit());

// ---------------------------------------------------------------- respaldo
ipcMain.handle('respaldo-leer', () => {
  try { return fs.readFileSync(RESPALDO, 'utf8'); } catch (e) { return null; }
});
ipcMain.handle('respaldo-guardar', (e, texto) => {
  try {
    fs.mkdirSync(CARPETA, { recursive: true });
    const tmp = RESPALDO + '.tmp';
    fs.writeFileSync(tmp, texto, 'utf8');
    fs.renameSync(tmp, RESPALDO);
    return true;
  } catch (err) { return false; }
});
ipcMain.handle('respaldo-carpeta', () => {
  fs.mkdirSync(CARPETA, { recursive: true });
  shell.openPath(CARPETA);
  return CARPETA;
});
ipcMain.handle('respaldo-exportar', async (e, texto) => {
  const r = await dialog.showSaveDialog({ title: 'Guardar respaldo', defaultPath: path.join(app.getPath('downloads'), 'respaldo_tv_jat.json'), filters: [{ name: 'Respaldo', extensions: ['json'] }] });
  if (r.canceled || !r.filePath) return null;
  fs.writeFileSync(r.filePath, texto, 'utf8');
  return r.filePath;
});
ipcMain.handle('respaldo-importar', async () => {
  const r = await dialog.showOpenDialog({ title: 'Recuperar respaldo (del PC o del celular)', filters: [{ name: 'Respaldo', extensions: ['json'] }], properties: ['openFile'] });
  if (r.canceled || !r.filePaths[0]) return null;
  return fs.readFileSync(r.filePaths[0], 'utf8');
});

// ---------------------------------------------------------------- listas M3U
ipcMain.handle('abrir-m3u', async () => {
  const r = await dialog.showOpenDialog({ title: 'Abrir lista de canales', filters: [{ name: 'Listas', extensions: ['m3u', 'm3u8', 'txt'] }], properties: ['openFile'] });
  if (r.canceled || !r.filePaths[0]) return null;
  return { nombre: path.basename(r.filePaths[0]).replace(/\.[^.]+$/, ''), texto: fs.readFileSync(r.filePaths[0], 'utf8'), ruta: r.filePaths[0] };
});
ipcMain.handle('descargar', async (e, url) => {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), 30000);
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA }, redirect: 'follow', signal: ctl.signal });
    if (!r.ok) throw new Error('El servidor respondió ' + r.status);
    return await r.text();
  } finally { clearTimeout(t); }
});
ipcMain.handle('cabeceras', (e, c) => { cabeceras = { ua: c?.ua || '', referer: c?.referer || '' }; });

// ---------------------------------------------------------------- revisar canales
async function pedir(url, c, soloInicio) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), 9000);
  const h = { 'User-Agent': c.ua || UA };
  if (c.referer) h['Referer'] = c.referer;
  if (soloInicio) h['Range'] = 'bytes=0-2047';
  try {
    const r = await fetch(url, { headers: h, redirect: 'follow', signal: ctl.signal });
    const tipo = (r.headers.get('content-type') || '').toLowerCase();
    let texto = '';
    if (!soloInicio && !tipo.startsWith('video')) {
      const reader = r.body.getReader();
      let n = 0; const partes = [];
      while (n < 200000) { const { done, value } = await reader.read(); if (done) break; partes.push(Buffer.from(value)); n += value.length; }
      try { reader.cancel(); } catch (x) {}
      texto = Buffer.concat(partes).toString('utf8');
    } else { try { r.body?.cancel(); } catch (x) {} }
    return { codigo: r.status, tipo, texto, url: r.url || url };
  } finally { clearTimeout(t); }
}
async function revisarHls(texto, base, c, nivel) {
  const lineas = texto.split(/\r?\n/).map(l => l.trim());
  if (texto.includes('#EXT-X-STREAM-INF') && nivel < 3) {
    const i = lineas.findIndex(l => l.startsWith('#EXT-X-STREAM-INF'));
    const sub = lineas.slice(i + 1).find(l => l && !l.startsWith('#'));
    if (!sub) return false;
    const r = await pedir(new URL(sub, base).toString(), c);
    return r.codigo >= 200 && r.codigo < 300 && r.texto.includes('#EXTM3U') && revisarHls(r.texto, r.url, c, nivel + 1);
  }
  if (!texto.includes('#EXTINF')) return false;
  const parte = [...lineas].reverse().find(l => l && !l.startsWith('#'));
  if (!parte) return false;
  const r = await pedir(new URL(parte, base).toString(), c, true);
  return r.codigo >= 200 && r.codigo < 300;
}
ipcMain.handle('probar', async (e, c) => {
  try {
    const r = await pedir(c.url, c);
    if (r.codigo < 200 || r.codigo > 299) return false;
    if (r.texto.includes('#EXTM3U')) return await revisarHls(r.texto, r.url, c, 0);
    if (/<MPD/i.test(r.texto)) return true;
    return r.tipo.startsWith('video') || r.tipo.includes('octet') || r.tipo.includes('mp2t');
  } catch (err) { return false; }
});

// ---------------------------------------------------------------- Mis Películas
ipcMain.handle('elegir-carpeta', async () => {
  const r = await dialog.showOpenDialog({ title: 'Elegir carpeta de películas', properties: ['openDirectory'] });
  if (r.canceled || !r.filePaths[0]) return null;
  return r.filePaths[0];
});
ipcMain.handle('escanear', (e, carpetas) => {
  const lista = [];
  const ver = (dir, prof) => {
    if (prof > 8) return;
    let items = [];
    try { items = fs.readdirSync(dir, { withFileTypes: true }); } catch (x) { return; }
    for (const it of items) {
      const ruta = path.join(dir, it.name);
      if (it.isDirectory()) { if (!it.name.startsWith('.') && !it.name.startsWith('$')) ver(ruta, prof + 1); continue; }
      if (!VIDEOS.includes(path.extname(it.name).toLowerCase())) continue;
      try {
        const st = fs.statSync(ruta);
        if (st.size < 5 * 1024 * 1024) continue;   // se saltan clips pequeños
        lista.push({ ruta, archivo: it.name, carpeta: path.basename(dir), tamano: st.size, fecha: st.mtimeMs });
      } catch (x) {}
    }
  };
  for (const c of carpetas || []) ver(c, 0);
  return lista;
});
