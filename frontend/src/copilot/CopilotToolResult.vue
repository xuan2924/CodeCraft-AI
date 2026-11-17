<script setup lang="ts">
import { computed } from 'vue';
import { parseToolReceipt } from './toolPresentation';
import CopilotMessageBody from './CopilotMessageBody.vue';

const props = defineProps<{ text: string }>();
const parsed = computed(() => parseToolReceipt(props.text));
const compactDefault = computed(() => {
  if (!parsed.value) return false;
  return parsed.value.statusTone !== 'danger' && parsed.value.rawPayload.length > 360;
});
</script>

<template>
  <section
    v-if="parsed"
    class="tool-receipt"
    :aria-label="`工具 ${parsed.toolName} 回执`"
  >
    <div class="tool-receipt-head">
      <div class="tool-receipt-title">
        <span class="ui-badge tool-name-badge">{{ parsed.toolName }}</span>
        <span class="tool-label">{{ parsed.meta.label }}</span>
      </div>
      <div class="tool-receipt-status">
        <span
          v-if="parsed.truncated"
          class="ui-pill"
          data-tone="warning"
        >
          已截断
        </span>
        <span
          v-if="parsed.statusCode"
          class="ui-pill"
          :data-tone="parsed.statusTone"
        >
          {{ parsed.statusCode }}
        </span>
      </div>
    </div>
    <div
      v-if="parsed.chips.length"
      class="tool-chips"
    >
      <span
        v-for="(chip, index) in parsed.chips"
        :key="index"
        class="tool-chip"
      >
        {{ chip }}
      </span>
    </div>
    <details
      v-if="compactDefault"
      class="tool-payload-drawer"
    >
      <summary>查看回执内容（{{ parsed.rawPayload.length }} 字符）</summary>
      <pre class="tool-payload">{{ parsed.rawPayload }}</pre>
    </details>
    <pre
      v-else-if="parsed.rawPayload"
      class="tool-payload"
    >{{ parsed.rawPayload }}</pre>
  </section>
  <CopilotMessageBody
    v-else
    :text="text"
  />
</template>

<style scoped>
.tool-receipt {
  display: grid;
  gap: 6px;
}

.tool-receipt-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.tool-receipt-title,
.tool-receipt-status {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.tool-name-badge {
  font-family: var(--oc-font-mono);
  font-size: 11.5px;
  color: var(--oc-fg);
}

.tool-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--oc-fg-muted);
}

.tool-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.tool-chip {
  padding: 1px 7px;
  border-radius: 5px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  font-family: var(--oc-font-mono);
  font-size: 11.5px;
  color: var(--oc-fg-muted);
  overflow-wrap: anywhere;
}

.tool-payload-drawer {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.tool-payload-drawer summary {
  cursor: pointer;
}

.tool-payload {
  margin: 4px 0 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  font-size: 11.5px;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 240px;
  overflow: auto;
}
</style>
