import { cp, copyFile, mkdir, writeFile, rm, readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { root, run } from './process.mjs';

const image = 'maven@sha256:d6e32a254897415a445654a3c43c30fbc731a4d946cf5c66d1cb9184141c20c1';

// Each invocation owns its reports, classes and container. No development .env or settings.xml.
export async function linuxJavaTests({ directory, project, values, database, mode, sandbox, test, url }) {
  const selection = test ?? { workspace: 'WorkspaceIT', identity: 'IdentityIT', recovery: 'WorkspaceCrashRecoveryIT', tracer: 'StatusIT' }[mode];
  if (!selection) throw new Error('Unknown Java integration-test mode');
  const work = join(directory, `java-tests-${mode}`);
  await mkdir(join(work, 'server/target'), { recursive: true });
  await mkdir(join(work, 'bin'));
  await copyFile(join(root, 'backend/server/pom.xml'), join(work, 'server/pom.xml'));
  for (const name of ['classes', 'test-classes']) {
    await cp(join(root, 'backend/server/target', name), join(work, 'server/target', name), { recursive: true });
  }
  const env = {
    POSTGRES_USER: values.POSTGRES_USER, POSTGRES_PASSWORD: values.POSTGRES_PASSWORD,
    SPRING_DATASOURCE_URL: `jdbc:postgresql://postgres:5432/${database}?connectTimeout=2&socketTimeout=3`,
    SPRING_DATASOURCE_USERNAME: values.POSTGRES_USER, SPRING_DATASOURCE_PASSWORD: values.POSTGRES_PASSWORD,
    BOOTSTRAP_ADMIN_USERNAME: values.BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD: values.BOOTSTRAP_ADMIN_PASSWORD,
    IDENTITY_FINGERPRINT_KEY: values.IDENTITY_FINGERPRINT_KEY,
    SANDBOX_HOST: 'sandbox', SANDBOX_PORT: '9090', SANDBOX_RPC_KEY: values.SANDBOX_RPC_KEY ?? '',
    PUBLIC_ORIGIN: `http://localhost:${values.WEB_PORT}`,
  };
  if (values.PROBLEM_PUBLICATION_FILE) {
    await copyFile(values.PROBLEM_PUBLICATION_FILE, join(work, 'publication.json'));
    env.PROBLEM_PUBLICATION_FILE = '/tests/publication.json';
  }
  if (mode === 'workspace') {
    env.WORKSPACE_IT_RUN = 'disposable';
    env.SANDBOX_TEST_CONTAINER = sandbox;
    await copyFile(join(root, 'scripts/test-support/docker-control.sh'), join(work, 'bin/docker'));
  } else if (mode === 'identity') env.IDENTITY_IT_RUN = 'disposable';
  else if (mode === 'recovery') env.WORKSPACE_CRASH_IT_RUN = 'disposable';
  else env.TRACER_IT_URL = url;
  const envFile = join(work, '.env');
  await writeFile(envFile, Object.entries(env).map(([key, value]) => `${key}=${value}`).join('\n'), { mode: 0o600 });
  const name = `${project}-${mode}-tests`;
  const failures = [];
  try {
    run('docker', ['run', '--rm', '--name', name, '--network', `${project}_default`,
      '--env-file', envFile, '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
      '--mount', `type=bind,source=${work},target=/tests`,
      '--mount', `type=bind,source=${join(homedir(), '.m2/repository')},target=/root/.m2/repository,readonly`,
      ...(mode === 'workspace' ? ['--mount', 'type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock'] : []),
      '--workdir', '/tests/server', '--entrypoint', 'sh', image, '-c',
      'set -e; export PATH=/tests/bin:$PATH; if [ -f /tests/bin/docker ]; then chmod +x /tests/bin/docker; fi; exec mvn -o -B -ntp "$@"', 'tests',
      `-Dit.test=${selection}`, 'failsafe:integration-test', 'failsafe:verify'], { timeout: 600000 });
    const summary = await readFile(join(work, 'server/target/failsafe-reports/failsafe-summary.xml'), 'utf8');
    const completed = Number(summary.match(/<completed>(\d+)<\/completed>/)?.[1]);
    const skipped = Number(summary.match(/<skipped>(\d+)<\/skipped>/)?.[1]);
    if (!(completed > 0) || skipped !== 0) throw new Error(`Expected real ${mode} tests, not an empty/skipped suite`);
  } catch (error) { failures.push(error); }
  // Attempt both cleanups even if Docker is unreachable; preserve the original test failure.
  try {
    const remaining = run('docker', ['ps', '-aq', '--filter', `name=^/${name}$`],
      { encoding: 'utf8', stdio: 'pipe', timeout: 10000 }).stdout.trim();
    if (remaining) run('docker', ['rm', '-f', name], { stdio: 'ignore', timeout: 10000 });
  } catch (error) { failures.push(error); }
  try { await rm(envFile, { force: true }); } catch (error) { failures.push(error); }
  if (failures.length === 1) throw failures[0];
  if (failures.length > 1) throw new AggregateError(failures, `Java ${mode} tests and cleanup failed; original failure is first`);
}
