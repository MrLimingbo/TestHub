'use strict'

const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('testhubDesktop', Object.freeze({
  runtime: 'desktop',
  executeHttpRequest: (value) => ipcRenderer.invoke('testhub-desktop:api-debug:execute', value),
}))
