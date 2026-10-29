import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createCloudLocalInventory } from '../app/src/main/assets/cloud-local-inventory.js';

test('Cloud navigation shares a pending local inventory and reuses its verified result', async () => {
  let finish, reads = 0;
  const inventory = createCloudLocalInventory({ read: () => { reads++; return new Promise(resolve => { finish = resolve; }); } });
  const first = inventory.load(), second = inventory.load();
  finish([{ id: 'verified' }]);
  assert.deepEqual(await first, [{ id: 'verified' }]);
  assert.deepEqual(await second, [{ id: 'verified' }]);
  assert.deepEqual(await inventory.load(), [{ id: 'verified' }]);
  assert.equal(reads, 1, 'Navigation must not scan the same local videos again');
});

test('a changed local library cancels the old scan and a late result cannot replace the new inventory', async () => {
  const requests = [];
  const inventory = createCloudLocalInventory({ read: options => new Promise(resolve => requests.push({ ...options, resolve })) });
  const old = inventory.load();
  inventory.clear();
  const current = inventory.load();
  assert.equal(requests[0].signal.aborted, true);
  requests[1].resolve([{ id: 'new' }]);
  assert.deepEqual(await current, [{ id: 'new' }]);
  requests[0].resolve([{ id: 'old' }]);
  await assert.rejects(old, { name: 'AbortError' });
  assert.deepEqual(await inventory.load(), [{ id: 'new' }]);
});
