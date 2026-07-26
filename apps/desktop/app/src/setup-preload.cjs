'use strict'

const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('testhubDesktop', Object.freeze({
  loadPreferences: () => ipcRenderer.invoke('testhub-desktop:preferences:load'),
  saveServerUrl: (serverUrl) => ipcRenderer.invoke('testhub-desktop:preferences:save', serverUrl),
}))
