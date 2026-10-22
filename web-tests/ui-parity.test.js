// SalsaAppCloud UI reference: 3e810d144187b56c4d1439cfceab031d65e4d756.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), '3a08371cf43ef1149c606963bed85b949af1dd929bc149722e7cf76f191ade4f');
 assert.equal(hash(await readFile(new URL('styles.css', root))), 'dbe54f17a2aed260158a34b4ee9907c5acdce1305e296abb4402626de25fdec5');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
