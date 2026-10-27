// SalsaAppCloud UI reference: 7aac60df44c3785595e04d1df977be203044faa5.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), '703a4c374883ceb828557402f56c90d980a39e3768500bcb74317c4586d590f0');
 assert.equal(hash(await readFile(new URL('styles.css', root))), '54915931d5d522c1ad9f30c28ffd423a8f47904502b849ca533f68480ac475ce');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
