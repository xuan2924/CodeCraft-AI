import { expect, it } from 'vitest';
import { buffer, committed, dirty, observe, useLatestBaseline } from './editorBuffer';

const file = { workspaceInstanceId: 'instance', path: 'src/main/java/Main.java', revision: '0', content: 'base' };
it('keeps typing that happened after the submitted snapshot', () => {
  const value = buffer(file); value.content = 'submitted then more';
  committed(value, 'submitted', '1');
  expect(value.content).toBe('submitted then more');
  expect(value.baseContent).toBe('submitted');
  expect(value.revision).toBe('1');
  expect(dirty(value)).toBe(true);
});
it('never replaces a dirty buffer with a newer server snapshot', () => {
  const value = buffer(file); value.content = 'local';
  observe(value, { ...file, revision: '1', content: 'server' });
  expect(value.content).toBe('local');
  expect(value.baseContent).toBe('base');
  expect(value.remote?.content).toBe('server');
  useLatestBaseline(value);
  expect(value.content).toBe('local');
  expect(value.baseContent).toBe('server');
  expect(value.revision).toBe('1');
  observe(value, { ...file, revision: '2', content: 'changed again' });
  expect(value.remote?.revision).toBe('2');
});
it('updates clean buffers and keeps larger revisions precise', () => {
  const value = buffer(file);
  observe(value, { ...file, revision: '9007199254740993', content: 'server' });
  expect(dirty(value)).toBe(false);
  expect(value.revision).toBe('9007199254740993');
});
it('does not mistake a different file for the same buffer', () => {
  expect(() => observe(buffer(file), { ...file, path: 'other' })).toThrow('File identity mismatch');
});
