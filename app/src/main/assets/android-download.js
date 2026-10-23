// Android WebView bridge: stream downloads in bounded chunks; do not allocate a whole ZIP as base64.
if (window.AndroidDownloads) {
  document.addEventListener('click', async event => {
    const a = event.target.closest?.('a[download]');
    if (!a || !a.href.startsWith('blob:')) return;
    event.preventDefault();
    try {
      const blob = await (await fetch(a.href)).blob();
      const filename = a.download || 'export.zip';
      if (!AndroidDownloads.start(filename)) throw new Error('Export konnte nicht gestartet werden.');
      const reader = blob.stream().getReader();
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        for (let i = 0; i < value.length; i += 65536) {
          const bytes = value.subarray(i, i + 65536);
          let binary = '';
          for (let j = 0; j < bytes.length; j++) binary += String.fromCharCode(bytes[j]);
          if (!AndroidDownloads.chunk(btoa(binary))) throw new Error('Nicht genügend Speicher für den Export.');
        }
      }
      if (!AndroidDownloads.finish(blob.type || 'application/octet-stream')) throw new Error('Export konnte nicht abgeschlossen werden.');
    } catch (error) {
      AndroidDownloads.abort();
      alert(error.message || 'Export fehlgeschlagen.');
    }
  }, true);
}
// The ZIP button calls this directly so errors and progress reach its dialog.
if (window.AndroidDownloads) {
  navigator.canShare = data => Array.isArray(data?.files) && data.files.length === 1;
  window.stepFileDelivery = { async save(file, progress = () => {}) {
    if (!AndroidDownloads.start(file.name || 'export.zip')) throw new Error('Ein Export läuft bereits.');
    try {
      await new Promise(resolve => requestAnimationFrame(() => setTimeout(resolve, 0)));
      for (let offset = 0; offset < file.size; offset += 49152) {
        const bytes = new Uint8Array(await file.slice(offset, offset + 49152).arrayBuffer());
        let binary = ''; for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
        if (!AndroidDownloads.chunk(btoa(binary))) throw new Error('Nicht genügend Speicher für den Export.');
        progress(`ZIP wird übergeben: ${Math.min(100, Math.round((offset + bytes.length) / file.size * 100))} % …`);
      }
      if (!AndroidDownloads.finish(file.type || 'application/octet-stream')) throw new Error('Export konnte nicht abgeschlossen werden.');
      return 'native';
    } catch (error) { AndroidDownloads.abort(); throw error; }
  }};
  navigator.share = data => {
    if (!navigator.canShare(data)) return Promise.reject(new Error('Bitte eine Datei zum Teilen wählen.'));
    return window.stepFileDelivery.save(data.files[0]);
  };
}
