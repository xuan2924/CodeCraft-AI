import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import MarkdownContent from './MarkdownContent.vue';

it('renders useful Markdown without script, remote images or active author HTML', () => {
  const page = mount(MarkdownContent, { props: { source: '# Requirement\n\n**Keep this**\n\n<script>alert(1)</script><img src="https://invalid.test/track"><iframe src="https://invalid.test"></iframe><button onclick="alert(1)">run</button><a href="javascript:alert(1)">bad link</a>' } });
  expect(page.get('h1').text()).toBe('Requirement');
  expect(page.get('strong').text()).toBe('Keep this');
  expect(page.find('script,img,iframe,button').exists()).toBe(false);
  expect(page.get('a').attributes('href')).toBeUndefined();
  page.unmount();
});
