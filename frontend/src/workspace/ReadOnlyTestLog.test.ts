import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ReadOnlyTestLog from './ReadOnlyTestLog.vue';

const adapter = vi.hoisted(() => ({ writes: [] as string[], dispose: vi.fn(), disconnect: vi.fn(), options: vi.fn() }));
vi.mock('@xterm/xterm', () => ({ Terminal: class {
  constructor(options: unknown) { adapter.options(options); }
  open() {} reset() {} resize() {}
  write(text: string, done: () => void) { adapter.writes.push(text); done(); }
  dispose() { adapter.dispose(); }
} }));
afterEach(() => { vi.unstubAllGlobals(); adapter.writes = []; vi.clearAllMocks(); });
it('disables input, removes unsafe controls, bounds rendering and disposes its external resources', async () => {
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() { adapter.disconnect(); } });
  const view = mount(ReadOnlyTestLog, { props: { logs: [{ channel: 'stderr', text: 'x'.repeat(70000) + '\u001b]52;c;clipboard\u0007\u009dtitle\u202efake' }] } });
  await flushPromises(); await flushPromises();
  expect(adapter.options).toHaveBeenCalledWith(expect.objectContaining({ disableStdin: true, scrollback: 2000 }));
  expect(adapter.writes).toHaveLength(1);
  expect(adapter.writes[0]!.length).toBeLessThan(65600);
  for (const point of [27, 7, 157, 0x202e]) expect(adapter.writes[0]).not.toContain(String.fromCodePoint(point));
  expect(view.text()).toContain('显示缓冲已截断');
  expect(view.get('.test-output').text()).toContain('[stderr]');
  view.unmount();
  expect(adapter.dispose).toHaveBeenCalledOnce(); expect(adapter.disconnect).toHaveBeenCalledOnce();
});
