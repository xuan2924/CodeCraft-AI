import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { cp, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { validatePackage } from './problem-contract.mjs';

const packageDirectory = fileURLToPath(new URL('../problems/cart-total/1.0.0/', import.meta.url));
const output = fileURLToPath(new URL('../problems/.validation/', import.meta.url));
await mkdir(output, { recursive: true });
await rm(join(output, 'receipt.json'), { force: true });
const { manifest, packageSha256 } = await validatePackage(packageDirectory);
const limits = manifest.runtime;
const tag = `codecraft/cart-total:validation-${packageSha256.slice(0, 12)}`;
const runId = `cart-validation-${process.pid}-${Date.now()}`;
const scratch = await mkdtemp(join(tmpdir(), 'cart-validation-'));
const containers = new Set();
const results = [];

function docker(args, options = {}) {
    return (execFileSync('docker', args, {
        encoding: 'utf8', timeout: 15000, maxBuffer: limits.logBytes,
        ...options,
    }) ?? '').trim();
}

const maven = 'mvn -o -B -ntp -f /opt/contract/pom.xml '
    + '-Dmaven.repo.local=/opt/maven-repository -Dcandidate.sources=/work/src/main/java '
    + '-Dacceptance.sources=/opt/acceptance -Dbuild.output=/work/target test';
const measure = `
printf '\\nMETRICS\\n'
for metric in memory.peak pids.peak cpu.stat; do
    printf '%s=' "$metric"; cat /sys/fs/cgroup/$metric
done
du -sk /work /tmp
`;

async function run(name, command, source, timeoutSeconds = limits.timeoutSeconds) {
    const container = `${runId}-${name}`;
    const args = ['create', '--name', container, '--label', `codecraft.validation=${runId}`,
        '--platform', limits.platform, '--network', 'none', '--read-only', '--user', '10001:10001',
        '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
        '--cpus', String(limits.cpus), '--memory', `${limits.memoryMiB}m`,
        '--memory-swap', `${limits.memoryMiB}m`, '--pids-limit', String(limits.pids),
        '--shm-size', `${limits.shmMiB}m`, '--ulimit', 'core=0',
        '--tmpfs', `/work:rw,nosuid,nodev,noexec,size=${limits.workMiB}m,uid=10001,gid=10001`,
        '--tmpfs', `/tmp:rw,nosuid,nodev,noexec,size=${limits.tmpMiB}m,uid=10001,gid=10001`,
        '--log-driver', 'local', '--log-opt', `max-size=${limits.logBytes}`,
        '--log-opt', 'max-file=1', '--log-opt', 'compress=false'];
    if (source) args.push('--mount', `type=bind,src=${source},dst=/input,readonly`);
    containers.add(container);
    docker([...args, imageId, '-c', command]);
    const started = Date.now();
    docker(['start', container]);
    let timedOut = false;
    let state;
    do {
        state = JSON.parse(docker(['inspect', '--format', '{{json .State}}', container]));
        if (!state.Running) break;
        if (Date.now() - started > timeoutSeconds * 1000) {
            timedOut = true;
            docker(['kill', container]);
            state = JSON.parse(docker(['inspect', '--format', '{{json .State}}', container]));
            break;
        }
        await delay(200);
    } while (true);
    const elapsedMs = Date.now() - started;
    const captured = spawnSync('docker', ['logs', container], {
        encoding: 'utf8', timeout: 15000, maxBuffer: limits.logBytes,
    });
    if (captured.error) throw captured.error;
    assert.equal(captured.status, 0, captured.stderr);
    // Preserve both channels; this maintainer receipt does not promise stream ordering.
    const log = captured.stdout + '\nSTDERR\n' + captured.stderr;
    assert(Buffer.byteLength(log) <= limits.logBytes, 'Validation log exceeded cap');
    await writeFile(join(output, `${name}.log`), log);
    const result = { name, elapsedMs, exitCode: state.ExitCode, oomKilled: state.OOMKilled,
        timedOut, logBytes: Buffer.byteLength(log),
        metrics: captured.stdout.split('METRICS\n')[1]?.trim() ?? null };
    results.push(result);
    console.log(JSON.stringify(result));
    docker(['rm', '-f', container]);
    containers.delete(container);
    return { ...result, log };
}

let imageId;
try {
    // Snapshot trusted maintainer inputs before building/mounting; no live workspace is used.
    const snapshot = join(scratch, 'package');
    await cp(packageDirectory, snapshot, { recursive: true });
    assert.equal((await validatePackage(snapshot)).packageSha256, packageSha256);
    docker(['build', '--platform', limits.platform, '-t', tag, snapshot], {
        timeout: 240000, stdio: 'inherit',
    });
    imageId = docker(['image', 'inspect', '--format', '{{.Id}}', tag]);
    const environment = JSON.parse(docker(['info', '--format', '{{json .}}'], { maxBuffer: 4 * 1024 * 1024 }));
    const probe = await run('isolation', `set -eu
[ "$(id -u)" = 10001 ]
grep -q '^CapEff:[[:space:]]*0000000000000000$' /proc/self/status
grep -q '^NoNewPrivs:[[:space:]]*1$' /proc/self/status
grep -q '^Seccomp:[[:space:]]*2$' /proc/self/status
[ "$(ls /sys/class/net)" = lo ]
if timeout 2 bash -c 'echo x > /dev/tcp/1.1.1.1/443' 2>/dev/null; then exit 10; fi
if touch /root-write-probe 2>/dev/null; then exit 11; fi
grep -q ' / .* ro[, ]' /proc/mounts
if touch /opt/contract/pom.xml 2>/dev/null; then exit 15; fi
if touch /opt/acceptance/write-probe 2>/dev/null; then exit 12; fi
if touch /opt/maven-repository/write-probe 2>/dev/null; then exit 13; fi
[ ! -e /opt/reference ]
[ "$(cat /sys/fs/cgroup/memory.max)" = ${limits.memoryMiB * 1024 * 1024} ]
[ "$(cat /sys/fs/cgroup/memory.swap.max)" = 0 ]
[ "$(cat /sys/fs/cgroup/pids.max)" = ${limits.pids} ]
[ "$(cat /sys/fs/cgroup/cpu.max)" = '${limits.cpus * 100000} 100000' ]
touch /work/writable /tmp/writable
if dd if=/dev/zero of=/work/full bs=1M count=${limits.workMiB + 1} 2>/dev/null; then exit 14; fi
rm /work/full
if dd if=/dev/zero of=/tmp/full bs=1M count=${limits.tmpMiB + 1} 2>/dev/null; then exit 16; fi
rm /tmp/full
printf 'ISOLATION_OK\\n'
${measure}`);
    assert.equal(probe.exitCode, 0, probe.log);
    assert(!probe.timedOut && probe.log.includes('ISOLATION_OK'));

    for (const variant of ['initial', 'reference']) {
        const source = resolve(snapshot, variant, 'src');
        const result = await run(variant, `cp -R /input /work/src || exit 90
${maven}
status=$?
grep '<testsuite ' /work/target/surefire-reports/TEST-interview.CartTotalTest.xml || exit 91
${measure}
exit "$status"`, source);
        assert(!result.timedOut && !result.oomKilled, JSON.stringify(result));
        assert.equal(result.exitCode, variant === 'initial' ? 1 : 0, result.log);
        const expected = manifest[variant === 'initial' ? 'expectedInitial' : 'expectedReference'];
        const suite = result.log.split('\n').find((line) => line.startsWith('<testsuite '));
        assert(suite, 'Missing structured test summary');
        const testSummary = {};
        for (const [key, value] of Object.entries(expected)) {
            testSummary[key] = Number(suite.match(new RegExp(` ${key}="(\\d+)"`))?.[1]);
            assert.equal(testSummary[key], value, key);
        }
        results.at(-1).testSummary = testSummary;
    }
    const deadline = await run('deadline', 'sleep 60 & wait', null, 2);
    assert(deadline.timedOut && deadline.exitCode === 137 && !deadline.oomKilled);
    assert.equal(docker(['ps', '-aq', '--filter', `label=codecraft.validation=${runId}`]), '');
    await writeFile(join(output, 'receipt.json'), JSON.stringify({
        validatedAt: new Date().toISOString(), packageSha256, imageId,
        platform: limits.platform, limits,
        environment: { serverVersion: environment.ServerVersion, kernel: environment.KernelVersion,
            cpus: environment.NCPU, memoryBytes: environment.MemTotal, driver: environment.Driver },
        results, verdict: 'local-contract-validation-passed',
    }, null, 2) + '\n');
    console.log(`Validated ${manifest.id}@${manifest.version}; receipt: ${join(output, 'receipt.json')}`);
} finally {
    const cleanupErrors = [];
    for (const container of containers) {
        try { docker(['rm', '-f', container]); } catch (error) { cleanupErrors.push(error); }
    }
    await rm(scratch, { recursive: true, force: true });
    if (cleanupErrors.length) throw new AggregateError(cleanupErrors, `Cleanup failed: ${runId}`);
}
