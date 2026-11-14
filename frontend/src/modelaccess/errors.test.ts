import { expect, it } from 'vitest';
import { ApiError } from '../api/request';
import { errorMessage } from './errors';

it.each(['constructor', '__proto__'])('does not expose inherited dictionary values for an unknown error code: %s', (code) => {
  expect(errorMessage(new ApiError(502, code))).toBe('请求未完成，请检查网络或稍后重新读取。');
});
