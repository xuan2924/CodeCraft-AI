import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import PlatformDraftForm from './PlatformDraftForm.vue';
import type { Draft } from './api';

it('emits explicit immutable draft content and immediately clears the secret input', async () => {
  const form = mount(PlatformDraftForm, { props: { disabled: false } });
  await form.get('input[type="password"]').setValue('fixture-platform-key');
  await form.get('textarea[required]').setValue('Explain only authorized requirements.');
  await form.get('form').trigger('submit');
  const submitted = form.emitted<[Draft]>('submit')?.[0]?.[0];
  expect(submitted?.connection.apiKey).toBe('fixture-platform-key');
  expect(submitted?.role).toBe('INTERVIEWER');
  expect(submitted?.skills).toEqual([]);
  expect((form.get('input[type="password"]').element as HTMLInputElement).value).toBe('');
  form.unmount();
});

it('does not submit invalid Skill JSON or submit again while a command is pending', async () => {
  const form = mount(PlatformDraftForm, { props: { disabled: false } });
  await form.get('textarea[spellcheck="false"]').setValue('{broken');
  await form.get('form').trigger('submit');
  expect(form.emitted('submit')).toBeUndefined();
  expect(form.get('[role="alert"]').text()).toContain('JSON 数组');
  await form.setProps({ disabled: true });
  await form.get('textarea[spellcheck="false"]').setValue('[]');
  await form.get('form').trigger('submit');
  expect(form.emitted('submit')).toBeUndefined();
  form.unmount();
});
