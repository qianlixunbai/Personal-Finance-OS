import assert from 'node:assert/strict';
import test from 'node:test';

test('accepts results only from the latest detail request', async () => {
    const cursor = await import('../src/utils/investmentCursor.ts') as Record<string, unknown>;
    assert.equal(typeof cursor.createLatestRequestGate, 'function');
    const createGate = cursor.createLatestRequestGate as () => { begin: () => number; isCurrent: (requestId: number) => boolean; invalidate: () => void };
    const gate = createGate();
    const first = gate.begin();
    const second = gate.begin();

    assert.equal(gate.isCurrent(first), false);
    assert.equal(gate.isCurrent(second), true);
    gate.invalidate();
    assert.equal(gate.isCurrent(second), false);
});
