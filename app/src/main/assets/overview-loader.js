// Show persisted summaries before slow device-folder checks. Each explicit
// refresh supersedes its predecessor. Lifecycle events reuse a pending check.
export function createOverviewLoader({ list, render, failed, defer }) {
  let current;
  async function refresh({ cached = false } = {}) {
    current?.controller.abort();
    const active = { controller: new AbortController(), pending: true }; current = active;
    const signal = active.controller.signal;
    const apply = figures => { if (current === active && !signal.aborted) render(figures); };
    const report = error => { if (current === active && !signal.aborted) failed(error); };
    let deferred = false;
    try {
      const figures = await list({ cached, signal });
      apply(figures);
      if (cached && figures.some(f => f.storageChecking) && !signal.aborted) {
        deferred = true;
        defer(async () => {
          try {
            if (current !== active || signal.aborted) return;
            apply(await list({ signal, yieldToUI: true }));
          } catch (error) { report(error); }
          finally { active.pending = false; }
        });
      }
    } catch (error) { report(error); }
    finally { if (!deferred) active.pending = false; }
  }
  // Focus and visibility can arrive together, including during cold startup.
  // Keep the first overview and any current validation; never restart its scan.
  const resume = () => { if (!current?.pending) return refresh({ cached: true }); };
  return { refresh, resume };
}
