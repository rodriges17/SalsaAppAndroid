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
// Native file sharing makes the PWA's ZIP and multi-part backup share actions available in WebView.
if (window.AndroidDownloads) {
  navigator.canShare = data => Array.isArray(data?.files) && data.files.length === 1;
  navigator.share = async data => {
    if (!navigator.canShare(data)) throw new Error('Bitte eine Datei zum Teilen wählen.');
    const file = data.files[0];
    if (!AndroidDownloads.start(file.name || 'export.zip')) throw new Error('Ein Export läuft bereits.');
    try {
      for (let p=0;p<file.size;p+=49152) {
        const bytes = new Uint8Array(await file.slice(p,p+49152).arrayBuffer()); let binary='';
        for(const b of bytes)binary+=String.fromCharCode(b);
        if(!AndroidDownloads.chunk(btoa(binary)))throw new Error('Export fehlgeschlagen.');
      }
      if(!AndroidDownloads.finish(file.type||'application/octet-stream'))throw new Error('Teilen fehlgeschlagen.');
    } catch(e) {AndroidDownloads.abort();throw e;}
  };
}
