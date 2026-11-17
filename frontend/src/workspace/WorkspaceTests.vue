<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import { message, type Exercise } from './api';
import * as api from './taskApi';
import { useTaskStream } from './useTaskStream';
import ReadOnlyTestLog from './ReadOnlyTestLog.vue';

const props = defineProps<{ owner: string; exercise: Exercise; credential: string; recoveryAllowed?: boolean; target?: 'base' | 'challenge' }>();
const emit = defineEmits<{ locked: [value: boolean]; 'stale-control': [] }>();
const client = useQueryClient();
const scope = ['workspace', props.owner, 'tests', props.exercise.workspaceInstanceId];
const exerciseId = props.exercise.exerciseId;
const workspaceId = props.exercise.workspaceInstanceId;
const recent = useQuery({ queryKey: scope, queryFn: ({ signal }) => api.recentTests(exerciseId, workspaceId, signal),
  retry: false, refetchInterval: 2000, gcTime: 0 });
const chosen = ref('');
watch(() => recent.data.value?.tasks, (tasks) => { if (!chosen.value && tasks?.length) chosen.value = tasks[0]!.taskId; });
const detail = useQuery({ queryKey: [...scope, 'detail', chosen], enabled: computed(() => !!chosen.value),
  queryFn: async ({ signal }) => {
    const id = chosen.value;
    const snapshot = await api.testTask(exerciseId, workspaceId, id, signal);
    const current = client.getQueryData<api.TestTask>([...scope, 'detail', id]);
    return api.latestTaskSnapshot(current, snapshot);
  }, retry: false, gcTime: 0,
  refetchInterval: (query) => query.state.data?.cursor || (query.state.data?.quiescent && query.state.data.copyCleanup !== 'PENDING') ? false : 1000 });
const stream = useTaskStream(detail.data, (id) => [...scope, 'detail', id]);
const sending = ref(false);
const stopping = ref(false);
const stopUnknown = ref<{ task: string; key: string; ownerRecovery: boolean } | null>(null);
const error = ref('');
const unknown = ref<{ key: string; revision: string; target: 'base' | 'challenge' } | null>(null);
const locked = computed(() => sending.value || !!unknown.value || recent.isPending.value || recent.isError.value ||
  recent.data.value?.tasks.some((task) => !task.quiescent) === true);
watch(locked, (value) => emit('locked', value), { immediate: true });
const controller = new globalThis.AbortController();
let disposed = false;
async function dispatch(input: { key: string; revision: string; target: 'base' | 'challenge' }) {
  if (disposed || sending.value || !props.credential || props.exercise.state !== 'ACTIVE') return;
  sending.value = true; error.value = ''; unknown.value = input;
  try {
    const result = await api.startTest(exerciseId, workspaceId, input.revision, input.key, props.credential, controller.signal, input.target);
    if (disposed) return;
    unknown.value = null;
    chosen.value = result.taskId;
    client.setQueryData<api.TestTask>([...scope, 'detail', result.taskId], (current) => api.latestTaskSnapshot(current, result));
    client.setQueryData<api.TestHistory>(scope, (old) => old ? { ...old, tasks: [result, ...old.tasks.filter((item) => item.taskId !== result.taskId)].slice(0, 100) } : undefined);
    await client.invalidateQueries({ queryKey: scope, exact: true });
  } catch (failure) {
    if (disposed) return;
    error.value = message(failure);
    if (failure instanceof ApiError && [400, 404, 409, 410, 429].includes(failure.status)) unknown.value = null;
    if (failure instanceof ApiError && failure.code === 'STALE_CONTROL') emit('stale-control');
  } finally { if (!disposed) sending.value = false; }
}
async function stop(input = stopUnknown.value ?? { task: chosen.value, key: globalThis.crypto.randomUUID(), ownerRecovery: false }) {
  if (disposed || stopping.value || props.exercise.state !== 'ACTIVE' || (input.ownerRecovery ? !props.recoveryAllowed : !props.credential)) return;
  stopping.value = true; error.value = ''; stopUnknown.value = input;
  try {
    const result = input.ownerRecovery ? await api.recoveryStopTest(exerciseId, workspaceId, input.task, input.key, controller.signal)
      : await api.stopTest(exerciseId, workspaceId, input.task, input.key, props.credential, controller.signal);
    if (disposed) return;
    stopUnknown.value = null;
    await client.cancelQueries({ queryKey: [...scope, 'detail', input.task], exact: true });
    if (disposed) return;
    client.setQueryData<api.TestTask>([...scope, 'detail', input.task], (current) => api.latestTaskSnapshot(current, result));
    await client.invalidateQueries({ queryKey: scope, exact: true });
  } catch (failure) {
    if (disposed) return;
    error.value = message(failure);
    if (failure instanceof ApiError && [400, 404, 409, 410, 429].includes(failure.status)) stopUnknown.value = null;
    if (failure instanceof ApiError && failure.code === 'STALE_CONTROL') emit('stale-control');
  } finally { if (!disposed) stopping.value = false; }
}
function recoverStop() { void stop({ task: chosen.value, key: globalThis.crypto.randomUUID(), ownerRecovery: true }); }
function start(revision: string) {
  if (!locked.value) void dispatch({ revision, key: globalThis.crypto.randomUUID(), target: props.target ?? 'base' });
}
function inspect(taskId: string) { if (!disposed) chosen.value = taskId; }
defineExpose({ start, inspect });
onScopeDispose(() => { disposed = true; controller.abort(); });
const labels: Record<string, string> = { ACCEPTED: '已受理，等待确认登记', STARTING: '正在启动', RUNNING: '正在测试', STOPPING: '正在停止', PASSED: '通过',
  FAILED: '测试失败', TIMED_OUT: '执行超时', STOPPED: '已停止', ENVIRONMENT_ERROR: '环境错误', INTERRUPTED: '执行中断' };
