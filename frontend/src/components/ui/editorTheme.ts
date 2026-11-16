import type { editor } from 'monaco-editor/editor';
import { colors } from './tokens';
/** Monaco's supported theme API, sharing the same tokens as controls and xterm. */
export function installEditorTheme(engine: Pick<typeof editor, 'defineTheme'>): string {
  engine.defineTheme('codecraft', { base: 'vs-dark', inherit: true, rules: [], colors: {
    'editor.background': colors.background, 'editor.foreground': colors.foreground,
    'editorLineNumber.foreground': colors.muted, 'editor.lineHighlightBackground': colors.surface,
    'editor.selectionBackground': colors.selected, 'editorWidget.background': colors.surface,
    'editorWidget.border': colors.border, 'editorGutter.background': colors.background,
    'diffEditor.insertedTextBackground': `${colors.accent}33`, 'diffEditor.removedTextBackground': `${colors.failure}33`,
  } });
  return 'codecraft';
}
