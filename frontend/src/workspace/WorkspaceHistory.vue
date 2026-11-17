<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import { message, type Exercise, type Mutation } from './api';
import * as api from './historyApi';
import WorkspaceFileDiff from './WorkspaceFileDiff.vue';
const props = defineProps<{ owner: string; exercise: Exercise; credential: string; disabled: boolean; initialChange?: string }>();
const emit = defineEmits<{ changed: []; unauthorized: []; locked: [value: boolean] }>();
const client = useQueryClient();
const scope = computed(() => `${props.owner}/${props.exercise.exerciseId}/${props.exercise.workspaceInstanceId}`);
const root = computed(() => ['code-history', scope.value]);
const cursor = ref<string | null>(null);
const selected = ref(props.initialChange ?? '');
watch(() => props.initialChange, value => { if (value) selected.value = value; });
const selection = ref<{ change: api.CodeChange; input: api.PreviewInput } | null>(null);
const accepted = ref<{ id: string; revision: string } | null>(null);
const acknowledged = ref(false);
const notice = ref('');
watch(scope, () => { cursor.value = null; selected.value = ''; selection.value = null; accepted.value = null; acknowledged.value = false; notice.value = ''; }, { flush: 'sync' });
const listing = useQuery({ queryKey: computed(() => [...root.value, 'list', cursor.value]), retry: false, gcTime: 0,
  queryFn: ({ signal }) => api.history(props.owner, props.exercise.exerciseId, cursor.value, signal) });
const change = computed(() => listing.data.value?.changes.find(item => item.id === selected.value));
watch(selected, () => { selection.value = null; acknowledged.value = false; });
const preview = useQuery({ queryKey: computed(() => [...root.value, 'preview', selection.value]), enabled: computed(() => selection.value !== null), retry: false, gcTime: 0,
  queryFn: ({ signal }) => api.preview(props.owner, props.exercise.exerciseId, selection.value!.change, selection.value!.input, signal) });
const stale = computed(() => preview.data.value?.expectedRevision !== props.exercise.revision);
watch([() => preview.data.value?.fingerprint, stale], () => { acknowledged.value = false; });
const operationKey = (id: string) => [...root.value, 'operation', id];
const operation = useQuery({ queryKey: computed(() => operationKey(accepted.value?.id ?? '')), enabled: computed(() => accepted.value !== null), retry: false, gcTime: 0,
  queryFn: async ({ signal }) => {
    const target = accepted.value!;
    const key = operationKey(target.id);
    const result = await api.operation(props.owner, props.exercise.exerciseId, target.id, target.revision, signal);
    return api.latest(client.getQueryData<Mutation>(key), result);
  }, refetchInterval: query => query.state.data && ['SUCCEEDED', 'REJECTED'].includes(query.state.data.state) ? false : 1000 });
const unresolved = computed(() => accepted.value !== null && (!operation.data.value || !['SUCCEEDED', 'REJECTED'].includes(operation.data.value.state)));
watch(() => operation.data.value, result => {
  if (!result || !['SUCCEEDED', 'REJECTED'].includes(result.state)) return;
  notice.value = result.state === 'SUCCEEDED' ? (result.changed ? '恢复已提交为新修订，原历史仍保留。' : '目标内容相同，未产生新修订。') : message(new ApiError(409, result.failureCode ?? 'REVISION_CONFLICT'));
  selection.value = null; acknowledged.value = false;
  emit('changed'); void listing.refetch();
});
const command = useCommand<api.RestoreOutcome>(() => scope.value, result => {
  if ('notAccepted' in result) { notice.value = '已核对原请求未受理，请重新预览。'; selection.value = null; return; }
  const revision = selection.value!.input.expectedRevision;
  client.setQueryData<Mutation>(operationKey(result.operationId), current => api.latest(current, result));
  accepted.value = { id: result.operationId, revision };
}, { definitiveRejection: failure => failure instanceof ApiError && [400, 401, 403, 404, 409, 410, 429].includes(failure.status), errorMessage: message,
  identityFailure: failure => failure instanceof ApiError && (failure.status === 401 || failure.code === 'STALE_CONTROL') }, () => emit('unauthorized'));
const busy = computed(() => command.pending.value || command.uncertain.value || unresolved.value);
watch(busy, value => emit('locked', value), { immediate: true });
watch([listing.error, preview.error, operation.error], errors => { if (errors.some(error => error instanceof ApiError && error.status === 401)) emit('unauthorized'); });
function inspect(mode: api.HistoryMode, targetRevision: string | null = null) {
  if (!change.value?.retained || busy.value || props.exercise.revision === null) return;
  acknowledged.value = false; notice.value = '';
  selection.value = { change: change.value, input: { mode, targetRevision, expectedRevision: props.exercise.revision } };
  void nextTick(() => { if (selection.value && !preview.isFetching.value) void preview.refetch(); });
}
watch([change, () => props.initialChange], ([value, initial]) => {
  if (initial && value?.id === initial && value.retained) inspect('DIFF');
}, { flush: 'post' });
const mayRestore = computed(() => !props.disabled && !!props.credential && props.exercise.state === 'ACTIVE' && !busy.value && !stale.value
  && acknowledged.value && !!preview.data.value && preview.data.value.mode !== 'DIFF' && preview.data.value.conflicts.length === 0);
function restore() {
  const value = preview.data.value;
  if (!mayRestore.value || !value || value.mode === 'DIFF') return;
  const input: api.RestoreInput = { changeId: value.changeId, mode: value.mode, expectedRevision: value.expectedRevision, targetRevision: value.targetRevision, previewFingerprint: value.fingerprint };
  const { owner, exercise } = props;
  void command.submit((key, signal) => api.restore(owner, exercise.exerciseId, input, key, props.credential, signal));
}
</script>

