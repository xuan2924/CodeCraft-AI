<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { useCommand } from '../api/useCommand';
import { commandPolicy, errorMessage } from './errors';
import * as api from './api';
import CopilotPanel from './CopilotPanel.vue';

const props = defineProps<{ owner: string; exerciseId: string; paths?: string[]; credential: string; disabled: boolean; prepare: () => Promise<boolean> }>();
const emit = defineEmits<{ unauthorized: []; changed: []; 'inspect-test': [testId: string] }>();
const client = useQueryClient();
const scope = computed(() => `${props.owner}/${props.exerciseId}`);
const key = computed(() => ['copilot-conversations', props.owner, props.exerciseId]);
const selected = ref(props.exerciseId);
const forkTarget = ref<{ source: string; message: string } | null>(null);
const list = useQuery({ queryKey: key, queryFn: ({ signal }) => api.conversations(props.owner, props.exerciseId, signal), retry: false, gcTime: 0 });
watch(scope, () => { selected.value = props.exerciseId; forkTarget.value = null; }, { flush: 'sync' });
watch(list.error, failure => { if (commandPolicy.identityFailure(failure)) emit('unauthorized'); });
const create = useCommand<api.Conversation>(() => scope.value, result => {
  client.setQueryData<api.Conversation[]>(key.value, previous => [...(previous ?? []).filter(item => item.id !== result.id), result]);
  selected.value = result.id;
  forkTarget.value = null;
  void list.refetch();
}, commandPolicy, () => emit('unauthorized'));
function newConversation() {
  if (props.disabled || !props.credential || !list.data.value || create.pending.value || create.uncertain.value) return;
  const { owner, exerciseId } = props;
  void create.submit((id, signal) => api.newConversation(owner, exerciseId, props.credential, id, signal));
}
function chooseFork(message: string) {
  if (props.disabled || !props.credential || create.pending.value || create.uncertain.value) return;
  forkTarget.value = { source: selected.value, message };
}
function forkConversation() {
  if (props.disabled || !props.credential || !forkTarget.value || create.pending.value || create.uncertain.value) return;
  const { source, message } = forkTarget.value;
  const { owner, exerciseId } = props;
  void create.submit((id, signal) => api.forkConversation(owner, exerciseId, source, message, props.credential, id, signal));
}
const current = computed(() => list.data.value?.find(item => item.id === selected.value));
</script>

<template>
  <section
    class="copilot-conversations"
    aria-label="Copilot 会话"
  >
    <div class="conversation-controls">
      <label>
        <span class="sr-only">当前会话</span>
        <select
          v-model="selected"
          :disabled="!list.data.value || create.pending.value"
          aria-label="选择 Copilot 会话"
        >
          <option
            v-for="(item, index) in list.data.value"
            :key="item.id"
            :value="item.id"
          >
            {{ item.initial ? '初始会话' : `会话 ${index + 1}` }} · {{ new Date(item.createdAt).toLocaleString() }}
          </option>
        </select>
      </label>
      <Button
        variant="secondary"
        size="small"
        :disabled="disabled || !credential || !list.data.value || create.pending.value || create.uncertain.value"
        @click="newConversation"
      >
        {{ create.pending.value ? '正在新建…' : 'New · 新会话' }}
      </Button>
    </div>
    <p
      v-if="current?.forkSource"
      class="conv-fork-banner"
    >
      此分支继承自会话 {{ current.forkSource }}，截至消息 {{ current.forkMessage }}。对话历史来自过去，文件是当前版本；不会重放命令或恢复代码。
    </p>
    <div
      v-if="forkTarget"
      class="conv-fork-confirm"
      aria-label="确认 Fork"
    >
      <p>继承会话 {{ forkTarget.source }} 至消息 {{ forkTarget.message }}（包含此消息）。仅继承完整工具批次，不复制审批或活动任务；当前代码不变。</p>
      <div class="conv-actions">
        <Button
          variant="primary"
          size="small"
          :disabled="disabled || !credential || create.pending.value || create.uncertain.value"
          @click="forkConversation"
        >
          确认创建对话分支
        </Button>
        <Button
          size="small"
          :disabled="create.pending.value || create.uncertain.value"
          @click="forkTarget = null"
        >
          取消 Fork
        </Button>
      </div>
    </div>
    <p
      v-if="list.isPending.value"
      role="status"
      class="conv-note"
    >
      正在读取会话…
    </p>
    <p
      v-if="list.isError.value"
      role="alert"
      class="conv-error"
    >
      {{ errorMessage(list.error.value) }}
      <Button
        size="small"
        @click="list.refetch()"
      >
        重新读取会话
      </Button>
    </p>
    <p
      v-if="create.error.value"
      role="alert"
      class="conv-error"
    >
      {{ create.error.value }}
    </p>
    <Button
      v-if="create.uncertain.value"
      size="small"
      :disabled="create.pending.value"
      @click="create.retry()"
    >
      使用原请求键核对新建结果
    </Button>
    <CopilotPanel
      v-if="current"
      :key="`${scope}/${current.id}`"
      :owner="owner"
      :exercise-id="exerciseId"
      :paths="paths"
      :conversation-id="current.id"
      :credential="credential"
      :disabled="disabled || create.pending.value || create.uncertain.value"
      :prepare="prepare"
      @fork="chooseFork"
      @unauthorized="emit('unauthorized')"
      @changed="emit('changed')"
      @inspect-test="emit('inspect-test', $event)"
    />
  </section>
</template>

<style scoped>
.copilot-conversations {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 8px;
  padding: 0;
  background: transparent;
  border: 0;
}

.conversation-controls {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.conversation-controls label {
  flex: 1 1 180px;
  min-width: 0;
  display: flex;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}

select {
  width: 100%;
  max-width: 100%;
  padding: 6px 10px;
  font-size: 12.5px;
}

.conv-note {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-fg-muted);
  line-height: 1.45;
}

.conv-fork-banner,
.conv-fork-confirm {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border-strong);
  font-size: 12.5px;
  display: grid;
  gap: 8px;
}

.conv-fork-confirm p {
  margin: 0;
}

.conv-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.conv-error {
  margin: 0;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--oc-danger-bg);
  border: 1px solid var(--oc-danger-border);
  color: var(--oc-danger);
  font-size: 12.5px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
</style>
