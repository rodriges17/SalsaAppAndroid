// Show persisted summaries before slow device-folder checks. Each explicit
// refresh supersedes its predecessor. Lifecycle events reuse a pending check.
export function createOverviewLoader({ list, render, failed, defer, now = Date.now, maxAge = 60_000, revision = () => 0 }) {
  let current, verified, verifiedAt, verifiedRevision;

  async function refresh({ cached = false, strict = false } = {}) {
    if (verified && verifiedRevision !== revision()) verified = undefined;
    // Tab changes reuse verified results, including while a background check is
    // pending. Explicit mutation/repair refreshes always bypass this cache.
    if (cached && !strict && current?.pending && current.revision === revision()) return;
    if (cached && !strict && verified && now() - verifiedAt < maxAge) {
      render(verified);
      return;
    }
    current?.controller.abort();
    const active = { controller: new AbortController(), pending: true, revision: revision() }; current = active;
    const signal = active.controller.signal;
    const apply = figures => {
      if (current !== active || signal.aborted) return;
      if (!figures.some(f => f.storageChecking)) { verified = figures; verifiedAt = now(); verifiedRevision = active.revision; }
      render(figures);
    };
    const report = error => { if (current === active && !signal.aborted) failed(error); };
    let deferred = false;
    try {
      const reuse = cached && !strict && verified;
      const figures = reuse || await list({ cached: cached && !strict, signal });
      if (strict) signal.throwIfAborted();
      if (reuse) render(figures); else apply(figures);
      if (cached && !strict && (reuse || figures.some(f => f.storageChecking)) && !signal.aborted) {
        deferred = true;
        defer(async () => {
          try {
            if (current !== active || signal.aborted) return;
            apply(await list({ signal, yieldToUI: true }));
          } catch (error) { report(error); }
          finally { active.pending = false; }
        });
      }
    } catch (error) { report(error); if (strict) throw error; }
    finally { if (!deferred) active.pending = false; }
  }
  // Focus and visibility can arrive together, including during cold startup.
  // Keep the first overview and any current validation; never restart its scan.
  const resume = () => { if (!current?.pending) return refresh({ cached: true }); };
  return { refresh, resume };
}
