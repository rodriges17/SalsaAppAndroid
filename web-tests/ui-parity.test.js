// SalsaAppCloud UI reference: 98ecf237204014a30b24f8b20230e6b0987a1414.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), '077462f825bb6a1201a91b74972e85a715e7c85afeb4e10977969302475cc0a3');
 assert.equal(hash(await readFile(new URL('styles.css', root))), 'f5d6f4dfbb3c549a7c65d9d25f7a5666dacceaab4495ed36d32f7e0cdc1d5b96');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
