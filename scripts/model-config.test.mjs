import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { parseConfig, serviceEnv } from './local-config.mjs';
import { javaComposeEnv } from './build-java.mjs';

test('container model channel has a fixed Java destination and no upstream provider credentials', () => {
  const compose = readFileSync(new URL('../compose.yaml', import.meta.url), 'utf8');
  const sandbox = compose.split('  sandbox:')[1].split('  postgres:')[0];
  assert.match(sandbox, /SANDBOX_LLM_HOST_URL: "http:\/\/java:8080"/);
  assert.doesNotMatch(sandbox, /MODEL_CREDENTIAL|OPENAI_API_KEY|ANTHROPIC_API_KEY|COPILOT_WEB_API_KEY/);
  const channel = readFileSync(new URL('../backend/sandbox/internal/security/llm.go', import.meta.url), 'utf8');
  assert.match(channel, /OPENAI_API_KEY=" \+ grant.Token/);
  assert.match(channel, /ANTHROPIC_API_KEY=" \+ grant.Token/);
});

const base = 'POSTGRES_USER=fixture\nPOSTGRES_PASSWORD=fixture\n';

test('RabbitMQ and multi-sandbox settings are explicit and secret-isolated', () => {
  const values = parseConfig(base + 'ASYNC_BACKEND=rabbit\nRABBITMQ_PASSWORD=fixture-broker\nSANDBOX_NODES=default@one:9090,second@two:9090\n');
  for (const service of ['web', 'sandbox', 'postgres']) {
    const env = serviceEnv(values, service, { RABBITMQ_PASSWORD: 'host-secret', SANDBOX_NODES: 'host-nodes' });
    for (const key of ['RABBITMQ_PASSWORD', 'RABBITMQ_USER', 'RABBITMQ_HOST', 'RABBITMQ_PORT', 'ASYNC_BACKEND', 'SANDBOX_NODES']) assert.equal(env[key], undefined);
  }
  for (const env of [serviceEnv(values, 'java', {}), javaComposeEnv(values)]) {
    assert.equal(env.ASYNC_BACKEND, 'rabbit'); assert.equal(env.RABBITMQ_PASSWORD, 'fixture-broker');
    assert.equal(env.SANDBOX_NODES, values.SANDBOX_NODES);
  }
  assert.equal(parseConfig(base).ASYNC_BACKEND, 'local');
  for (const invalid of ['ASYNC_BACKEND=unknown', 'ASYNC_BACKEND=rabbit', 'RABBITMQ_PORT=0',
    'SANDBOX_NODES=second@two:9090', 'SANDBOX_NODES=default@one:9090,default@two:9090',
    'SANDBOX_NODES=default@one:0', 'WORKSPACE_EXECUTION_CONCURRENCY=65']) assert.throws(() => parseConfig(base + invalid));
  const compose = readFileSync(new URL('../compose.yaml', import.meta.url), 'utf8');
  assert.match(compose, /profiles: \[rabbit\]/); assert.match(compose, /profiles: \[sandbox-multi\]/);
  assert.match(compose, /sandbox-secondary-data:\/var\/lib\/codecraft/);
});

test('Redis configuration is opt-in, validated and Java-only', () => {
  const values = parseConfig(base + 'REALTIME_BACKEND=redis\nRATE_LIMIT_BACKEND=redis\nREDIS_PASSWORD=fixture-redis\n');
  for (const service of ['web', 'sandbox', 'postgres']) {
    const env = serviceEnv(values, service, { REDIS_PASSWORD: 'host-secret', REALTIME_BACKEND: 'redis' });
    for (const key of ['REDIS_HOST', 'REDIS_PORT', 'REDIS_PASSWORD', 'REALTIME_BACKEND', 'RATE_LIMIT_BACKEND']) assert.equal(env[key], undefined);
  }
  for (const env of [serviceEnv(values, 'java', {}), javaComposeEnv(values)]) {
    assert.equal(env.REDIS_PASSWORD, 'fixture-redis');
    assert.equal(env.REALTIME_BACKEND, 'redis');
    assert.equal(env.RATE_LIMIT_BACKEND, 'redis');
  }
  assert.equal(parseConfig(base).REALTIME_BACKEND, 'local');
  assert.equal(parseConfig(base).RATE_LIMIT_BACKEND, 'postgresql');
  for (const invalid of ['REALTIME_BACKEND=typo', 'RATE_LIMIT_BACKEND=local', 'REDIS_PORT=0', 'REDIS_HOST=bad/host']) {
    assert.throws(() => parseConfig(base + invalid));
  }
});
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
test('dedicated web service configuration is explicit, Java-only and container-bound', () => {
  const configured = { COPILOT_WEB_API_KEY: 'fixture-web-key', COPILOT_WEB_BASE_URL: 'https://api.tavily.com/', COPILOT_WEB_TIMEOUT: '10s' };
  const values = parseConfig(base + Object.entries(configured).map(([key, value]) => `${key}=${value}`).join('\n'));
  for (const service of ['web', 'sandbox', 'postgres']) {
    const env = serviceEnv(values, service, configured);
    for (const key of Object.keys(configured)) assert.equal(env[key], undefined);
  }
  for (const env of [serviceEnv(values, 'java', {}), javaComposeEnv(values)]) {
    for (const [key, value] of Object.entries(configured)) assert.equal(env[key], value);
  }
  assert.equal(javaComposeEnv(parseConfig(base)).COPILOT_WEB_API_KEY, '');
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

test('non-paid Copilot recovery requires its isolated suite before provisioning', () => {
  for (const args of [['--copilot-recovery'], ['--workspace', '--copilot-recovery'],
    ['--workspace', '--boundary-only', '--copilot-recovery', '--copilot-tests'],
    ['--workspace', '--boundary-only', '--copilot-recovery', '--copilot-browser']]) {
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./test-tracer.mjs', import.meta.url)), ...args],
      { encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /--copilot-recovery requires --workspace --boundary-only without other suite selections/);
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
