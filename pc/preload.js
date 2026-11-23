// Puente seguro entre la pantalla y Windows (archivos, respaldo, descargas)
const { contextBridge, ipcRenderer } = require('electron');
const { pathToFileURL } = require('url');

contextBridge.exposeInMainWorld('pc', {
  leerRespaldo: () => ipcRenderer.invoke('respaldo-leer'),
  guardarRespaldo: (texto) => ipcRenderer.invoke('respaldo-guardar', texto),
  carpetaRespaldo: () => ipcRenderer.invoke('respaldo-carpeta'),
  exportar: (texto) => ipcRenderer.invoke('respaldo-exportar', texto),
  importar: () => ipcRenderer.invoke('respaldo-importar'),
  abrirM3u: () => ipcRenderer.invoke('abrir-m3u'),
  descargar: (url) => ipcRenderer.invoke('descargar', url),
  cabeceras: (c) => ipcRenderer.invoke('cabeceras', c),
  probar: (c) => ipcRenderer.invoke('probar', c),
  elegirCarpeta: () => ipcRenderer.invoke('elegir-carpeta'),
  escanear: (carpetas) => ipcRenderer.invoke('escanear', carpetas),
  urlArchivo: (ruta) => pathToFileURL(ruta).href
});
