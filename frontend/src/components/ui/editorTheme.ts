import type { editor } from 'monaco-editor/editor';
import { colors } from './tokens';

/** Monaco's supported theme API, sharing the same tokens as controls and xterm. */
export function installEditorTheme(engine: Pick<typeof editor, 'defineTheme'>): string {
  engine.defineTheme('codecraft', {
    base: 'vs-dark',
    inherit: true,
    rules: [],
    colors: {
      'editor.background': colors.background,
      'editor.foreground': colors.foreground,
      'editorCursor.foreground': colors.accent,
      'editorLineNumber.foreground': colors.muted,
      'editorLineNumber.activeForeground': colors.foreground,
      'editor.lineHighlightBackground': colors.surface,
      'editor.selectionBackground': colors.selected,
      'editor.inactiveSelectionBackground': `${colors.selected}99`,
      'editorWidget.background': colors.surface,
      'editorWidget.border': colors.border,
      'editorGutter.background': colors.background,
      'diffEditor.insertedTextBackground': `${colors.accent}33`,
      'diffEditor.removedTextBackground': `${colors.failure}33`,
      'diffEditor.insertedLineBackground': `${colors.accent}14`,
      'diffEditor.removedLineBackground': `${colors.failure}14`,
      'scrollbarSlider.background': `${colors.border}88`,
      'scrollbarSlider.hoverBackground': `${colors.controlBorder}88`,
    },
  });
  return 'codecraft';
}
