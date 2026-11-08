import { join } from 'node:path';
import { root, run, maven, windows } from './process.mjs';
import { loadConfig, serviceEnv } from './local-config.mjs';

const service = process.argv[2];
if (!['postgres', 'java', 'sandbox', 'web'].includes(service)) {
  throw new Error('Usage: node scripts/dev.mjs postgres|java|sandbox|web');
}
const env = serviceEnv(await loadConfig(), service);
switch (service) {
  case 'postgres':
    run('docker', ['compose', 'up', '-d', '--wait', 'postgres'], { env });
    break;
  case 'java':
    maven(['spring-boot:run'], { env });
    break;
  case 'sandbox':
    run('go', ['run', './cmd/sandbox'], { cwd: join(root, 'backend/sandbox'), env });
    break;
  case 'web':
    run(windows ? 'npm.cmd' : 'npm', ['run', 'dev'], { cwd: join(root, 'frontend'), env });
    break;
}
