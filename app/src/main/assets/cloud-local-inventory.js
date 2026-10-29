// One verified device inventory for a Cloud browsing session, not one scan per tab.
export function createCloudLocalInventory({ read }) {
  let pending, saved, controller;
  return {
    load() {
      if (saved) return Promise.resolve(saved);
      if (!pending) {
        const scan = new AbortController(); controller = scan;
        const work = Promise.resolve(read({ yieldToUI: true, signal: scan.signal })).then(value => {
          scan.signal.throwIfAborted(); saved = value; return value;
        }).finally(() => { if (pending === work) { pending = null; controller = null; } });
        pending = work;
      }
      return pending;
    },
    clear() {
      controller?.abort(); controller = null; pending = null; saved = undefined;
    }
  };
}
