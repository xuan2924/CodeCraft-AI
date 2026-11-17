import { expect, it, vi } from 'vitest';
import { installEditorTheme } from './editorTheme';
import { colors } from './tokens';

it('uses the shared palette through the Monaco public theme API', () => {
  const defineTheme = vi.fn();
  expect(installEditorTheme({ defineTheme })).toBe('codecraft');
  expect(defineTheme).toHaveBeenCalledWith('codecraft', expect.objectContaining({
    base: 'vs-dark',
    colors: expect.objectContaining({
      'editor.background': colors.background,
      'editor.foreground': colors.foreground,
      'editorLineNumber.foreground': colors.muted,
      'editor.selectionBackground': colors.selected,
      'editorWidget.border': colors.border,
      'diffEditor.insertedTextBackground': `${colors.accent}33`,
      'diffEditor.removedTextBackground': `${colors.failure}33`,
    }),
  }));
});
