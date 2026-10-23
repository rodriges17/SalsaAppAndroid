// SalsaAppCloud UI reference: 0235a9f4925ad232a45eab7815240ecd33d64026.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), 'dc79d4862dc9b4c9d255fbe784b75e086466cd52e28c6d5c6654d948ee539a61');
 assert.equal(hash(await readFile(new URL('styles.css', root))), '58fc152bcc7feaa2b2248c66c389e3ea8965401e3d9089f1fbff2ce335e917cf');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