</script>

<template>
  <section
    class="workspace-tests"
    aria-label="测试任务与结果"
  >
    <div class="tests-header">
      <div class="tests-title-group">
        <h2>基础测试</h2>
        <span class="tests-sub">使用已保存版本 · 最终状态以结构化结果为准</span>
      </div>
      <Button
        variant="secondary"
        size="small"
        @click="recent.refetch(); chosen && detail.refetch()"
      >
        刷新测试结果
      </Button>
    </div>
    <p
      v-if="error"
      role="alert"
      class="tests-alert"
    >
      {{ error }}
    </p>
    <p
      v-if="stopUnknown"
      role="alert"
      class="tests-alert"
    >
      停止结果尚未确认，不会自动重发或停止其他任务。
    </p>
    <Button
      v-if="stopUnknown"
      size="small"
      :disabled="stopping || (stopUnknown.ownerRecovery || !credential ? !recoveryAllowed : !credential)"
      @click="stop({ ...stopUnknown, ownerRecovery: stopUnknown.ownerRecovery || !credential })"
    >
      {{ stopUnknown.ownerRecovery || !credential ? '恢复并核对原停止请求' : '核对原停止请求' }}
    </Button>
    <p
      v-if="unknown"
      role="alert"
      class="tests-alert"
    >
      启动结果尚未确认，不会自动换请求重新运行。原请求固定修订 {{ unknown.revision }}。
    </p>
    <Button
      v-if="unknown"
      size="small"
      :disabled="sending || !credential"
      @click="dispatch(unknown)"
    >
      核对并重试原测试请求
    </Button>
    <p
      v-if="recent.isPending.value"
      role="status"
      class="tests-desc"
    >
      正在查询测试任务…
    </p>
    <p
      v-else-if="recent.isError.value"
      role="alert"
      class="tests-alert"
    >
      无法确认测试状态。{{ message(recent.error.value) }}
    </p>
    <p
      v-else-if="!recent.data.value?.tasks.length"
      class="tests-desc"
    >
      尚未运行测试。
    </p>
    <ul class="tests-list">
      <li
        v-for="task in recent.data.value?.tasks"
        :key="task.taskId"
      >
        <button
          type="button"
          :aria-pressed="chosen === task.taskId"
          @click="chosen = task.taskId"
        >
          修订 {{ task.revision }} · {{ labels[task.state] }}
        </button>
      </li>
    </ul>
    <p
      v-if="recent.data.value"
      class="tests-retention-note"
    >
      普通测试保留最近 {{ recent.data.value.retention.retainedCompletedLimit }} 次完整记录；最终验收及需要恢复的任务不受此窗口淘汰。
      已精简 {{ recent.data.value.retention.prunedCount }} 条较早记录。
      <span v-if="recent.data.value.retention.cleanupPendingCount">{{ recent.data.value.retention.cleanupPendingCount }} 条仍在后台清理。</span>
    </p>
    <p
      v-if="chosen && detail.isPending.value"
      role="status"
      class="tests-desc"
    >
      正在读取测试结果…
    </p>
    <p
      v-if="detail.isError.value"
      role="alert"
      class="tests-alert"
    >
      结果查询失败，先前数据不代表当前状态。{{ message(detail.error.value) }}
    </p>
    <article
      v-if="detail.data.value"
      class="test-detail-card"
      aria-label="当前测试结果"
    >
      <div class="test-detail-head">
        <h3>{{ labels[detail.data.value.state] }} · 启动修订 {{ detail.data.value.revision }}</h3>
        <Button
          v-if="!detail.data.value.finishedAt && detail.data.value.state !== 'STOPPING' && !stopUnknown"
          variant="danger"
          size="small"
          :disabled="stopping || !credential || exercise.state !== 'ACTIVE'"
          @click="stop()"
        >
          停止任务
        </Button>
        <Button
          v-if="recoveryAllowed && !credential && exercise.state === 'ACTIVE' && !detail.data.value.quiescent && !stopUnknown"
          variant="danger"
          size="small"
          :disabled="stopping"
          @click="recoverStop"
        >
          恢复停止任务
        </Button>
      </div>
      <p
        v-if="recoveryAllowed && !credential && exercise.state === 'ACTIVE' && !detail.data.value.quiescent"
        class="tests-desc"
      >
        恢复停止只取消这个任务，不授予本页写入或新测试权限。
      </p>
      <p
        v-if="detail.data.value.revision !== exercise.revision"
        role="status"
        class="tests-warn"
      >
        这是旧版本结果；当前工作区修订 {{ exercise.revision }}，不能视为当前版本已通过。
      </p>
      <p class="tests-meta">
        任务 <code>{{ detail.data.value.taskId }}</code> · 目标 {{ detail.data.value.target }}
      </p>
      <p
        v-if="detail.data.value.reason"
        class="tests-desc"
      >
        原因：{{ detail.data.value.reason }}
      </p>
      <details class="test-provenance">
        <summary>执行身份与证据</summary>
        <div class="provenance-body">
          <p>题目：{{ detail.data.value.problem.problemId }} / {{ detail.data.value.problem.version }}（{{ detail.data.value.problem.platform }}）</p>
          <p>包摘要：{{ detail.data.value.problem.packageSha256 }}</p>
          <p>镜像：{{ detail.data.value.problem.executionImageDigest }}</p>
          <p>执行实例：{{ detail.data.value.executionInstanceId }}</p>
          <p v-if="detail.data.value.evidence">
            权威证据：{{ detail.data.value.evidence.kind }}，{{ detail.data.value.evidence.bytes }} 字节；SHA-256 {{ detail.data.value.evidence.sha256 }}。原始证据随执行记录保留，控制台文本不用于判分。
          </p>
          <p v-else>
            尚无可用于判分的权威证据。
          </p>
        </div>
      </details>
      <p class="tests-meta">
        退出码：{{ detail.data.value.exitCode ?? '尚未确认' }}；容器清理：{{ detail.data.value.cleanup }}；副本清理：{{ detail.data.value.copyCleanup }}
      </p>
      <p
        v-if="detail.data.value.cleanup === 'FAILED' || detail.data.value.copyCleanup === 'FAILED'"
        role="alert"
        class="tests-alert"
      >
        资源清理失败，需要维护者处理；不改变已确认的测试结论。
      </p>
      <p
        v-if="detail.data.value.droppedBytes !== '0'"
        class="tests-warn"
      >
        日志已截断，丢弃 {{ detail.data.value.droppedBytes }} 字节，仅保留尾部。
      </p>
      <p
        v-if="detail.data.value.logsIncomplete"
        class="tests-warn"
      >
        输出流不完整，最终状态以结构化结果为准。
      </p>
      <p
        role="status"
        class="tests-meta"
      >
        {{ stream.status.value }}
      </p>
      <ReadOnlyTestLog :logs="detail.data.value.logs" />
    </article>
  </section>
