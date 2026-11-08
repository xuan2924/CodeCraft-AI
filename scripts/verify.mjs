import { join } from 'node:path';
import { root, run, maven, windows } from './process.mjs';

run(process.execPath, ['--test', 'scripts/problem-contract.test.mjs', 'scripts/validation-cleanup.test.mjs']);
maven(['verify']);
const go = { cwd: join(root, 'backend/sandbox') };
run('go', ['mod', 'verify'], go);
const formatted = run('gofmt', ['-l', 'cmd', 'internal'], { ...go, encoding: 'utf8', stdio: 'pipe' });
if (formatted.stdout.trim()) throw new Error(`Run gofmt on:\n${formatted.stdout}`);
run('go', ['vet', './...'], go);
run('go', ['test', '-count=1', './...'], go);
// On Windows set CC to the verified LLVM-MinGW compiler documented in README.
run('go', ['test', '-race', '-count=1', './...'], go);
run('go', ['build', './cmd/sandbox'], go);
const npm = windows ? 'npm.cmd' : 'npm';
const frontend = { cwd: join(root, 'frontend') };
for (const script of ['lint', 'typecheck', 'test:unit', 'build']) run(npm, ['run', script], frontend);
run(npm, ['audit'], frontend);
console.log('Local checks passed. Real dependency/browser checks: node scripts/test-tracer.mjs');
