// SalsaAppCloud UI reference: 648144142c8abd3563991119fdb09a7f4f56afb3.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash = text => createHash('sha256').update(text).digest('hex');
test('Android uses the original SalsaAppCloud layout and stylesheet', async () => {
 const root = new URL('../app/src/main/assets/', import.meta.url);
 const html = await readFile(new URL('index.html', root), 'utf8');
 assert.equal(hash(html.slice(html.indexOf('<body>'))), '94c3f7838713ab05fee2b232838b166483973a8cab32a9e59ede293a59b96476');
 assert.equal(hash(await readFile(new URL('styles.css', root))), 'f743aebb0fab99f32492c84f4964d51b424b20d6ddb6300df14ab635bcf0c009');
 const manifest = await readFile(new URL('../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
 assert.match(manifest, /name=".CloudUiActivity" android:exported="true"/);
});
