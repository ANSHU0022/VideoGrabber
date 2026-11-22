const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('vg', {
  platform: process.platform,
  probe: (url, key) => ipcRenderer.invoke('probe', url, key),
  download: (opts) => ipcRenderer.invoke('download', opts),
  cancel: (id) => ipcRenderer.invoke('cancel', id),
  reveal: (file) => ipcRenderer.invoke('reveal', file),
  openFile: (file) => ipcRenderer.invoke('open-file', file),
  openFolder: () => ipcRenderer.invoke('open-folder'),
  chooseFolder: () => ipcRenderer.invoke('choose-folder'),
  getSettings: () => ipcRenderer.invoke('get-settings'),
  setSettings: (p) => ipcRenderer.invoke('set-settings', p),
  updateYtdlp: () => ipcRenderer.invoke('update-ytdlp'),
  historyList: () => ipcRenderer.invoke('history-list'),
  historyRemove: (ids, deleteFiles) => ipcRenderer.invoke('history-remove', ids, deleteFiles),
  historyClear: () => ipcRenderer.invoke('history-clear'),
  fileUrl: (f) => ipcRenderer.invoke('file-url', f),
  on: (channel, cb) => {
    if (!['job-update', 'media-detected', 'open-url', 'download-link', 'command', 'history-updated'].includes(channel)) return;
    ipcRenderer.on(channel, (_e, data) => cb(data));
  },
});
