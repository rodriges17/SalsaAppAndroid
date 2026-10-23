// One adapter for Figur-ZIP, full backup and legacy native views.
// Keep allocations bounded even for multi-part backups.
if (window.AndroidDownloads) {
  navigator.canShare = data => Array.isArray(data?.files) && data.files.length === 1;
  window.stepFileDelivery = { async save(file, progress = () => {}, signal) {
    signal?.throwIfAborted();
    if (!AndroidDownloads.start(file.name || 'export.zip')) throw new Error('Ein Export läuft bereits.');
    try {
      await new Promise(resolve => requestAnimationFrame(() => setTimeout(resolve, 0)));
      for (let offset = 0; offset < file.size; offset += 49152) {
        signal?.throwIfAborted();
        const bytes = new Uint8Array(await file.slice(offset, offset + 49152).arrayBuffer());
        signal?.throwIfAborted();
        let binary = ''; for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
        if (!AndroidDownloads.chunk(btoa(binary))) throw new Error('Nicht genügend Speicher für den Export.');
        progress(`Datei wird übergeben: ${Math.min(100, Math.round((offset + bytes.length) / file.size * 100))} % …`);
      }
      signal?.throwIfAborted();
      if (!AndroidDownloads.finish(file.type || 'application/octet-stream')) throw new Error('Export konnte nicht abgeschlossen werden.');
      return 'native';
    } catch (error) { AndroidDownloads.abort(); throw error; }
  }};
  navigator.share = data => {
    if (!navigator.canShare(data)) return Promise.reject(new Error('Bitte eine Datei zum Teilen wählen.'));
    return window.stepFileDelivery.save(data.files[0]);
  };
}
