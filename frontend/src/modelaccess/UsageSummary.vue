<script setup lang="ts">
import type { UsageSummary, UsagePurpose } from './usageApi';
defineProps<{ data: UsageSummary }>();
const labels: Record<UsagePurpose, string> = { CHAT: 'Copilot 请求', SUMMARY: '上下文摘要', CHECK: '连接检查' };
function unknownCosts(calls: string, priced: string) { return (BigInt(calls) - BigInt(priced)).toString(); }
</script>

<template>
  <div class="usage-summary">
    <p v-if="!data.totals.length">
      暂无保留的调用用量记录；不代表历史费用为零。
    </p>
    <section
      v-for="row in data.totals"
      :key="row.purpose"
      :aria-label="labels[row.purpose]"
    >
      <h4>{{ labels[row.purpose] }} · {{ row.calls }} 次派发</h4>
      <p v-if="row.completeCalls !== row.calls">
        统计可能不完整：仅 {{ row.completeCalls }} 次返回完整用量；缺失不按零计算。
      </p>
      <dl>
        <dt>累计输入（含缓存）</dt><dd>{{ row.inputTokens ?? '未知' }}</dd>
        <dt>普通输入</dt><dd>{{ row.ordinaryInputTokens ?? '未知' }}</dd>
        <dt>缓存读取</dt><dd>{{ row.cacheReadTokens ?? '未知' }}</dd>
        <dt>缓存写入</dt><dd>{{ row.cacheWriteTokens ?? '未知' }}</dd>
        <dt>累计输出</dt><dd>{{ row.outputTokens ?? '未知' }}</dd>
        <dt>累计 Token</dt><dd>{{ row.totalTokens ?? '未知' }}</dd>
      </dl>
      <p>缓存是输入细分，不与输入重复相加；以上为已知字段累计，不是当前上下文占用。</p>
      <p
        v-for="cost in data.costs.filter(item => item.purpose === row.purpose)"
        :key="cost.currency"
      >
        估算费用已知小计：{{ cost.amount }} {{ cost.currency }}（{{ cost.pricedCalls }} 次）
      </p>
      <p v-if="row.calls !== row.pricedCalls">
        {{ unknownCosts(row.calls, row.pricedCalls) }} 次费用未知
      </p>
    </section>
    <p class="help">
      金额仅为估算，以供应商账单为准。失败或中断请求也可能收费；未知价格或缺失用量不等于免费。
    </p>
  </div>
</template>

<style scoped>
.usage-summary { display: grid; gap: 10px; }
section { padding: 10px 12px; border-radius: 8px; background: var(--oc-surface); border: 1px solid var(--oc-border); display: grid; gap: 8px; }
h4 { margin: 0; font-size: 13px; font-weight: 600; }
dl { margin: 0; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 6px 12px; font-size: 12px; }
dt { color: var(--oc-fg-muted); }
dd { margin: 0; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; font-weight: 550; }
p { margin: 0; line-height: 1.55; overflow-wrap: anywhere; font-size: 12px; color: var(--oc-fg-muted); }
.help { color: var(--oc-fg-muted); font-size: 12px; }
</style>
