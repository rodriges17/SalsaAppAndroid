// SalsaAppCloud UI reference: 91189eaa3c62c3670209c951068df374d6e24165.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), '3e00e10065af9f6fdf1a731040e9d8507591148b5fb29a784a1261e920af90a9');
 assert.equal(hash(await readFile(new URL('styles.css', root))), '3eae7cd5dced6f5ba3057458bea0985801557082f39e10efa4cd8eca6e69b4ec');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
