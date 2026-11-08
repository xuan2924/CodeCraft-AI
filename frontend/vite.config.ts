import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';

const javaPort = Number(process.env.JAVA_PORT ?? 8080);
const webPort = Number(process.env.WEB_PORT ?? 5173);
for (const port of [javaPort, webPort]) {
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid service port');
}
const localServer = {
  host: '127.0.0.1',
  port: webPort,
  strictPort: true,
  proxy: { '/api': { target: `http://127.0.0.1:${javaPort}` } },
};

export default defineConfig({
  plugins: [vue()],
  server: localServer,
  preview: localServer,
  test: { environment: 'jsdom', include: ['src/**/*.test.ts'], restoreMocks: true },
});
