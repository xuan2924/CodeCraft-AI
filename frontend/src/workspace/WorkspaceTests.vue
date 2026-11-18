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
  const wasUncertain = unknown.value !== null;
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
    if (!wasUncertain && failure instanceof ApiError && [400, 404, 409, 410, 429].includes(failure.status)) unknown.value = null;
    if (failure instanceof ApiError && failure.code === 'STALE_CONTROL') emit('stale-control');
  } finally { if (!disposed) sending.value = false; }
}
async function stop(input = stopUnknown.value ?? { task: chosen.value, key: globalThis.crypto.randomUUID(), ownerRecovery: false }) {
  if (disposed || stopping.value || props.exercise.state !== 'ACTIVE' || (input.ownerRecovery ? !props.recoveryAllowed : !props.credential)) return;
  const wasUncertain = stopUnknown.value !== null;
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
    if (!wasUncertain && failure instanceof ApiError && [400, 404, 409, 410, 429].includes(failure.status)) stopUnknown.value = null;
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
        <h2>验证输出</h2>
        <span
          v-if="recent.data.value && (recent.data.value.retention.prunedCount > 0 || recent.data.value.retention.cleanupPendingCount > 0)"
          class="tests-retention-note font-mono-num"
        >
          已精简 {{ recent.data.value.retention.prunedCount }} 条<template v-if="recent.data.value.retention.cleanupPendingCount">（{{ recent.data.value.retention.cleanupPendingCount }} 条后台清理中）</template>
        </span>
      </div>
      <Button
        variant="ghost"
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
      停止结果尚未确认。
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
      启动结果尚未确认（原请求固定修订 {{ unknown.revision }}）。
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
    <ul
      v-if="recent.data.value?.tasks.length"
      class="tests-list"
    >
      <li
        v-for="task in recent.data.value.tasks"
        :key="task.taskId"
      >
        <button
          type="button"
          :aria-pressed="chosen === task.taskId"
          @click="chosen = task.taskId"
        >
          r{{ task.revision }} · {{ labels[task.state] }}
        </button>
      </li>
    </ul>
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
      结果查询失败，{{ message(detail.error.value) }}
    </p>
    <article
      v-if="detail.data.value"
      class="test-detail-block"
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
        恢复停止仅取消该任务。
      </p>
      <p
        v-if="detail.data.value.revision !== exercise.revision"
        role="status"
        class="tests-warn"
      >
        这是旧版本结果（当前修订 {{ exercise.revision }}）。
      </p>
      <p class="tests-meta font-mono-num">
        任务 <code>{{ detail.data.value.taskId }}</code> · 目标 {{ detail.data.value.target }} · {{ stream.status.value }}
      </p>
      <p
        v-if="detail.data.value.reason"
        class="tests-desc"
      >
        {{ detail.data.value.reason }}
      </p>
      <details class="test-provenance">
        <summary>执行凭证与退出信息（退出码：{{ detail.data.value.exitCode ?? '待确认' }}）</summary>
        <div class="provenance-body font-mono-num">
          <p>题目：{{ detail.data.value.problem.problemId }} / {{ detail.data.value.problem.version }}（{{ detail.data.value.problem.platform }}）</p>
          <p>包摘要：{{ detail.data.value.problem.packageSha256 }}</p>
          <p>镜像：{{ detail.data.value.problem.executionImageDigest }}</p>
          <p>实例：{{ detail.data.value.executionInstanceId }}；清理：{{ detail.data.value.cleanup }} / {{ detail.data.value.copyCleanup }}</p>
          <p v-if="detail.data.value.evidence">
            证据：{{ detail.data.value.evidence.kind }}（{{ detail.data.value.evidence.bytes }} B，SHA-256 {{ detail.data.value.evidence.sha256 }}）
          </p>
        </div>
      </details>
      <p
        v-if="detail.data.value.cleanup === 'FAILED' || detail.data.value.copyCleanup === 'FAILED'"
        role="alert"
        class="tests-alert"
      >
        资源清理失败，请等待后台或维护者处理。
      </p>
      <p
        v-if="detail.data.value.droppedBytes !== '0'"
        class="tests-warn"
      >
        日志已截断，丢弃 {{ detail.data.value.droppedBytes }} 字节。
      </p>
      <p
        v-if="detail.data.value.logsIncomplete"
        class="tests-warn"
      >
        输出流不完整，最终状态以结构化结果为准。
      </p>
      <ReadOnlyTestLog :logs="detail.data.value.logs" />
    </article>
  </section>
</template>

<style scoped>
.workspace-tests {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 10px 14px 14px;
}

.tests-header {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.tests-title-group {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.tests-header h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.tests-desc,
.tests-retention-note,
.tests-meta {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
  line-height: 1.5;
}

.tests-alert {
  margin: 0;
  font-size: 12px;
  color: var(--oc-danger);
}

.tests-warn {
  margin: 0;
  font-size: 12px;
  color: var(--oc-warning);
}

.tests-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tests-list button {
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid var(--oc-border);
  background: var(--oc-surface);
  color: var(--oc-fg-muted);
  font-size: 12px;
  cursor: pointer;
}

.tests-list button[aria-pressed="true"] {
  background: var(--oc-selected);
  border-color: var(--oc-border-strong);
  color: var(--oc-fg);
  font-weight: 550;
}

.test-detail-block {
  padding-top: 8px;
  border-top: 1px solid var(--oc-border);
  display: grid;
  gap: 8px;
}

.test-detail-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.test-detail-head h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.test-provenance {
  overflow-wrap: break-word;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.test-provenance summary {
  cursor: pointer;
}

.provenance-body {
  margin-top: 6px;
  display: grid;
  gap: 3px;
  font-size: 11.5px;
}

.provenance-body p {
  margin: 0;
}
</style>
