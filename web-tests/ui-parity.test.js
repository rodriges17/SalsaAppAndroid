// Shared UI reviewed for Pur Cloud UI against SalsaAppCloud (base f49a60a).
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), 'a0b4b0a17d17b4cbe2d508d5942c536f85c5d04128f33c64e40f27389954897d');
 assert.equal(hash(await readFile(new URL('styles.css', root))), '9c8d78b4fad7950d9be90ba20f798d8f3272324202f64080c354ea394ada397d');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
