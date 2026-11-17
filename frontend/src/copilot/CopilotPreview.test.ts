import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { nextTransientPreview, parsePreview, watchTask } from './api';

const exercise = '10000000-0000-0000-0000-000000000001';
const taskId = '10000000-0000-0000-0000-000000000002';
const turnId = '10000000-0000-0000-0000-000000000003';
const epoch1 = '10000000-0000-0000-0000-000000000004';
const epoch2 = '10000000-0000-0000-0000-000000000005';

class FakeEventSource {
  static latest: FakeEventSource | null = null;
  listeners = new Map<string, ((event: MessageEvent) => void)[]>();
  closed = false;
  constructor(readonly url: string) {
    FakeEventSource.latest = this;
  }
  addEventListener(type: string, handler: (event: MessageEvent) => void) {
    const list = this.listeners.get(type) ?? [];
    list.push(handler);
    this.listeners.set(type, list);
  }
  emit(type: string, data: string, lastEventId = '') {
    for (const handler of this.listeners.get(type) ?? []) {
      handler(new MessageEvent(type, { data, lastEventId }));
    }
  }
  close() {
    this.closed = true;
  }
}

beforeEach(() => {
  FakeEventSource.latest = null;
  vi.stubGlobal('EventSource', FakeEventSource);
});
afterEach(() => vi.unstubAllGlobals());

it('validates copilot_preview UUID epoch and decimal string sequence', () => {
  const valid = parsePreview({
    taskId,
    turnId,
    previewEpoch: epoch1,
    previewSequence: '1',
    text: 'hello',
    thinking: 'plan',
    truncated: false,
    thinkingTruncated: false,
  }, taskId);
  expect(valid).toMatchObject({ previewEpoch: epoch1, previewSequence: '1', text: 'hello', thinking: 'plan' });

  for (const bad of [
    { taskId, turnId, previewEpoch: '123', previewSequence: '1', text: 'x', truncated: false },
    { taskId, turnId, previewEpoch: epoch1, previewSequence: '0', text: 'x', truncated: false },
    { taskId, turnId, previewEpoch: epoch1, previewSequence: 1, text: 'x', truncated: false },
  ]) {
    expect(() => parsePreview(bad, taskId)).toThrow('INVALID_RESPONSE');
  }
});

it('delivers copilot_preview without failing on empty lastEventId and ignores malformed transient events', () => {
  const onSnapshot = vi.fn();
  const onFailed = vi.fn();
  const onPreview = vi.fn();
  const controller = new AbortController();
  const stop = watchTask(exercise, taskId, onSnapshot, onFailed, controller.signal, onPreview);

  const source = FakeEventSource.latest!;
  source.emit('copilot_preview', '{"bad json', '');
  expect(onFailed).not.toHaveBeenCalled();
  expect(onPreview).not.toHaveBeenCalled();

  source.emit('copilot_preview', JSON.stringify({
    taskId,
    turnId,
    previewEpoch: epoch1,
    previewSequence: '3',
    text: 'Streaming answer',
    thinking: '',
    truncated: false,
    thinkingTruncated: false,
  }), '');
  expect(onPreview).toHaveBeenCalledWith(expect.objectContaining({ previewEpoch: epoch1, previewSequence: '3', text: 'Streaming answer' }));
  expect(onFailed).not.toHaveBeenCalled();

  const p1 = parsePreview({ taskId, turnId, previewEpoch: epoch1, previewSequence: '2', text: 'v2', truncated: false }, taskId);
  const p2 = parsePreview({ taskId, turnId, previewEpoch: epoch1, previewSequence: '1', text: 'v1', truncated: false }, taskId);
  const p3 = parsePreview({ taskId, turnId, previewEpoch: epoch2, previewSequence: '1', text: 'epoch2', truncated: false }, taskId);
  expect(nextTransientPreview(p1, p2, taskId, true)).toBe(p1);
  expect(nextTransientPreview(p1, p3, taskId, true)).toBe(p3);
  expect(nextTransientPreview(p1, p3, taskId, false)).toBe(p1);

  stop();
  expect(source.closed).toBe(true);
});
