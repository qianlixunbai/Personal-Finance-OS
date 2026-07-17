import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

test('pagination buttons explicitly use the button type', async () => {
    const source = await readFile(new URL('../src/components/Pagination.tsx', import.meta.url), 'utf8');

    assert.match(source, /<button type="button" onClick={onPrevious}/);
    assert.match(source, /<button type="button" onClick={onNext}/);
});
