import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { parseConfig, serviceEnv } from './local-config.mjs';
import { javaComposeEnv } from './build-java.mjs';

const base = 'POSTGRES_USER=fixture\nPOSTGRES_PASSWORD=fixture\n';
const configured = { MODEL_CREDENTIAL_ACTIVE_KEY_ID: 'new', MODEL_CREDENTIAL_KEYS: `new:${Buffer.alloc(32, 1).toString('base64')}`, MODEL_CREDENTIAL_ROTATION_ENABLED: 'true' };

test('local literal configuration admits the declared model wrapping-key settings without evaluating values', () => {
  const values = parseConfig(base + Object.entries(configured).map(([key, value]) => `${key}=${value}`).join('\n'));
  for (const [key, value] of Object.entries(configured)) assert.equal(values[key], value);
});
test('only Java receives explicitly configured wrapping keys; other services cannot inherit host copies', () => {
  const values = { ...parseConfig(base), ...configured };
  const inherited = { PATH: 'fixture-path', MODEL_CREDENTIAL_KEYS: 'host-fixture', MODEL_CREDENTIAL_ACTIVE_KEY_ID: 'host-id', MODEL_CREDENTIAL_ROTATION_ENABLED: 'true' };
  const java = serviceEnv(values, 'java', inherited);
  for (const [key, value] of Object.entries(configured)) assert.equal(java[key], value);
  for (const service of ['web', 'sandbox', 'postgres']) {
    const env = serviceEnv(values, service, inherited);
    for (const key of Object.keys(configured)) assert.equal(env[key], undefined);
    assert.equal(env.PATH, 'fixture-path');
  }
  const absent = serviceEnv(parseConfig(base), 'java', inherited);
  assert.equal(absent.MODEL_CREDENTIAL_KEYS, undefined);
  assert.equal(absent.MODEL_CREDENTIAL_ACTIVE_KEY_ID, undefined);
  assert.equal(absent.MODEL_CREDENTIAL_ROTATION_ENABLED, 'false');
});
test('paid validation refuses ambiguous suites or missing credentials before provisioning', () => {
  const env = { ...process.env };
  for (const key of Object.keys(env)) if (key.toUpperCase() === 'DEEPSEEK') delete env[key];
  for (const args of [['--copilot-browser'], ['--workspace', '--copilot-browser', '--smoke'],
    ['--workspace', '--copilot-browser', '--boundary-only'], ['--workspace', '--copilot-browser'],
    ['--workspace', '--boundary-only', '--copilot-tests']]) {
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./test-tracer.mjs', import.meta.url)), ...args],
      { env, encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /requires --workspace without other suite selections|requires DEEPSEEK/);
    assert.equal(result.stdout, '');
  }
});

test('Compose passes only explicit model settings and defaults maintenance off', () => {
  const values = javaComposeEnv({ ...parseConfig(base), ...configured, UNRELATED_PRIVATE_VALUE: 'fixture' });
  for (const [key, value] of Object.entries(configured)) assert.equal(values[key], value);
  assert.equal(values.UNRELATED_PRIVATE_VALUE, undefined);
  const defaults = javaComposeEnv(parseConfig(base));
  assert.equal(defaults.MODEL_CREDENTIAL_KEYS, '');
  assert.equal(defaults.MODEL_CREDENTIAL_ACTIVE_KEY_ID, '');
  assert.equal(defaults.MODEL_CREDENTIAL_ROTATION_ENABLED, 'false');
});
