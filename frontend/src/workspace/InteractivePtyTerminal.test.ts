import { mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { message, type Exercise } from './api';
import InteractivePtyTerminal from './InteractivePtyTerminal.vue';
import * as terminalApi from './terminalApi';

const exerciseId = '10000000-0000-0000-0000-000000000001';
const workspaceId = '10000000-0000-0000-0000-000000000002';
const commandId = '10000000-0000-0000-0000-000000000003';

const mockTerminals: {
  write: ReturnType<typeof vi.fn>;
  writeln: ReturnType<typeof vi.fn>;
  resize: ReturnType<typeof vi.fn>;
  reset: ReturnType<typeof vi.fn>;
  focus: ReturnType<typeof vi.fn>;
  dispose: ReturnType<typeof vi.fn>;
  emitData: (text: string) => void;
  options: Record<string, unknown>;
}[] = [];

vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    options: Record<string, unknown> = {};
    write = vi.fn();
    writeln = vi.fn();
    resize = vi.fn();
    reset = vi.fn();
    focus = vi.fn();
    dispose = vi.fn();
    private dataHandlers: ((data: string) => void)[] = [];
    constructor(opts: Record<string, unknown>) {
      this.options = opts;
      mockTerminals.push({
        write: this.write,
        writeln: this.writeln,
        resize: this.resize,
        reset: this.reset,
        focus: this.focus,
        dispose: this.dispose,
        emitData: text => this.dataHandlers.forEach(handler => handler(text)),
        options: this.options,
      });
    }
    open() {}
    onData(handler: (data: string) => void) {
      this.dataHandlers.push(handler);
      return { dispose: () => {} };
    }
  },
}));

class FakeWebSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;
  static instances: FakeWebSocket[] = [];

  readyState = FakeWebSocket.CONNECTING;
  binaryType = 'blob';
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: (() => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  sent: (string | Uint8Array)[] = [];
  closedWith: { code?: number; reason?: string } | null = null;

  constructor(readonly url: string, readonly protocols: string[] = []) {
    FakeWebSocket.instances.push(this);
  }
  send(payload: string | Uint8Array) {
    this.sent.push(payload);
  }
  close(code?: number, reason?: string) {
    this.readyState = FakeWebSocket.CLOSED;
    this.closedWith = { code, reason };
  }
  triggerOpen() {
    this.readyState = FakeWebSocket.OPEN;
    this.onopen?.();
  }
  triggerText(payload: Record<string, unknown>) {
    this.onmessage?.({ data: JSON.stringify(payload) } as MessageEvent);
  }
  triggerBinary(bytes: Uint8Array) {
    this.onmessage?.({ data: bytes.buffer } as MessageEvent);
  }
  triggerClose(wasClean = false, code = 1006) {
    this.readyState = FakeWebSocket.CLOSED;
    this.onclose?.({ wasClean, code } as CloseEvent);
  }
}

const sampleExercise = (revision = '7', ws = workspaceId): Exercise => ({
  exerciseId,
  problemVersionId: 'prob-v1',
  workspaceInstanceId: ws,
  state: 'ACTIVE',
  revision,
  paths: ['src/Main.java'],
  deadlineAt: '2026-11-18T12:00:00Z',
  preparationFailureReason: null,
  cleanupState: null,
});

const okJson = (data: unknown, status = 200) => new Response(JSON.stringify({ code: 'OK', data }), { status });