</template>

<style scoped>
.workspace-tests {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 14px;
  border-radius: var(--radius-panel);
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
}

.tests-header {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}

.tests-title-group {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.tests-header h2 {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
}

.tests-sub {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.tests-desc,
.tests-retention-note,
.tests-meta {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
  line-height: 1.55;
}

.tests-alert {
  margin: 0;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--oc-danger-bg);
  border: 1px solid var(--oc-danger-border);
  color: var(--oc-danger);
  font-size: 12.5px;
}

.tests-warn {
  margin: 0;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--oc-warning-bg);
  border: 1px solid var(--oc-warning-border);
  color: var(--oc-warning);
  font-size: 12.5px;
}

.tests-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tests-list button {
  padding: 6px 12px;
  border-radius: 8px;
  border: 1px solid var(--oc-border);
  background: var(--oc-surface-2);
  color: var(--oc-fg-muted);
  font-size: 12.5px;
  cursor: pointer;
  transition: background-color 150ms var(--oc-ease), border-color 150ms var(--oc-ease), color 150ms var(--oc-ease);
}

.tests-list button:hover {
  border-color: var(--oc-border-strong);
  color: var(--oc-fg);
}

.tests-list button[aria-pressed="true"] {
  background: var(--oc-selected);
  border-color: var(--oc-border-accent);
  color: var(--oc-fg);
  font-weight: 550;
}

.test-detail-card {
  padding: 14px;
  border-radius: 10px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 10px;
}

.test-detail-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.test-detail-head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.test-provenance {
  overflow-wrap: anywhere;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.test-provenance summary {
  cursor: pointer;
}

.provenance-body {
  margin-top: 8px;
  display: grid;
  gap: 4px;
}

.provenance-body p {
  margin: 0;
}
</style>
