// SalsaAppCloud UI reference: 56084d2fe3f18f59af91af563357afb08d4be8d8.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), '2b571f0caa6b35788e452e49bf5af2b520831b2323ed33529c71645a7fba9f3b');
 assert.equal(hash(await readFile(new URL('styles.css', root))), '58fc152bcc7feaa2b2248c66c389e3ea8965401e3d9089f1fbff2ce335e917cf');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
