import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import CopilotToolResult from './CopilotToolResult.vue';
import { splitAssistantToolCalls } from './toolPresentation';

it.each([
  ['read', '读取文件', '{"code":"OK","path":"src/Main.java","startLine":1,"endLine":20,"totalLines":80,"revision":"3","truncated":true}'],
  ['grep', '代码检索', '{"code":"OK","pattern":"RoundingMode","matchCount":4,"truncated":false}'],
  ['find', '查找文件', '{"code":"OK","pattern":"src/**/*.java","paths":["src/Main.java","src/Tax.java"]}'],
  ['edit', '局部编辑', '{"code":"OK","baseRevision":"3","fileCount":2,"mode":"ASK"}'],
  ['write', '写入文件', '{"code":"OK","path":"src/NewFile.java","fileCount":1,"mode":"AUTO"}'],
  ['view_file', '读取文件', '{"code":"OK","path":"src/Legacy.java"}'],
  ['propose_changes', '整批提案', '{"code":"OK","fileCount":1}'],
])('renders %s tool receipt cleanly with friendly label and metadata chips', (tool, label, payload) => {
  const wrapper = mount(CopilotToolResult, { props: { text: `${tool} [call_1]\n${payload}` } });
  expect(wrapper.text()).toContain(tool);
  expect(wrapper.text()).toContain(label);
  expect(wrapper.text()).toContain('OK');
  wrapper.unmount();
});

it('renders hostile payload text safely without HTML execution and expands large payloads in a drawer', () => {
  const large = JSON.stringify({ code: 'OK', path: 'src/Main.java', content: '<script>xss()</script>' + 'a'.repeat(400) });
  const wrapper = mount(CopilotToolResult, { props: { text: `read [call_9]\n${large}` } });
  expect(wrapper.find('script').exists()).toBe(false);
  expect(wrapper.find('details.tool-payload-drawer').exists()).toBe(true);
  expect(wrapper.get('pre').text()).toContain('<script>xss()</script>');
  wrapper.unmount();
});

it('parses assistant tool call lists including Pi-style and legacy tool names', () => {
  const result = splitAssistantToolCalls('我先检查相关文件。\n工具调用：[read [c1], grep [c2], find [c3], edit [c4], write [c5], web_search [c6] 参数：{"q":"tax"}]');
  expect(result.body).toBe('我先检查相关文件。');
  expect(result.calls.map(c => [c.name, c.label])).toEqual([
    ['read', '读取文件'],
    ['grep', '代码检索'],
    ['find', '查找文件'],
    ['edit', '局部编辑'],
    ['write', '写入文件'],
    ['web_search', '联网搜索'],
  ]);
});