<template>
  <section
    class="history"
    aria-label="代码修改历史"
  >
    <div class="history-toolbar">
      <div class="history-select-wrap">
        <select
          v-model="selected"
          :disabled="busy"
          aria-label="选择代码修改"
        >
          <option value="">选择历史修改预览或恢复…</option>
          <option
            v-for="item in listing.data.value?.changes"
            :key="item.id"
            :value="item.id"
          >r{{ item.baseRevision }} → r{{ item.resultRevision }} · {{ item.source }} · {{ item.retained ? '可用' : '已清理' }}</option>
        </select>
      </div>
      <div class="actions">
        <Button
          v-if="listing.data.value?.nextBefore"
          variant="ghost"
          size="small"
          :disabled="busy"
          @click="cursor = listing.data.value.nextBefore; selected = ''"
        >
          更早
        </Button>
        <Button
          v-if="cursor"
          variant="ghost"
          size="small"
          :disabled="busy"
          @click="cursor = null; selected = ''"
        >
          最新
        </Button>
        <Button
          variant="ghost"
          size="small"
          @click="listing.refetch()"
        >
          刷新历史
        </Button>
      </div>
    </div>
    <p
      v-if="listing.isPending.value"
      role="status"
      class="history-meta"
    >
      正在读取历史…
    </p>
    <p
      v-if="listing.error.value"
      role="alert"
      class="history-alert"
    >
      {{ message(listing.error.value) }}
    </p>
    <p
      v-if="listing.data.value?.changes.length === 0"
      class="history-meta"
    >
      暂无修改记录。
    </p>
    <template v-if="change">
      <p class="history-meta">
        {{ change.recordedAt }}<template v-if="change.taskId"> · 任务 {{ change.taskId }}</template><template v-if="change.conversationId"> · 会话 {{ change.conversationId }}</template>
      </p>
      <div class="actions">
        <Button
          size="small"
          :disabled="!change.retained || busy"
          @click="inspect('DIFF')"
        >
          查看修改 Diff
        </Button>
        <Button
          size="small"
          :disabled="!change.retained || busy"
          @click="inspect('UNDO')"
        >
          预览撤销此次修改
        </Button>
        <Button
          size="small"
          :disabled="!change.retained || busy"
          @click="inspect('ROLLBACK', change.baseRevision)"
        >
          预览回滚到修改前
        </Button>
        <Button
          size="small"
          :disabled="!change.retained || busy"
          @click="inspect('ROLLBACK', change.resultRevision)"
        >
          预览回滚到修改后
        </Button>
      </div>
    </template>
    <p
      v-if="preview.isFetching.value"
      role="status"
      class="history-meta"
    >
      正在读取预览…
    </p>
    <p
      v-if="preview.error.value"
      role="alert"
      class="history-alert"
    >
      {{ message(preview.error.value) }}
    </p>
    <template v-if="preview.data.value">
      <p class="history-meta">
        预览基线 r{{ preview.data.value.expectedRevision }} · {{ preview.data.value.mode }}
      </p>
      <p
        v-if="stale"
        role="alert"
        class="history-alert"
      >
        当前修订已变化，请重新预览后确认。
      </p>
      <p
        v-if="preview.data.value.conflicts.length"
        role="alert"
        class="history-alert"
      >
        撤销冲突，未覆盖后续修改：{{ preview.data.value.conflicts.join('、') }}
      </p>
      <WorkspaceFileDiff
        :files="preview.data.value.changes"
        :scope="`${scope}/${preview.data.value.fingerprint}`"
        label="历史比较文件"
      />
      <template v-if="preview.data.value.mode !== 'DIFF'">
        <label class="history-ack"><input
          v-model="acknowledged"
          type="checkbox"
          :disabled="busy || stale || preview.data.value.conflicts.length > 0"
        >确认按此预览恢复源码，保留本地草稿</label>
        <Button
          variant="primary"
          size="small"
          :disabled="!mayRestore"
          @click="restore"
        >
          确认恢复为新修订
        </Button>
      </template>
    </template>
    <p
      v-if="notice"
      role="status"
      class="history-meta"
    >
      {{ notice }}
    </p>
    <p
      v-if="command.error.value"
      role="alert"
      class="history-alert"
    >
      {{ command.error.value }}
    </p>
    <Button
      v-if="command.uncertain.value"
      size="small"
      :disabled="command.pending.value"
      @click="command.retry()"
    >
      核对原恢复请求，不更换意图
    </Button>
    <p
      v-if="unresolved"
      role="status"
      class="history-meta"
    >
      恢复已受理，正在核对最终回执…
    </p>
    <p
      v-if="operation.error.value"
      role="alert"
      class="history-alert"
    >
      {{ message(operation.error.value) }}
    </p>
    <Button
      v-if="operation.error.value"
      size="small"
      @click="operation.refetch()"
    >
      重新核对回执
    </Button>
  </section>
</template>

<style scoped>
.history {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 10px 14px 14px;
}

.history-meta {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
  font-family: var(--oc-font-mono);
  overflow-wrap: break-word;
}

.history-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.history-select-wrap {
  flex: 1 1 240px;
  min-width: 0;
}

.history-select-wrap select {
  width: 100%;
  min-height: 28px;
  padding: 4px 8px;
  font-size: 12px;
}

.history-ack {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  font-weight: 500;
}

.history-alert {
  margin: 0;
  font-size: 12px;
  color: var(--oc-danger);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
