<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import { useCommand } from '../api/useCommand';
import { commandPolicy, errorMessage } from './errors';
import * as api from './api';
import CopilotPanel from './CopilotPanel.vue';

const props = defineProps<{ owner: string; exerciseId: string; credential: string; disabled: boolean; prepare: () => Promise<boolean> }>();
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
  <section aria-label="Copilot 会话">
    <div class="conversation-controls">
      <label>
        当前会话
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
      <NButton
        :disabled="disabled || !credential || !list.data.value || create.pending.value || create.uncertain.value"
        @click="newConversation"
      >
        {{ create.pending.value ? '正在新建…' : 'New · 新会话' }}
      </NButton>
    </div>
    <p>会话各自保留聊天，共享当前代码。新建或切换不回滚代码，也不停止其他会话的任务；每个演练同一时间仅执行一个主任务。</p>
    <p v-if="current?.forkSource">
      此分支继承自会话 {{ current.forkSource }}，截至消息 {{ current.forkMessage }}。对话历史来自过去，文件是当前版本；不会重放命令或恢复代码。
    </p>
    <div
      v-if="forkTarget"
      aria-label="确认 Fork"
    >
      <p>继承会话 {{ forkTarget.source }} 至消息 {{ forkTarget.message }}（包含此消息）。仅继承完整工具批次，不复制审批或活动任务；当前代码不变。</p>
      <NButton
        :disabled="disabled || !credential || create.pending.value || create.uncertain.value"
        @click="forkConversation"
      >
        确认创建对话分支
      </NButton>
      <NButton
        :disabled="create.pending.value || create.uncertain.value"
        @click="forkTarget = null"
      >
        取消 Fork
      </NButton>
    </div>
    <p
      v-if="list.isPending.value"
      role="status"
    >
      正在读取会话…
    </p>
    <p
      v-if="list.isError.value"
      role="alert"
    >
      {{ errorMessage(list.error.value) }}
      <NButton @click="list.refetch()">
        重新读取会话
      </NButton>
    </p>
    <p
      v-if="create.error.value"
      role="alert"
    >
      {{ create.error.value }}
    </p>
    <NButton
      v-if="create.uncertain.value"
      :disabled="create.pending.value"
      @click="create.retry()"
    >
      使用原请求键核对新建结果
    </NButton>
    <CopilotPanel
      v-if="current"
      :key="`${scope}/${current.id}`"
      :owner="owner"
      :exercise-id="exerciseId"
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
.conversation-controls { display: flex; align-items: end; gap: 12px; flex-wrap: wrap; }
.conversation-controls label { display: grid; gap: 4px; }
select { max-width: 100%; padding: 6px; font: inherit; }
</style>
