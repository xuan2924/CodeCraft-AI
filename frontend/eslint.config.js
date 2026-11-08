import js from '@eslint/js';
import vue from 'eslint-plugin-vue';
import ts from 'typescript-eslint';

export default ts.config(
  { ignores: ['dist/**', 'test-results/**', 'playwright-report/**'] },
  js.configs.recommended,
  ...ts.configs.recommended,
  ...vue.configs['flat/recommended'],
  { files: ['**/*.vue'], languageOptions: { parserOptions: { parser: ts.parser } } },
  { files: ['**/*.ts', '**/*.vue'], rules: { '@typescript-eslint/no-explicit-any': 'error' } },
);
