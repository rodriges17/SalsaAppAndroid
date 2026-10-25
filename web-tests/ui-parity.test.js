// SalsaAppCloud UI reference: 7aac60df44c3785595e04d1df977be203044faa5.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), '509ace103f2da2d8ec614c2dc45efd5d4f6e134622cdee07a50fde29ae9f8bee');
 assert.equal(hash(await readFile(new URL('styles.css', root))), '6cba9ea28ff56758302ad6f2378403d724b6871cf61e4ba650b0238e62d9c4fe');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
