import { readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { root } from './process.mjs';

const defaults = {
  POSTGRES_DB: 'codecraft', POSTGRES_PORT: '5432', JAVA_PORT: '8080',
  SANDBOX_PORT: '9090', WEB_PORT: '5173',
};
const allowed = new Set([...Object.keys(defaults), 'POSTGRES_USER', 'POSTGRES_PASSWORD',
  'BOOTSTRAP_ADMIN_USERNAME', 'BOOTSTRAP_ADMIN_PASSWORD', 'IDENTITY_FINGERPRINT_KEY', 'PUBLIC_ORIGIN', 'SANDBOX_RPC_KEY', 'PROBLEM_PUBLICATION_FILE']);

// Deliberately a literal subset, not a shell/dotenv evaluator. Never log values.
export function parseConfig(text) {
  const values = { ...defaults };
  const seen = new Set();
  for (const [index, line] of text.split(/\r?\n/).entries()) {
    const entry = line.trim();
    if (!entry || entry.startsWith('#')) continue;
    const match = /^([A-Z_]+)=(.*)$/.exec(entry);
    if (!match || !allowed.has(match[1]) || seen.has(match[1])) {
      throw new Error(`Invalid/unknown/duplicate .env key at line ${index + 1}`);
    }
    const [, key, raw] = match;
    seen.add(key);
    let value = raw.trim();
    if (value.startsWith("'")) {
      if (!value.endsWith("'") || value.slice(1, -1).includes("'")) throw new Error(`Invalid quoting for ${key}`);
      value = value.slice(1, -1);
    } else if (/[\s'"#]/.test(value)) {
      throw new Error(`Use single quotes for ${key}`);
    }
    values[key] = value;
  }
  for (const key of ['POSTGRES_USER', 'POSTGRES_PASSWORD']) {
    if (!values[key]) throw new Error(`Set ${key} in .env`);
  }
  for (const key of ['POSTGRES_USER', 'POSTGRES_DB']) {
    if (!/^[a-z][a-z0-9_]{0,62}$/.test(values[key])) throw new Error(`${key} must be a lowercase database identifier`);
  }
  for (const key of ['POSTGRES_PORT', 'JAVA_PORT', 'SANDBOX_PORT', 'WEB_PORT']) {
    if (!/^\d+$/.test(values[key]) || Number(values[key]) < 1 || Number(values[key]) > 65535) {
      throw new Error(`${key} must be between 1 and 65535`);
    }
  }
  if (new Set(['POSTGRES_PORT', 'JAVA_PORT', 'SANDBOX_PORT', 'WEB_PORT'].map((key) => Number(values[key]))).size !== 4) {
    throw new Error('Service ports must be distinct');
  }
  if (values.PROBLEM_PUBLICATION_FILE && !/^[0-9a-f]{64}$/.test(values.SANDBOX_RPC_KEY ?? '')) {
    throw new Error('Published workspaces require a 32-byte lowercase hex SANDBOX_RPC_KEY');
  }
  return values;
}

export async function loadConfig() {
  const values = parseConfig(await readFile(join(root, '.env'), 'utf8'));
  if (values.PROBLEM_PUBLICATION_FILE) values.PROBLEM_PUBLICATION_FILE = resolve(root, values.PROBLEM_PUBLICATION_FILE);
  return values;
}

// Only these .env values reach each service. Frontend never receives DB/admin secrets.
export function serviceEnv(values, service) {
  const keys = {
    java: ['POSTGRES_DB', 'POSTGRES_PORT', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'JAVA_PORT', 'SANDBOX_PORT',
      'BOOTSTRAP_ADMIN_USERNAME', 'BOOTSTRAP_ADMIN_PASSWORD', 'IDENTITY_FINGERPRINT_KEY', 'PUBLIC_ORIGIN',
      'SANDBOX_RPC_KEY', 'PROBLEM_PUBLICATION_FILE'],
    sandbox: ['SANDBOX_PORT', 'SANDBOX_RPC_KEY'], web: ['JAVA_PORT', 'WEB_PORT'],
    postgres: ['POSTGRES_DB', 'POSTGRES_PORT', 'POSTGRES_USER', 'POSTGRES_PASSWORD'],
  }[service];
  if (!keys) throw new Error('Unknown service');
  const env = { ...process.env };
  for (const key of allowed) delete env[key];
  for (const key of keys) if (values[key] !== undefined) env[key] = values[key];
  if (service === 'java' && !env.PUBLIC_ORIGIN) env.PUBLIC_ORIGIN = `http://localhost:${values.WEB_PORT}`;
  return env;
}
