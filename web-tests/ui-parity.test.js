// SalsaAppCloud UI reference: 25a9288d734b815df62f570224b432cd04068db5.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), 'd6bb0deef7dbfe297a21adedd3648cf269d561ccbcf44558382a38ba5a1c5f3e');
 assert.equal(hash(await readFile(new URL('styles.css', root))), 'c9124029896a2b269e7b4f073b07287740f117f10926641d3afd22076e748096');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
