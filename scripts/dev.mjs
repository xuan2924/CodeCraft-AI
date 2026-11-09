import { join } from 'node:path';
import { root, run, maven, windows } from './process.mjs';
import { loadConfig, serviceEnv } from './local-config.mjs';
import { buildSandboxImage } from './build-sandbox.mjs';
import { buildJavaImage, javaComposeEnv } from './build-java.mjs';

const service = process.argv[2];
if (!['postgres', 'java', 'sandbox', 'web'].includes(service)) {
  throw new Error('Usage: node scripts/dev.mjs postgres|java|sandbox|web');
}
const values = await loadConfig();
const env = serviceEnv(values, service);
switch (service) {
  case 'postgres':
    run('docker', ['compose', 'up', '-d', '--wait', 'postgres'], { env });
    break;
  case 'java':
    maven(['-DskipTests', 'package']);
    run('docker', ['compose', 'up', '--no-deps', 'java'], {
      env: { ...serviceEnv(values, 'postgres'), ...javaComposeEnv(values),
        JAVA_IMAGE: await buildJavaImage(values.PROBLEM_PUBLICATION_FILE) },
    });
    break;
  case 'sandbox':
    run('docker', ['compose', 'up', '--no-deps', 'sandbox'], {
      env: { ...serviceEnv(values, 'postgres'), SANDBOX_PORT: values.SANDBOX_PORT,
        SANDBOX_RPC_KEY: values.SANDBOX_RPC_KEY ?? '',
        SANDBOX_IMAGE: await buildSandboxImage(values.PROBLEM_PUBLICATION_FILE) },
    });
    break;
  case 'web':
    run(windows ? 'npm.cmd' : 'npm', ['run', 'dev'], { cwd: join(root, 'frontend'), env });
    break;
}
