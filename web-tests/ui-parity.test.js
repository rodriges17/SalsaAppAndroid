// SalsaAppCloud UI reference: 8026d872b268837ab050c66dd5176a7ccedb9e76.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), '1a40c4d37f1143582fe2df05e12ceb37d758747f8a86d7da6c45929a0b6498c8');
 assert.equal(hash(await readFile(new URL('styles.css', root))), '9ea890a3627065e263a5b2908f7d5604ea2f06c9db4a9a029efddfdc7e66d59c');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
