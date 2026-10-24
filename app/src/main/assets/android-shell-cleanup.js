// The bootstrap must unregister obsolete workers before navigating, but their
// caches can be removed after the first overview. User-video caches use a
// different prefix and must never be included here.
export async function retireAndroidShellCaches(cacheStorage = globalThis.caches) {
  if (!cacheStorage) return;
  const prefix = 'salsa-trainer:/assets/:';
  await Promise.all((await cacheStorage.keys()).filter(key => key.startsWith(prefix)).map(key => cacheStorage.delete(key)));
}
