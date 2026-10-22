// SalsaAppCloud UI reference: d674b65f993f0023529bdee6e6dd4d16ef84fea1.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), '854c665366de616ff29e2068b9aa7fb06cfe5b8649e4824ac15d1cc51a2486eb');
 assert.equal(hash(await readFile(new URL('styles.css', root))), '92fd4ab9a50386371e685acb582b228851ff6c2b75ee63a4fe0c4c245c60c15c');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
