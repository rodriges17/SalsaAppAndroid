self.addEventListener('install', event => event.waitUntil(self.skipWaiting()));
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));
self.addEventListener('fetch', event => {
  if (new URL(event.request.url).pathname.startsWith('/assets/')) {
    event.respondWith(new Response('OLD ANDROID SHELL', { headers: { 'Content-Type': 'text/html' } }));
  }
});
