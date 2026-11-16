<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import Button from '../components/ui/Button.vue';
import { object } from '../api/request';
const props = defineProps<{ text: string; scope: string }>();
interface Source { url: string; title: string; content: string; retainedTruncated: boolean }
const parsed = computed(() => {
  try {
    const value: unknown = JSON.parse(props.text.slice(props.text.indexOf('\n') + 1));
    if (object(value) && typeof value.code === 'string' && value.kind === undefined && value.code !== 'OK') {
      return { code: value.code, kind: props.text.startsWith('web_search [') ? 'SEARCH_SNIPPETS' : 'FETCHED_BODY', input: '', notice: '', sources: [] as Source[], credits: value.code === 'NOT_EXECUTED' ? '0' : null, usageUnknown: value.code !== 'NOT_EXECUTED' };
    }
    if (!object(value) || typeof value.code !== 'string' || !['SEARCH_SNIPPETS', 'FETCHED_BODY'].includes(String(value.kind)) || typeof value.input !== 'string'
      || !Array.isArray(value.sources) || value.sources.length > 5 || typeof value.usageUnknown !== 'boolean'
      || (value.serviceCredits !== null && (typeof value.serviceCredits !== 'string' || !/^\d+(?:\.\d+)?$/.test(value.serviceCredits)))) return null;
    if (value.kind !== (props.text.startsWith('web_search [') ? 'SEARCH_SNIPPETS' : 'FETCHED_BODY')) return null;
    const sources: Source[] = [];
    for (const source of value.sources) {
      if (!object(source) || typeof source.url !== 'string' || source.url.length > 2048 || typeof source.title !== 'string' || typeof source.content !== 'string'
        || new globalThis.TextEncoder().encode(source.content).length > 32768 || typeof source.retainedTruncated !== 'boolean') return null;
      const url = new globalThis.URL(source.url);
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null;
      sources.push({ url: source.url, title: source.title, content: source.content, retainedTruncated: source.retainedTruncated });
    }
    if (value.code !== 'OK' && sources.length > 0) return null;
    return { code: value.code, kind: value.kind, input: value.input, notice: typeof value.notice === 'string' ? value.notice.slice(0, 2048) : '', sources, credits: value.serviceCredits, usageUnknown: value.usageUnknown };
  } catch { return null; }
});
const selected = ref(0);
const offset = ref(0);
const notice = ref('');
let disposed = false;
let generation = 0;
watch([() => props.scope, selected, offset], () => { generation++; notice.value = ''; }, { flush: 'sync' });
onScopeDispose(() => { disposed = true; });
watch(() => props.scope, () => { selected.value = 0; offset.value = 0; notice.value = ''; });
watch(selected, () => { offset.value = 0; notice.value = ''; });
const source = computed(() => parsed.value?.sources[selected.value]);
const characters = computed(() => Array.from(source.value?.content ?? ''));
const page = computed(() => characters.value.slice(offset.value, offset.value + 2048).join(''));
async function copy() {
  const started = generation;
  const current = () => !disposed && started === generation;
  try { await globalThis.navigator.clipboard.writeText(page.value); if (current()) notice.value = '当前保留片段已复制。'; }
  catch { if (current()) notice.value = '复制失败，请选中下方原文手动复制。'; }
}
const errors: Record<string, string> = { INTERRUPTED: '资料调用已中断，结果未确认且可能已消耗服务 credits；不会自动重新抓取。', NOT_EXECUTED: '此资料调用未执行。', WEB_NOT_CONFIGURED: '资料服务尚未配置，请配置 Tavily 专用 Key 后发起新请求。', WEB_TIMEOUT: '资料请求超时，可能已消耗服务 credits。', WEB_NETWORK_UNKNOWN: '网络结果未知，不自动重发。', WEB_FETCH_FAILED: '网页正文获取失败，没有把空内容当成功。', WEB_RATE_LIMITED: '资料服务限流，请稍后显式发起新请求。', WEB_CREDENTIAL_REJECTED: '资料服务凭证被拒绝，请检查配置。', WEB_RESPONSE_TOO_LARGE: '服务响应超过传输限额，未保留正文。' };
</script>
<template>
  <section aria-label="联网资料结果">
    <p
      v-if="!parsed"
      role="alert"
    >
      资料回执格式无效，不能确认来源内容。
    </p>
    <template v-else>
      <h4>{{ parsed.kind === 'SEARCH_SNIPPETS' ? '搜索摘要（不是网页全文）' : '获取的网页正文' }}</h4>
      <p>{{ parsed.input }}</p>
      <p
        v-if="parsed.code !== 'OK'"
        role="alert"
      >
        {{ errors[parsed.code] ?? `资料工具未成功：${parsed.code}` }}
      </p>
      <p v-if="parsed.code !== 'OK' && parsed.notice">
        {{ parsed.notice }}
      </p>
      <p v-if="parsed.code === 'OK' && parsed.sources.length === 0">
        搜索完成，没有匹配来源。
      </p>
      <p>专用资料服务 credits：{{ parsed.usageUnknown ? '未知，不能按零计费' : parsed.credits }}；与用户模型 Token/费用分开，来源不是审批授权。</p>
      <label v-if="parsed.sources.length">来源<select
        v-model="selected"
        aria-label="选择资料来源"
      ><option
        v-for="(item, index) in parsed.sources"
        :key="item.url + index"
        :value="index"
      >{{ item.title }}</option></select></label>
      <template v-if="source">
        <a
          :href="source.url"
          target="_blank"
          rel="noopener noreferrer"
        >{{ source.url }}</a>
        <p
          v-if="source.retainedTruncated"
          role="status"
        >
          底层保留正文已截断，超出部分不可继续查看；不承诺完整网页。
        </p>
        <p v-if="characters.length > 2048">
          当前展示 {{ offset + 1 }}–{{ Math.min(offset + 2048, characters.length) }} / {{ characters.length }} 字符；翻页只读已保留内容，不重新检索。
        </p>
        <pre tabindex="0">{{ page }}</pre>
        <Button
          :disabled="offset === 0"
          @click="offset = Math.max(0, offset - 2048)"
        >
          上一段资料
        </Button>
        <Button
          :disabled="offset + 2048 >= characters.length"
          @click="offset += 2048"
        >
          继续查看保留资料
        </Button>
        <Button @click="copy">
          复制当前资料片段
        </Button>
      </template>
      <p
        v-if="notice"
        role="status"
      >
        {{ notice }}
      </p>
    </template>
  </section>
</template>
<style scoped>
section { display: grid; gap: 8px; }
p, h4 { margin: 0; overflow-wrap: anywhere; font-size: 12.5px; line-height: 1.55; }
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 24rem;
  overflow: auto;
  padding: 10px;
  border-radius: 8px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  font-size: 12px;
}
a { overflow-wrap: anywhere; font-size: 12.5px; }
select {
  max-width: 100%;
  padding: 7px 10px;
  font: inherit;
  color: var(--oc-fg);
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border-input);
  border-radius: 8px;
}
</style>
