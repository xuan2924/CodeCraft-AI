import { createHash } from 'node:crypto';
import { chmod, mkdir, readdir, readFile, writeFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { root, run, maven, windows } from './process.mjs';

const platforms = {
  'win32-x64': ['windows-x86_64', '614f1fa619853cd8aa05817bd6b97eb03dd96d4bdbb79561fa8ae005e44d3973'],
  'linux-x64': ['linux-x86_64', '277c8548ead748ac3e600d3b860f114d36a1311dff29568cd95115ad65f59e2f'],
};
const platform = platforms[`${process.platform}-${process.arch}`];
if (!platform) throw new Error('Generation supports Windows/Linux x64; other platforms require a verified protoc checksum.');
const [classifier, checksum] = platform;
const tools = join(root, '.tools');
await mkdir(tools, { recursive: true });
const protoc = join(tools, `protoc-3.25.9-${classifier}${windows ? '.exe' : ''}`);
let bytes;
try { bytes = await readFile(protoc); } catch (error) { if (error.code !== 'ENOENT') throw error; }
if (!bytes) {
  const response = await fetch(`https://repo.maven.apache.org/maven2/com/google/protobuf/protoc/3.25.9/protoc-3.25.9-${classifier}.exe`, { signal: AbortSignal.timeout(60000) });
  if (!response.ok) throw new Error(`protoc download failed: ${response.status}`);
  bytes = Buffer.from(await response.arrayBuffer());
}
if (createHash('sha256').update(bytes).digest('hex') !== checksum) throw new Error('protoc SHA-256 mismatch');
await writeFile(protoc, bytes);
await chmod(protoc, 0o755);
const env = { ...process.env, GOBIN: tools };
run('go', ['install', 'google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.12'], { env });
run('go', ['install', 'google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.6.2'], { env });
const output = join(root, 'backend/sandbox/internal/gen');
await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });
run(protoc, [
  '-I', join(root, 'backend/proto'),
  `--plugin=protoc-gen-go=${join(tools, `protoc-gen-go${windows ? '.exe' : ''}`)}`,
  `--plugin=protoc-gen-go-grpc=${join(tools, `protoc-gen-go-grpc${windows ? '.exe' : ''}`)}`,
  `--go_out=${output}`, '--go_opt=paths=source_relative',
  `--go-grpc_out=${output}`, '--go-grpc_opt=paths=source_relative',
  ...(await readdir(join(root, 'backend/proto'))).filter((name) => name.endsWith('.proto')).sort()
    .map((name) => join(root, 'backend/proto', name)),
]);
maven(['generate-sources']);
