<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { Plus } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import Dialog from '../components/ui/Dialog.vue';
import { useCommand } from '../api/useCommand';
import { commandPolicy, errorMessage } from './errors';
import { formatShortTimestamp } from './feeConsent';
import * as api from './api';
import CopilotPanel from './CopilotPanel.vue';

const props = defineProps<{
  owner: string;
  exerciseId: string;
  paths?: string[];
  credential: string;
  disabled: boolean;
  prepare: () => Promise<boolean>;
  subview?: 'chat' | 'btw';
  headerTarget?: globalThis.HTMLElement | null;
}>();
const emit = defineEmits<{
  unauthorized: [];
  changed: [];
  'inspect-test': [testId: string];
  'update:subview': [value: 'chat' | 'btw'];
}>();

const client = useQueryClient();
const scope = computed(() => `${props.owner}/${props.exerciseId}`);
const key = computed(() => ['copilot-conversations', props.owner, props.exerciseId]);
const selected = ref(props.exerciseId);
const forkTarget = ref<{ source: string; message: string } | null>(null);
const panelHeaderExtra = ref<globalThis.HTMLElement | null>(null);

const list = useQuery({
  queryKey: key,
  queryFn: ({ signal }) => api.conversations(props.owner, props.exerciseId, signal),
  retry: false,
  gcTime: 0,
});
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
    <Teleport
      :to="headerTarget"
      :disabled="!headerTarget"
    >
      <div
        v-show="!subview || subview === 'chat'"
        class="conversation-controls"
      >
        <select
          v-model="selected"
          class="conv-select"
          :disabled="!list.data.value || create.pending.value"
          aria-label="选择 Copilot 会话"
        >
          <option
            v-for="(item, index) in list.data.value"
            :key="item.id"
            :value="item.id"
          >
            {{ item.initial ? '初始会话' : `会话 ${index + 1}` }} · {{ formatShortTimestamp(item.createdAt) }}
          </option>
        </select>
        <Button
          variant="ghost"
          size="icon"
          class="conv-icon-btn"
          aria-label="新建会话"
          title="新建空白会话"
          :disabled="disabled || !credential || !list.data.value || create.pending.value || create.uncertain.value"
          @click="newConversation"
        >
          <Plus
            :size="14"
            aria-hidden="true"
          />
        </Button>
        <div
          ref="panelHeaderExtra"
          class="conv-header-extra"
        />
      </div>
    </Teleport>

    <div
      v-if="current?.forkSource || list.isPending.value || list.isError.value || create.error.value || create.uncertain.value"
      class="conv-notices"
    >
      <p
        v-if="current?.forkSource"
        class="conv-fork-banner"
      >
        分支自会话 #{{ current.forkSource.slice(0, 6) }}（截至消息 #{{ (current.forkMessage ?? '').slice(0, 6) }}），基于当前工作区代码继续。
      </p>
      <p
        v-if="list.isPending.value"
        role="status"
        class="conv-note"
      >
        正在加载会话…
      </p>
      <div
        v-if="list.isError.value"
        role="alert"
        class="conv-error"
      >
        <span>{{ errorMessage(list.error.value) }}</span>
        <Button
          variant="ghost"
          size="small"
          @click="list.refetch()"
        >
          重试
        </Button>
      </div>
      <p
        v-if="create.error.value"
        role="alert"
        class="conv-error"
      >
        {{ create.error.value }}
      </p>
      <div
        v-if="create.uncertain.value"
        class="conv-error"
      >
        <span>新建会话状态待确认</span>
        <Button
          size="small"
          :disabled="create.pending.value"
          @click="create.retry()"
        >
          使用原请求键核对新建结果
        </Button>
      </div>
    </div>

    <Dialog
      :open="!!forkTarget"
      title="创建对话分支"
      description="从选定消息创建新会话分支，保留历史上下文但不影响当前代码或任务。"
      @update:open="(val) => { if (!val) forkTarget = null; }"
    >
      <template #footer>
        <Button
          variant="ghost"
          size="small"
          :disabled="create.pending.value || create.uncertain.value"
          @click="forkTarget = null"
        >
          取消
        </Button>
        <Button
          variant="primary"
          size="small"
          :disabled="disabled || !credential || create.pending.value || create.uncertain.value"
          @click="forkConversation"
        >
          确认创建分支
        </Button>
      </template>
    </Dialog>

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
      :subview="subview ?? 'chat'"
      :more-target="panelHeaderExtra"
      @fork="chooseFork"
      @switch-chat="emit('update:subview', 'chat')"
      @switch-btw="emit('update:subview', 'btw')"
      @unauthorized="emit('unauthorized')"
      @changed="emit('changed')"
      @inspect-test="emit('inspect-test', $event)"
    />
  </section>
</template>

<style scoped>
.copilot-conversations {
  flex: 1 1 0%;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.conversation-controls {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
}

.conv-select {
  min-width: 0;
  max-width: 148px;
  min-height: 26px;
  padding: 2px 6px;
  font-size: 11.5px;
  border-radius: 5px;
  text-overflow: ellipsis;
}

.conv-icon-btn {
  width: 26px;
  height: 26px;
  min-height: 26px;
  padding: 0;
  flex-shrink: 0;
}

.conv-header-extra {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.conv-notices {
  flex-shrink: 0;
  padding: 6px 10px;
  display: grid;
  gap: 6px;
  border-bottom: 1px solid var(--oc-border);
}

.conv-fork-banner,
.conv-note {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}

.conv-error {
  margin: 0;
  font-size: 12px;
  color: var(--oc-danger);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
