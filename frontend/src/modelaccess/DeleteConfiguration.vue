<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import NButton from '../components/ui/Button.vue';
import * as api from './api';
import { useModelCommand } from './useModelCommand';
const props = defineProps<{ owner: string; kind: 'connection' | 'model'; entity: api.Connection | api.Model }>();
const emit = defineEmits<{ deleted: []; unauthorized: [] }>();
const scope = computed(() => `${props.owner}/${props.kind}/${props.entity.id}`);
const confirmation = ref<{ id: string; version: string } | null>(null);
const cancel = ref<globalThis.HTMLButtonElement>();
watch(scope, () => { confirmation.value = null; }, { flush: 'sync' });
const command = useModelCommand<api.Connection | api.Model>(() => scope.value, () => { confirmation.value = null; emit('deleted'); }, () => emit('unauthorized'));
function ask() {
  confirmation.value = Object.freeze({ id: props.entity.id, version: props.entity.version });
  void nextTick(() => cancel.value?.focus());
}
function confirm() {
  if (!confirmation.value || command.pending.value || command.uncertain.value) return;
  const { id, version } = confirmation.value; const kind = props.kind; const owner = props.owner;
  void command.submit((key, signal) => kind === 'connection' ? api.deleteConnection(id, version, key, signal, owner) : api.deleteModel(id, version, key, signal, owner));
}
</script>
<template>
  <div>
    <NButton
      v-if="!confirmation"
      @click="ask"
    >
      {{ kind === 'connection' ? '删除连接' : '删除模型' }}
    </NButton>
    <div
      v-else
      role="group"
      aria-label="删除确认"
      class="confirmation"
    >
      <p>确认删除“{{ entity.name }}”？{{ kind === 'connection' ? '其全部模型配置也将删除。' : '不会删除共用 Key 或其他模型。' }}已发出的请求不保证取消，后续使用需要重新选择配置。</p>
      <p
        v-if="command.error.value"
        role="alert"
        class="failure"
      >
        {{ command.error.value }}
      </p>
      <p
        v-if="command.uncertain.value"
        role="status"
      >
        删除结果未确认；重试核对原请求。关闭后请重新读取，不能假定未删除。
      </p>
      <div class="actions">
        <NButton
          v-if="command.uncertain.value"
          :disabled="command.pending.value"
          :loading="command.pending.value"
          @click="command.retry"
        >
          核对原删除请求
        </NButton>
        <NButton
          v-else
          variant="danger"
          :disabled="command.pending.value"
          :loading="command.pending.value"
          @click="confirm"
        >
          确认删除
        </NButton>
        <button
          ref="cancel"
          type="button"
          :disabled="command.pending.value || command.uncertain.value"
          @click="confirmation = null"
        >
          取消删除
        </button>
      </div>
    </div>
  </div>
</template>
<style scoped>
.confirmation { border-left: 3px solid var(--failure); padding-left: 16px; }
.actions { display: flex; flex-wrap: wrap; gap: 12px; }
button { font: inherit; color: inherit; background: var(--background); border: 1px solid var(--control-border); border-radius: 4px; padding: 6px 14px; }
</style>
