import test from 'node:test';
import assert from 'node:assert/strict';
import { quickBudget, budgetStatus } from './verification-budget.mjs';

test('30-second target warns without failing near the boundary', () => {
  const budget = quickBudget({});
  assert.deepEqual(budget, { targetSeconds: 30, timeoutSeconds: 60 });
  for (const [seconds, status] of [[29.9, 'ok'], [30, 'ok'], [30.1, 'warning'], [59.9, 'warning'], [60, 'timeout'], [61, 'timeout']]) {
    assert.equal(budgetStatus(seconds, budget), status);
  }
});

test('machine-specific headroom does not raise the performance target', () => {
  const budget = quickBudget({ CODECRAFT_QUICK_TIMEOUT_SECONDS: '90' });
  assert.equal(budgetStatus(60, budget), 'warning');
  assert.equal(budgetStatus(90, budget), 'timeout');
  assert.equal(budget.targetSeconds, 30);
  for (const value of ['31', '600']) assert.equal(quickBudget({ CODECRAFT_QUICK_TIMEOUT_SECONDS: value }).timeoutSeconds, Number(value));
});

test('invalid overrides cannot silently disable the timeout', () => {
  for (const value of ['', '0', '-1', '30', '60.5', '601', 'NaN', 'Infinity', 'slow']) {
    assert.throws(() => quickBudget({ CODECRAFT_QUICK_TIMEOUT_SECONDS: value }), /between 31 and 600/);
  }
});
