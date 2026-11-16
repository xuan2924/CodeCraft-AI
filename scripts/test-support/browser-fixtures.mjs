import { createServer } from 'node:http';
import { createHash, createHmac, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { spawn } from 'node:child_process';

// Test-only control plane. Never mounted in an application image; only a random,
// disposable tracer database is accepted. The entire database/volume is removed by tracer.
export async function browserFixtures({ project, composeArgs, env, username, database, fingerprintKey }) {
  if (!/^cc-tracer-\d+-[a-f0-9]{8}$/.test(project) || database !== 'tracer_test' || username !== 'tracer_test') {
    throw new Error('Browser fixtures require the disposable tracer database');
  }
  const key = randomBytes(32).toString('hex');
  const server = createServer(async (request, response) => {
    const supplied = Buffer.from(request.headers.authorization ?? '');
    const expected = Buffer.from(`Bearer ${key}`);
    if (request.method !== 'POST' || !['/identity', '/captcha'].includes(request.url) || supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) {
      response.writeHead(403).end(); return;
    }
    const id = randomUUID();
    const token = randomBytes(32).toString('base64url');
    const csrf = randomBytes(32).toString('base64url');
    const hash = createHash('sha256').update(token).digest('hex');
    // Every value interpolated below is generated here, never supplied by an HTTP caller.
    let sql = `BEGIN;
      INSERT INTO identity_user(id, username, password_hash, role, password_change_required)
      VALUES ('${id}', 'e2e_${id.replaceAll('-', '').slice(0, 24)}', 'unused-fixture-hash', 'USER', false);
      INSERT INTO identity_session(token_hash, user_id, auth_version, csrf_token, issued_at, last_activity)
      VALUES ('${hash}', '${id}', 1, '${csrf}', now(), now()); COMMIT;`;
    let result = { userId: id, token, csrf };
    if (request.url === '/captcha') {
      let body = '';
      for await (const chunk of request) {
        body += chunk;
        if (body.length > 512) { response.writeHead(413).end(); return; }
      }
      let challenge;
      try { challenge = JSON.parse(body).id; } catch { response.writeHead(400).end(); return; }
      if (typeof challenge !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(challenge)
          || !/^[a-f0-9]{64}$/i.test(fingerprintKey ?? '')) { response.writeHead(400).end(); return; }
      const hmac = createHmac('sha256', Buffer.from(fingerprintKey, 'hex'));
      for (const value of ['captcha-answer', challenge, 'ABCDE']) {
        const bytes = Buffer.from(value), length = Buffer.alloc(4);
        length.writeInt32BE(bytes.length); hmac.update(length); hmac.update(bytes);
      }
      sql = `UPDATE identity_captcha SET answer_hash = '${hmac.digest('hex')}' WHERE id = '${challenge}';`;
      result = { answer: 'ABCDE' };
    }
    try {
      await new Promise((resolve, reject) => {
        const child = spawn('docker', [...composeArgs, 'exec', '-T', 'postgres', 'psql', '-U', username, '-d', database, '-v', 'ON_ERROR_STOP=1'],
          { env, timeout: 10000, stdio: ['pipe', 'ignore', 'pipe'] });
        let error = '';
        child.stderr.on('data', (data) => { error = (error + data).slice(-4096); });
        child.once('error', reject);
        child.once('exit', (code) => code === 0 ? resolve() : reject(new Error(`Fixture database failed (${code}): ${error}`)));
        child.stdin.on('error', reject);
        child.stdin.end(sql);
      });
      response.writeHead(201, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      response.end(JSON.stringify(result));
    } catch (error) {
      console.error(error);
      response.writeHead(500).end('Disposable fixture unavailable');
    }
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return { url: `http://127.0.0.1:${server.address().port}`, key,
    close: () => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())) };
}