beforeEach(() => {
  mockTerminals.length = 0;
  FakeWebSocket.instances = [];
  vi.stubGlobal('WebSocket', FakeWebSocket);
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    disconnect() {}
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

it('validates session responses, WebSocket URL safety and terminal error translations', () => {
  const valid = terminalApi.parseSession({
    commandId,
    ticket: 'opaque-ticket-value',
    webSocketPath: `/api/exercises/${exerciseId}/terminal/ws`,
    protocol: 'codecraft.terminal.v1',
    gateEpoch: '3',
    expiresAt: '2026-11-18T12:00:30Z',
  }, exerciseId);
  expect(valid.ticket).toBe('opaque-ticket-value');
  expect(terminalApi.terminalWsUrl(valid.webSocketPath, { protocol: 'https:', host: 'ide.local' }))
    .toBe(`wss://ide.local/api/exercises/${exerciseId}/terminal/ws`);

  expect(() => terminalApi.terminalWsUrl(`${valid.webSocketPath}?ticket=leak`, { protocol: 'http:', host: '127.0.0.1' }))
    .toThrow('INVALID_RESPONSE');
  expect(() => terminalApi.parseSession({
    commandId,
    ticket: 'has whitespace',
    webSocketPath: valid.webSocketPath,
    protocol: 'codecraft.terminal.v1',
    gateEpoch: '3',
    expiresAt: '2026-11-18T12:00:30Z',
  }, exerciseId)).toThrow('INVALID_RESPONSE');
  expect(message({ status: 409, code: 'TERMINAL_BUSY' })).not.toBe('');
});

it('launches WebPTY via subprotocol ticket, chunks >16KiB binary stdin, syncs cleanly and cleans up on unmount', async () => {
  const sessionPayload = {
    commandId,
    ticket: 'secret-once-ticket',
    webSocketPath: `/api/exercises/${exerciseId}/terminal/ws`,
    protocol: 'codecraft.terminal.v1',
    gateEpoch: '4',
    expiresAt: '2026-11-18T12:01:00Z',
  };
  const fetcher = vi.fn<typeof fetch>(async (url, init) => {
    const path = String(url);
    if (path.endsWith('/csrf')) return okJson({ token: 'csrf-token' });
    if (path.endsWith('/terminal/session')) {
      expect(init?.method).toBe('POST');
      const headers = new Headers(init?.headers);
      expect(headers.get('X-Operation-Credential')).toBe('cred-abc');
      return okJson(sessionPayload, 202);
    }
    if (path.endsWith('/terminal/sessions')) {
      return okJson([{
        commandId,
        state: 'EXITED',
        createdAt: '2026-11-18T00:00:00Z',
        openedAt: '2026-11-18T00:00:01Z',
        closedAt: '2026-11-18T00:00:10Z',
        baseRevision: '7',
        synchronizedRevision: '8',
        synchronization: 'SYNCED',
        reason: 'command_exit',
        pruned: false,
      }]);
    }
    return okJson({});
  });
  vi.stubGlobal('fetch', fetcher);

  const wrapper = mount(InteractivePtyTerminal, {
    props: { owner: 'user-1', exercise: sampleExercise(), credential: 'cred-abc', disabled: false },
  });
  await vi.waitFor(() => expect(mockTerminals).toHaveLength(1));

  await wrapper.get('[data-testid="pty-start-btn"]').trigger('click');
  await vi.waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1));

  const ws = FakeWebSocket.instances[0]!;
  expect(ws.url).not.toContain('secret-once-ticket');
  expect(ws.protocols).toEqual(['codecraft.terminal.v1', 'ticket.secret-once-ticket']);

  ws.triggerOpen();
  ws.triggerText({ type: 'opened', commandId });
  await wrapper.vm.$nextTick();

  // Initial resize control frame emitted upon opened
  expect(JSON.parse(String(ws.sent[0]))).toMatchObject({ type: 'resize' });
  expect(wrapper.emitted('locked')?.at(-1)).toEqual([true]);

  // Large pasted input (>16384 bytes) chunks safely into <=16KiB binary frames
  mockTerminals[0]!.emitData('x'.repeat(20000));
  const binaryFrames = ws.sent.filter((item): item is Uint8Array => typeof item !== 'string' && ArrayBuffer.isView(item));
  expect(binaryFrames.length).toBe(2);
  expect(binaryFrames[0]!.byteLength).toBe(16384);
  expect(binaryFrames[1]!.byteLength).toBe(3616);

  // Server binary PTY output renders through xterm
  ws.triggerBinary(new TextEncoder().encode('hello pty'));
  expect(mockTerminals[0]!.write).toHaveBeenCalledTimes(1);

  // Explicit workspace sync emits control frame and propagates revision update once SYNCED
  await wrapper.get('[data-testid="pty-sync-btn"]').trigger('click');
  expect(JSON.parse(String(ws.sent.at(-1)))).toEqual({ type: 'sync' });
  ws.triggerText({
    type: 'exit',
    revision: '8',
    state: 'EXITED',
    synchronization: 'SYNCED',
    reason: 'command_exit',
    hasExitCode: true,
    exitCode: 0,
  });
  await wrapper.vm.$nextTick();
  expect(wrapper.emitted('changed')).toHaveLength(1);
  expect(wrapper.emitted('locked')?.at(-1)).toEqual([false]);

  // Unmount cleanly disposes terminal
  wrapper.unmount();
  expect(mockTerminals[0]!.dispose).toHaveBeenCalledTimes(1);
});

