// SalsaAppCloud UI reference: eff8e3e28b7ae1e699402834f0afc8417cccd301.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), '509ace103f2da2d8ec614c2dc45efd5d4f6e134622cdee07a50fde29ae9f8bee');
 assert.equal(hash(await readFile(new URL('styles.css', root))), '1fa891a73bba1bc75dd92b31bb38c8758a07ecd0a701a5d5dc855a47a60c92db');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
