import assert from 'node:assert/strict';
import test from 'node:test';
import { decodeTransactionResponse, formatExactTransactionAmount, normalizeTransactionAmount, validateTransactionAmount } from '../src/utils/transactionAmount.ts';
import { nextTransactionCreateIntent } from '../src/utils/transactionCreateIntent.ts';

const amounts = ['0.01', '1.00', '1234.56', '99999999999999.99', '100000000000000.01', '100000000000000.02', '100000000000000.03', '9999999999999999.99'];

test('legal amount matrix survives JSON request and raw numeric response without Number conversion', () => {
    for (const type of ['INCOME', 'EXPENSE', 'ADJUSTMENT']) {
        for (const amount of amounts) {
            assert.equal(validateTransactionAmount(amount, type), '');
            assert.equal(normalizeTransactionAmount(amount), amount);
            assert.equal(JSON.parse(JSON.stringify({ type, amount })).amount, amount);
            const response = decodeTransactionResponse(`{"code":200,"data":{"records":[{"id":1,"amount":${amount}}],"total":1}}`) as { code: number; data: { records: Array<{ id: number; amount: string }>; total: number } };
            assert.equal(response.data.records[0]?.amount, amount);
            assert.equal(response.data.records[0]?.id, 1);
            assert.equal(response.code, 200);
            assert.equal(response.data.total, 1);
        }
    }
});

test('invalid syntax, scale, range and signs are refused without rounding or truncating', () => {
    for (const amount of ['', ' ', 'abc', '1.2.3', 'NaN', 'Infinity', '0x10', '1,234.56', '1e', '1.001', '1.000', '10000000000000000.00', '1e16', '1e-3', '0', '-0.00', '-0.01']) {
        assert.notEqual(validateTransactionAmount(amount, 'INCOME'), '', amount);
        assert.notEqual(validateTransactionAmount(amount, 'EXPENSE'), '', amount);
    }
    for (const amount of ['0', '0.00', '-0.00', '+0.00', '0e2']) assert.equal(validateTransactionAmount(amount, 'ADJUSTMENT'), '调整金额不能为 0');
    assert.equal(validateTransactionAmount('-100000000000000.01', 'ADJUSTMENT'), '');
    assert.equal(validateTransactionAmount('-9999999999999999.99', 'ADJUSTMENT'), '');
    assert.notEqual(validateTransactionAmount('-10000000000000000.00', 'ADJUSTMENT'), '');
});

test('existing integer, one decimal, explicit sign and exponent values remain exact and legal', () => {
    for (const amount of ['1', '1.0', '+1.00', '0001.00', '.01', '1.', '1e2', '1e-2', '10000000000000001e-2', '1.000e1']) {
        assert.equal(validateTransactionAmount(amount, 'INCOME'), '', amount);
        assert.equal(JSON.parse(JSON.stringify({ amount })).amount, amount);
    }
    assert.notEqual(validateTransactionAmount('1.000e0', 'INCOME'), '');
    assert.notEqual(validateTransactionAmount('1e999999999999999999999999', 'INCOME'), '');
});

test('exact list formatting preserves cents and income/expense/adjustment signs', () => {
    assert.equal(formatExactTransactionAmount('INCOME', '100000000000000.01'), '¥100,000,000,000,000.01');
    assert.equal(formatExactTransactionAmount('EXPENSE', '100000000000000.01'), '-¥100,000,000,000,000.01');
    assert.equal(formatExactTransactionAmount('ADJUSTMENT', '-100000000000000.03'), '-¥100,000,000,000,000.03');
    assert.equal(formatExactTransactionAmount('INCOME', '1'), '¥1.00');
    assert.equal(formatExactTransactionAmount('INCOME', '1.0'), '¥1.00');
    assert.throws(() => formatExactTransactionAmount('INCOME', '1.001'));
});

test('response strings and backend errors are preserved, malformed JSON is rejected', () => {
    assert.deepEqual(decodeTransactionResponse('{"data":{"amount":"100000000000000.01"}}'), { data: { amount: '100000000000000.01' } });
    assert.deepEqual(decodeTransactionResponse('{"code":400,"message":"流水金额不合法"}'), { code: 400, message: '流水金额不合法' });
    assert.throws(() => decodeTransactionResponse('{"amount":}'));
});

test('neighboring high precision cents retain distinct create intents', () => {
    const first = nextTransactionCreateIntent(null, { amount: '100000000000000.01' }, () => 'first');
    const retry = nextTransactionCreateIntent(first, { amount: '100000000000000.01' }, () => 'unused');
    const changed = nextTransactionCreateIntent(first, { amount: '100000000000000.02' }, () => 'changed');
    assert.equal(retry.key, 'first');
    assert.equal(changed.key, 'changed');
});

test('exact normalization preserves value and equivalent retry intents without hiding invalid scale', () => {
    for (const amount of ['1', '1.0', '+1.00', '0001.00', '1.', '1e0', '100e-2']) {
        assert.equal(normalizeTransactionAmount(amount), '1.00');
        const first = nextTransactionCreateIntent(null, { amount: '1.00' }, () => 'first');
        assert.equal(nextTransactionCreateIntent(first, { amount: normalizeTransactionAmount(amount) }, () => 'unused').key, 'first');
    }
    assert.equal(normalizeTransactionAmount('10000000000000001e-2'), '100000000000000.01');
    assert.equal(normalizeTransactionAmount('-000.01'), '-0.01');
    assert.equal(normalizeTransactionAmount('1e2'), '100.00');
    assert.equal(normalizeTransactionAmount('1e-2'), '0.01');
    for (const amount of ['1.000', '1.005', '1.000e0', '1e16', '0', 'abc']) assert.throws(() => normalizeTransactionAmount(amount));
});
