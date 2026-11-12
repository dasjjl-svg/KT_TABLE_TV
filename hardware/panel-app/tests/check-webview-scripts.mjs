import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../src/net/quber/afactorymode/AFactoryModeMainActivity.java', import.meta.url), 'utf8');
const scripts = [...source.matchAll(/evaluateJavascript\("([^"]*)"/g)].map(match => match[1]);
const ready = scripts.find(script => script.includes("getElementById('refresh')"));
const back = scripts.find(script => script.includes("getElementById('completion-confirm')"));
const standby = scripts.find(script => script.includes('requestHomePanelStandby'));
assert.ok(ready && back && standby);
assert.equal(vm.runInNewContext(ready, { document: { getElementById: () => ({}) }, createWasherAlertTracker() {} }), true);
assert.equal(vm.runInNewContext(ready, { document: { getElementById: () => null }, createWasherAlertTracker() {} }), false);
assert.equal(vm.runInNewContext(ready, { document: { getElementById: () => ({}) } }), false);
for (const scenario of ['visible', 'hidden', 'disabled', 'missing', 'focus-failed']) {
  let focused = 0;
  const document = { activeElement: null, getElementById: () => scenario === 'missing' ? null : button };
  const button = {
    disabled: scenario === 'disabled', offsetWidth: scenario === 'hidden' ? 0 : 120, offsetHeight: 0,
    getClientRects: () => [],
    focus() { focused++; if (scenario !== 'focus-failed') document.activeElement = button; },
    click() { throw new Error('Back must never acknowledge the alert'); },
  };
  assert.equal(vm.runInNewContext(back, { document, window: {} }), scenario === 'visible', scenario);
  assert.equal(focused, ['visible', 'focus-failed'].includes(scenario) ? 1 : 0, scenario);
}
for (const value of [true, false, undefined, 'true', 1]) {
  let calls = 0;
  const backWindow = { requestHomePanelBack() { calls++; return value; } };
  const document = { getElementById() { throw new Error('The modern helper owns Back handling'); } };
  assert.equal(vm.runInNewContext(back, { window: backWindow, document }), value === true);
  assert.equal(calls, 1);
  calls = 0;
  const standbyWindow = { requestHomePanelStandby() { calls++; return value; } };
  assert.equal(vm.runInNewContext(standby, { window: standbyWindow }), value === true);
  assert.equal(calls, 1);
}
assert.equal(vm.runInNewContext(standby, { window: {} }), false);
assert.equal(vm.runInNewContext(standby, { window: { requestHomePanelStandby: true } }), false);
console.log('WebView readiness, Back compatibility, and standby helper contracts verified (20 scenarios)');
