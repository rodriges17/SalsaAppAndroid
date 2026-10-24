// SalsaAppCloud UI reference: 7ef3136fbfbdf7fc05a0c630d7e252aa8213a4d3.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), '1a40c4d37f1143582fe2df05e12ceb37d758747f8a86d7da6c45929a0b6498c8');
 assert.equal(hash(await readFile(new URL('styles.css', root))), '853747f9d6d93b04f72f54cecc8a417324afe7503ec7c4f1d4b88eafbba81406');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