it('preserves draft notice on CONFLICT exit without emitting changed and signals unauthorized on revoked authority', async () => {
  const fetcher = vi.fn<typeof fetch>(async (url) => {
    const path = String(url);
    if (path.endsWith('/csrf')) return okJson({ token: 'csrf-token' });
    if (path.endsWith('/terminal/sessions')) return okJson([]);
    if (path.endsWith('/terminal/session')) {
      return new Response(JSON.stringify({ code: 'TERMINAL_AUTHORITY_ENDED' }), { status: 403 });
    }
    return okJson({});
  });
  vi.stubGlobal('fetch', fetcher);

  const wrapper = mount(InteractivePtyTerminal, {
    props: { owner: 'user-1', exercise: sampleExercise(), credential: 'cred-abc', disabled: false },
  });
  await vi.waitFor(() => expect(mockTerminals).toHaveLength(1));

  await wrapper.get('[data-testid="pty-start-btn"]').trigger('click');
  await vi.waitFor(() => expect(wrapper.emitted('unauthorized')).toHaveLength(1));

  // Switch fetch to valid session and simulate a non-synced draft termination
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (url) => {
    const path = String(url);
    if (path.endsWith('/csrf')) return okJson({ token: 'csrf-token' });
    if (path.endsWith('/terminal/sessions')) return okJson([]);
    return okJson({
      commandId,
      ticket: 't2',
      webSocketPath: `/api/exercises/${exerciseId}/terminal/ws`,
      protocol: 'codecraft.terminal.v1',
      gateEpoch: '5',
      expiresAt: '2026-11-18T12:02:00Z',
    }, 202);
  }));
  await wrapper.get('[data-testid="pty-start-btn"]').trigger('click');
  await vi.waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1));
  const ws = FakeWebSocket.instances[0]!;
  ws.triggerOpen();
  ws.triggerText({ type: 'opened', commandId });
  ws.triggerText({
    type: 'exit',
    revision: '7',
    state: 'STOPPED',
    synchronization: 'CONFLICT',
    reason: 'TERMINAL_DRAFT',
    hasExitCode: false,
    exitCode: null,
  });
  await wrapper.vm.$nextTick();
  expect(wrapper.text()).toContain('草稿');
  expect(wrapper.emitted('changed')).toBeUndefined();

  await wrapper.setProps({ exercise: sampleExercise('7', '10000000-0000-0000-0000-000000000099') });
  expect(mockTerminals[0]!.reset).toHaveBeenCalled();
  wrapper.unmount();
});

it('reconciles authoritative command state after abnormal WebSocket close without exit frame', async () => {
  let currentSync: 'SYNCED' | 'CONFLICT' = 'SYNCED';
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (url) => {
    const path = String(url);
    if (path.endsWith('/csrf')) return okJson({ token: 'csrf-token' });
    if (path.endsWith('/terminal/sessions')) return okJson([]);
    if (path.endsWith('/terminal/session')) {
      return okJson({
        commandId,
        ticket: 't-reconnect',
        webSocketPath: `/api/exercises/${exerciseId}/terminal/ws`,
        protocol: 'codecraft.terminal.v1',
        gateEpoch: '5',
        expiresAt: '2026-11-18T12:02:00Z',
      }, 202);
    }
    if (path.includes(`/commands/${commandId}`)) {
      return okJson({
        id: commandId,
        exerciseId,
        workspaceInstanceId: workspaceId,
        executionInstanceId: '10000000-0000-0000-0000-000000000088',
        revision: '7',
        stateVersion: '1',
        droppedBytes: '0',
        command: 'bash',
        directory: '.',
        state: 'STOPPED',
        reason: '',
        stopRequested: false,
        quiescent: true,
        synchronization: currentSync,
        synchronizationReason: '',
        synchronizedRevision: currentSync === 'SYNCED' ? '8' : null,
        changed: currentSync === 'SYNCED',
        exitCode: null,
        logs: [],
        logsIncomplete: false,
        cleanup: 'SUCCEEDED',
        copyCleanup: 'SUCCEEDED',
        createdAt: '2026-11-18T12:00:00Z',
        completedAt: '2026-11-18T12:00:05Z',
      });
    }
    return okJson({});
  }));

  const wrapper = mount(InteractivePtyTerminal, {
    props: { owner: 'user-1', exercise: sampleExercise(), credential: 'cred-abc', disabled: false },
  });
  await vi.waitFor(() => expect(mockTerminals).toHaveLength(1));

  await wrapper.get('[data-testid="pty-start-btn"]').trigger('click');
  await vi.waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1));
  const ws1 = FakeWebSocket.instances[0]!;
  ws1.triggerOpen();
  ws1.triggerText({ type: 'opened', commandId });
  ws1.triggerClose(false, 1006);
  await vi.waitFor(() => expect(wrapper.emitted('changed')).toHaveLength(1));
  expect(wrapper.text()).toContain('已将改动同步为版本 r8');

  currentSync = 'CONFLICT';
  await wrapper.get('[data-testid="pty-start-btn"]').trigger('click');
  await vi.waitFor(() => expect(FakeWebSocket.instances).toHaveLength(2));
  const ws2 = FakeWebSocket.instances[1]!;
  ws2.triggerOpen();
  ws2.triggerText({ type: 'opened', commandId });
  ws2.triggerClose(false, 1006);
  await vi.waitFor(() => expect(wrapper.text()).toContain('容器内存在未同步修改，已作为草稿安全保留'));
  expect(wrapper.emitted('changed')).toHaveLength(1);
  wrapper.unmount();
});
