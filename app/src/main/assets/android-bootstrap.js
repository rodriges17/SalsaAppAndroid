// This page is deliberately outside /assets/, the scope of older Android service workers.
// Retire only the obsolete app shell. IndexedDB, user-video caches and document grants stay intact.
(async () => {
  const scope = new URL('/assets/', location.origin).href;
  if ('serviceWorker' in navigator) {
    const registrations = await navigator.serviceWorker.getRegistrations();
    await Promise.all(registrations.filter(r => r.scope === scope).map(r => r.unregister()));
  }
  if ('caches' in globalThis) {
    const prefix = 'salsa-trainer:/assets/:';
    await Promise.all((await caches.keys()).filter(key => key.startsWith(prefix)).map(key => caches.delete(key)));
  }
  location.replace('/assets/index.html' + location.search);
})().catch(() => {
  document.body.textContent = 'Die App konnte ihre alte Offline-Oberfläche nicht aktualisieren. Bitte die App vollständig schließen und erneut öffnen. Deine gespeicherten Figuren bleiben erhalten.';
});
