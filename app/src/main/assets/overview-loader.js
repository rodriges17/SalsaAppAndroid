// Show persisted summaries before slow device-folder checks. Each new refresh
// cancels its predecessor, including a startup check that has not started yet.
export function createOverviewLoader({ list, render, failed, defer }) {
  let current;
  async function refresh({ cached = false } = {}) {
    current?.abort();
    const active = new AbortController(); current = active;
    const apply = figures => { if (current === active && !active.signal.aborted) render(figures); };
    const report = error => { if (current === active && !active.signal.aborted) failed(error); };
    try {
      const figures = await list({ cached, signal: active.signal });
      apply(figures);
      if (cached && figures.some(f => f.storageChecking) && !active.signal.aborted) {
        defer(async () => {
          if (current !== active || active.signal.aborted) return;
          try { apply(await list({ signal: active.signal, yieldToUI: true })); }
          catch (error) { report(error); }
        });
      }
    } catch (error) { report(error); }
  }
  return { refresh };
}
