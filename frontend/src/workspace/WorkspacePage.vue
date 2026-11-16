<script setup lang="ts">
import { computed, defineAsyncComponent, onScopeDispose, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { Clock, Cpu, FileCode2, RefreshCw, ShieldCheck } from '@lucide/vue';
import { session } from '../auth/api';
import { routes } from '../routes';
import { ApiError } from '../api/request';
import * as api from './api';
import WorkspaceLogout from './WorkspaceLogout.vue';
import WorkbenchLayout from './WorkbenchLayout.vue';
import WorkspaceTests from './WorkspaceTests.vue';
import WorkspaceCommands from './WorkspaceCommands.vue';
import WorkspaceHistory from './WorkspaceHistory.vue';
import WorkspaceStorage from './WorkspaceStorage.vue';
import ExerciseModelPicker from '../modelaccess/ExerciseModelPicker.vue';
import CopilotConversations from '../copilot/CopilotConversations.vue';
import InterviewerPanel from './InterviewerPanel.vue';
import ChallengePanel from './ChallengePanel.vue';
import * as challengeApi from './challengeApi';
import ClosedDrafts from './ClosedDrafts.vue';
import ProblemRequirements from '../catalog/ProblemRequirements.vue';
import Dialog from '../components/ui/Dialog.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import { cancelSaveLogout, saveLogoutOwner } from './logoutIntent';
import { confirmExerciseDeletion } from './drafts';
import { useExerciseTimer } from './useExerciseTimer';

const modelOpen = ref(false);

const WorkspaceEditor = defineAsyncComponent(() => import('./WorkspaceEditor.vue'));
const client = useQueryClient();
const account = useQuery({ queryKey: ['identity-session'], queryFn: ({ signal }) => session(signal), retry: false, gcTime: 0 });
const allowed = computed(() => !!account.data.value && !account.data.value.passwordChangeRequired &&
  !(account.error.value instanceof ApiError && [401, 403].includes(account.error.value.status)));
const owner = computed(() => account.data.value?.id ?? '');
const catalogue = useQuery({ queryKey: ['workspace', owner, 'problems'], queryFn: ({ signal }) => api.problems(signal), enabled: allowed, retry: false });
const route = useRoute();
const router = useRouter();
const lastExercise = ref(typeof route.query.exercise === 'string' ? route.query.exercise : undefined);
const active = useQuery({ queryKey: ['workspace', owner, 'current', lastExercise], queryFn: ({ signal }) => api.current(signal, lastExercise.value, owner.value),
  enabled: allowed, retry: false, refetchInterval: 2000 });
watch(() => route.query.exercise, (id) => {
  lastExercise.value = typeof id === 'string' ? id : undefined;
  if (id !== deletedExercise.value) deletedExercise.value = '';
});
const deletedExercise = ref('');
const exercise = computed(() => active.data.value?.exerciseId === deletedExercise.value ? null : active.data.value);
const problem = computed(() => catalogue.data.value?.find((item) => item.problemVersionId === exercise.value?.problemVersionId));
const challenge = useQuery({ queryKey: ['workspace', owner, 'challenge', computed(() => exercise.value?.exerciseId)],
  queryFn: ({ signal }) => challengeApi.read(owner.value, exercise.value!.exerciseId, signal),
  enabled: computed(() => allowed.value && !!exercise.value?.revision), retry: false, refetchInterval: 2000 });
const challengeBlocked = computed(() => (!problem.value || problem.value.hasChallenge) && (challenge.isPending.value || challenge.isError.value));
const testTarget = computed(() => challenge.data.value?.entered ? 'challenge' as const : 'base' as const);
const selected = ref('');
const editorView = ref<{ saveForLogout: () => Promise<boolean>; prepareEnd: (choice: 'save' | 'discard' | 'retain') => Promise<boolean> }>();
const { expired, remaining } = useExerciseTimer(exercise);
const implementingStates = ['ACTIVE', 'PAUSING', 'PAUSED', 'RESUMING'];
const historyStates = [...implementingStates, 'CLOSING', 'FINAL_TESTING', 'ENDED'];
const canEnd = computed(() => !!exercise.value && implementingStates.includes(exercise.value.state) && !expired.value);
const answeringClosed = computed(() => expired.value || exercise.value?.state !== 'ACTIVE');
const endPrompt = ref(false);
const ending = ref(false);
const hasEditor = ref(false);
watch(() => exercise.value?.exerciseId, (id) => {
  if (id) {
    lastExercise.value = id;
    if (route.query.exercise !== id) void router.replace({ query: { ...route.query, exercise: id } });
  }
  hasEditor.value = !!exercise.value && implementingStates.includes(exercise.value.state);
  endPrompt.value = false;
});
watch(() => exercise.value?.state, (state) => { if (state && implementingStates.includes(state)) hasEditor.value = true; });
watch(expired, (value) => { if (value) { endPrompt.value = false; void active.refetch(); } });
const logoutBusy = ref(false);
const testView = ref<{ start: (revision: string) => void; inspect: (taskId: string) => void }>();
const testBusy = ref(true);
const commandBusy = ref(true);
const historyBusy = ref(false);
const editorWriteBusy = ref(false);
const editorGeneration = ref(0);
async function saveForLogout() {
  if (!writable.value || pending.value || unresolved.value) return false;
  if (editorView.value) return editorView.value.saveForLogout();
  return !active.isPending.value && !active.isError.value && exercise.value?.state !== 'ACTIVE';
}
async function prepareCopilot() {
  if (!writable.value || !credential.value || pending.value || unresolved.value || answeringClosed.value || ending.value || logoutBusy.value) return false;
  return editorView.value ? editorView.value.saveForLogout() : false;
}
async function prepareCommand() {
  const before = `${owner.value}/${exercise.value?.exerciseId}`;
  if (!await prepareCopilot() || before !== `${owner.value}/${exercise.value?.exerciseId}`) return null;
  await refreshWorkspace();
  return before === `${owner.value}/${exercise.value?.exerciseId}` ? exercise.value?.revision ?? null : null;
}
function restartEditor() { editorGeneration.value++; credential.value = ''; }
watch(() => account.data.value, (value) => {
  if (value !== undefined && saveLogoutOwner.value && saveLogoutOwner.value !== value?.id) cancelSaveLogout();
});
const credential = ref('');
const credentialGeneration = ref('');
const controlState = useQuery({ queryKey: ['workspace', owner, 'control', computed(() => exercise.value?.exerciseId)],
  queryFn: ({ signal }) => api.control(exercise.value!.exerciseId, null, signal),
  enabled: computed(() => allowed.value && !!exercise.value && ['READY', ...implementingStates].includes(exercise.value.state)), retry: false, refetchInterval: 2000 });
watch(() => controlState.data.value?.generation, (value) => {
  if (value && credential.value && value !== credentialGeneration.value) credential.value = '';
});
watch(() => exercise.value?.workspaceInstanceId, () => { selected.value = ''; credential.value = ''; testBusy.value = true; commandBusy.value = true; historyBusy.value = false; editorWriteBusy.value = false; });
const source = useQuery({ queryKey: ['workspace', owner, 'file', computed(() => exercise.value?.workspaceInstanceId), selected, computed(() => exercise.value?.revision)],
  queryFn: ({ signal }) => {
    if (!exercise.value) throw new Error('Workspace is not available');
    return api.file(exercise.value, selected.value, signal);
  }, enabled: computed(() => allowed.value && !!exercise.value && exercise.value.revision !== null && !!selected.value && exercise.value.paths.includes(selected.value)), retry: false, gcTime: 0 });
const canRecover = computed(() => allowed.value && !account.isError.value && !active.isError.value);
const writable = computed(() => canRecover.value && !controlState.isError.value);
const pending = ref(false);
const error = ref('');
const unresolved = ref<api.Action | null>(null);
let controller = new globalThis.AbortController();
let generation = 0;
let disposed = false;
watch(owner, (_, previous) => {
  generation++;
  logoutBusy.value = false;
  deletedExercise.value = '';
  cleanupFailed.value = '';
  if (previous) {
    lastExercise.value = undefined;
    void router.replace({ query: {} });
  }
  hasEditor.value = false;
  endPrompt.value = false;
  ending.value = false;
  controller.abort();
  controller = new globalThis.AbortController();
  unresolved.value = null;
  error.value = '';
  pending.value = false;
  credential.value = '';
}, { flush: 'sync' });
const cleanedDeletions = new Set<string>();
const cleaningDeletions = new Set<string>();
const cleanupFailed = ref('');
async function acceptedDeletion(id: string, user = owner.value) {
  if (!user || user !== owner.value) return;
  const key = `${user}:${id}`;
  if ((lastExercise.value === id || exercise.value?.exerciseId === id) && deletedExercise.value !== id) {
    generation++;
    pending.value = false;
    ending.value = false;
    unresolved.value = null;
    deletedExercise.value = id;
    hasEditor.value = false;
    selected.value = '';
    credential.value = '';
    endPrompt.value = false;
    controller.abort();
    controller = new globalThis.AbortController();
    await client.cancelQueries({ queryKey: ['workspace', user, 'current'] });
    for (const kind of ['file', 'control', 'tasks', 'tests', 'storage']) {
      await client.cancelQueries({ queryKey: ['workspace', user, kind] });
      client.removeQueries({ queryKey: ['workspace', user, kind] });
    }
  }
  if (cleanedDeletions.has(key) || cleaningDeletions.has(key)) return;
  cleaningDeletions.add(key);
  try {
    await confirmExerciseDeletion(user, id);
    cleanedDeletions.add(key);
    if (owner.value === user && cleanupFailed.value === id) cleanupFailed.value = '';
  } catch { if (!disposed && owner.value === user) cleanupFailed.value = id; }
  finally { cleaningDeletions.delete(key); }
}
watch(() => active.error.value, (failure) => {
  if (failure instanceof ApiError && failure.code === 'EXERCISE_DELETED' && lastExercise.value) void acceptedDeletion(lastExercise.value);
});

onScopeDispose(() => {
  disposed = true;
  controller.abort();
  void client.cancelQueries({ queryKey: ['workspace'] }).then(() => client.removeQueries({ queryKey: ['workspace'] }));
});
async function perform(action: api.Action) {
  if (pending.value || disposed || !allowed.value) return;
  const startedGeneration = generation;
  const startedOwner = owner.value;
  pending.value = true;
  error.value = '';
  unresolved.value = action;
  try {
    await api.act(action, controller.signal, credential.value || undefined, startedOwner);
    if (disposed || generation !== startedGeneration) return;
    unresolved.value = null;
    await client.invalidateQueries({ queryKey: ['workspace', startedOwner, 'current'] });
  } catch (failure) {
    if (disposed || generation !== startedGeneration) return;
    error.value = api.message(failure);
    if (failure instanceof ApiError && [400, 401, 403, 404, 409, 429].includes(failure.status)) unresolved.value = null;
    if (failure instanceof ApiError && failure.status === 401) client.setQueryData(['identity-session'], null);
  } finally { if (!disposed && generation === startedGeneration) pending.value = false; }
}
async function endExercise(choice: 'save' | 'discard' | 'retain') {
  if (ending.value || pending.value || unresolved.value || !canEnd.value || (choice === 'retain' ? !canRecover.value : answeringClosed.value || !writable.value || !credential.value)) return;
  const started = generation;
  const id = exercise.value?.exerciseId;
  ending.value = true;
  error.value = '';
  try {
    const prepared = editorView.value ? await editorView.value.prepareEnd(choice) : choice === 'retain';
    if (!prepared) {
      if (started === generation) error.value = '请先处理草稿或未确认的保存，再结束演练。';
      return;
    }
    if (disposed || started !== generation || id !== exercise.value?.exerciseId || !canEnd.value) return;
    endPrompt.value = false;
    await perform({ path: `/api/exercises/${encodeURIComponent(id!)}/${choice === 'retain' ? 'recovery-end' : 'end'}`, body: {}, key: globalThis.crypto.randomUUID() });
  } finally { if (started === generation) ending.value = false; }
}
async function pauseExercise() {
  if (ending.value || pending.value || unresolved.value || answeringClosed.value || !writable.value || !credential.value) return;
  const started = generation;
  const id = exercise.value?.exerciseId;
  ending.value = true;
  try {
    if (editorView.value && !await editorView.value.prepareEnd('retain')) {
      if (started === generation) error.value = '草稿备份未确认，请先保留草稿后再暂停。';
      return;
    }
    if (!disposed && started === generation && exercise.value?.exerciseId === id && !answeringClosed.value) change('pause');
  } catch {
    if (!disposed && started === generation) error.value = '草稿备份失败，本次未请求暂停，请先复制草稿。';
  } finally { if (started === generation) ending.value = false; }
}
function change(kind: 'start' | 'preparation-retry' | 'abandon' | 'pause' | 'resume') {
  if (exercise.value) void perform({ path: `/api/exercises/${encodeURIComponent(exercise.value.exerciseId)}/${kind}`, body: {}, key: globalThis.crypto.randomUUID() });
}
async function takeControl(kind: 'acquire' | 'takeover') {
  if (!exercise.value || pending.value) return;
  const started = generation;
  const id = exercise.value.exerciseId;
  pending.value = true;
  error.value = '';
  try {
    const result = await api.control(id, kind, controller.signal, globalThis.crypto.randomUUID());
    if (disposed || started !== generation || exercise.value?.exerciseId !== id) return;
    client.setQueryData(['workspace', owner.value, 'control', id], result);
    credentialGeneration.value = result.generation;
    credential.value = result.credential ?? '';
    if (!result.credential) error.value = '操作权转换尚未完成或凭证未交付。请刷新确认后显式接管。';
  } catch (failure) { if (!disposed && started === generation) error.value = api.message(failure); }
  finally { if (!disposed && started === generation) pending.value = false; }
}
async function refreshWorkspace() {
  await client.invalidateQueries({ queryKey: ['workspace', owner.value, 'current'] });
}
const failureReasons: Record<string, string> = {
  INVALID_PREPARATION_REQUEST: '准备请求不符合执行服务契约，请联系维护者。',
  PREPARATION_PRECONDITION_FAILED: '题目未在执行服务发布，或工作区准备条件已失效，请联系维护者核对。',
  PREPARATION_CANCELLED: '旧准备操作已取消。可用新实例重试，或放弃本次演练。',
  AUTHORIZATION_REVOKED: '发起准备的登录已撤销，请重新登录后重试。',
};
const labels: Record<string, string> = { PREPARING: '正在准备，结果未确认', READY: '已准备，仅可浏览', PREPARATION_FAILED: '准备失败，旧实例已封存',
  ACTIVE: '已开始，请显式保存修改', PAUSING: '正在暂停：核对在途操作，仍在计时', PAUSED: '已暂停：工作区只读', RESUMING: '正在恢复：等待服务器重新开放作答', CLOSING: '正在收尾', FINAL_TESTING: '正在最终验收', ENDED: '已结束' };
</script>

<template>
  <main
    class="workspace-page"
  >
    <header class="workspace-header">
      <h1>{{ exercise ? '演练工作区' : '当前演练' }}</h1>
      <div class="header-actions">
        <Button
          v-if="exercise && ['READY', 'ACTIVE'].includes(exercise.state)"
          variant="secondary"
          size="small"
          @click="modelOpen = true"
        >
          <Cpu
            :size="14"
            aria-hidden="true"
          />
          <span>Copilot 模型与检查</span>
        </Button>
        <Button
          variant="ghost"
          size="small"
          :disabled="pending || active.isFetching.value"
          @click="catalogue.refetch(); active.refetch(); exercise && controlState.refetch()"
        >
          <RefreshCw
            :size="14"
            aria-hidden="true"
          />
          <span>刷新查询</span>
        </Button>
      </div>
    </header>
    <p
      v-if="account.isPending.value"
      role="status"
      class="ws-banner"
    >
      正在检查登录状态…
    </p>
    <p
      v-else-if="account.isError.value"
      role="alert"
      class="ws-banner ws-banner-alert"
    >
      无法确认登录状态，请刷新页面重试。
    </p>
    <p
      v-else-if="!allowed"
      class="ws-banner"
    >
      请先登录并完成临时密码修改。
    </p>
    <template v-if="allowed">
      <WorkspaceLogout
        v-if="saveLogoutOwner === owner"
        :key="owner"
        :owner="owner"
        :exercise-id="exercise?.exerciseId"
        :save="saveForLogout"
        @select="selected = $event"
        @busy="logoutBusy = $event"
        @restart="restartEditor"
      />
      <p
        v-if="error"
        role="alert"
        class="failure ws-banner ws-banner-alert"
      >
        {{ error }}
      </p>
      <Button
        v-if="unresolved"
        size="small"
        :disabled="pending"
        @click="perform(unresolved)"
      >
        核对并重试原操作
      </Button>
      <p
        v-if="cleanupFailed"
        role="alert"
        class="ws-banner ws-banner-alert"
      >
        本地草稿清理失败，演练访问仍已撤销。
        <Button
          size="small"
          @click="acceptedDeletion(cleanupFailed)"
        >
          重试清理本地草稿
        </Button>
      </p>
      <p
        v-if="deletedExercise"
        role="status"
        class="ws-banner"
      >
        演练已删除，不能恢复作答；后台清理进度可继续查询。
      </p>
      <p
        v-if="catalogue.isError.value || active.isError.value && !deletedExercise"
        role="alert"
        class="failure ws-banner ws-banner-alert"
      >
        {{ api.message(catalogue.error.value ?? active.error.value) }} 先前数据不代表当前状态。
      </p>
      <p
        v-if="controlState.isError.value"
        role="alert"
        class="ws-banner ws-banner-alert"
      >
        无法确认操作权，已暂停写入。{{ api.message(controlState.error.value) }}
      </p>
      <p
        v-if="catalogue.isPending.value || active.isPending.value"
        role="status"
        class="ws-banner"
      >
        正在读取题目与当前演练…
      </p>
      <template v-else-if="exercise || (!catalogue.isError.value && !active.isError.value)">
        <section
          v-if="!exercise"
          class="workspace-empty"
        >
          <h2>还没有当前演练</h2>
          <p>从题库选择题目，准备好模型与环境后再开始计时。</p>
          <RouterLink
            class="ui-button ui-button-primary"
            :to="{ name: routes.catalog.name }"
          >前往题库选择题目</RouterLink>
        </section>
        <template v-if="exercise">
          <section
            class="exercise-summary"
            aria-label="演练状态"
          >
            <div class="summary-primary">
              <h2>{{ problem?.title ?? '固定版本演练' }}</h2>
              <p
                role="status"
                class="status-pill"
              >
                {{ labels[exercise.state] }} · 修订 {{ exercise.revision ?? '尚未确认' }}
              </p>
              <p
                v-if="['READY', ...implementingStates].includes(exercise.state)"
                role="status"
                class="control-pill"
                :data-held="!!credential"
              >
                <ShieldCheck
                  :size="13"
                  aria-hidden="true"
                />
                <span>{{ credential ? '当前页面持有操作权' : '当前页面只读，可保留本地草稿' }}</span>
              </p>
            </div>
            <div class="summary-controls">
              <p
                v-if="implementingStates.includes(exercise.state)"
                role="status"
                class="timer-pill"
              >
                <Clock
                  :size="13"
                  aria-hidden="true"
                />
                <span>{{ expired ? '作答已到期，等待服务器确认；本地草稿不会自动提交。' : `剩余时间 ${remaining}` }}</span>
              </p>
              <Button
                v-if="!credential && ['READY', 'ACTIVE', 'PAUSED'].includes(exercise.state)"
                variant="secondary"
                size="small"
                :disabled="pending || controlState.data.value?.state === 'PENDING'"
                @click="takeControl(controlState.data.value?.held ? 'takeover' : 'acquire')"
              >
                {{ controlState.data.value?.held ? '接管操作权' : '申请操作权' }}
              </Button>
              <Button
                v-if="exercise.state === 'READY'"
                variant="primary"
                size="small"
                :disabled="pending || !!unresolved || !credential"
                @click="change('start')"
              >
                确认开始计时
              </Button>
              <Button
                v-if="exercise.state === 'ACTIVE' && !expired"
                data-testid="pause-exercise"
                size="small"
                :disabled="pending || ending || !!unresolved || !credential || !writable || logoutBusy"
                @click="pauseExercise"
              >
                暂停演练
              </Button>
              <Button
                v-if="exercise.state === 'PAUSED'"
                data-testid="resume-exercise"
                variant="primary"
                size="small"
                :disabled="pending || ending || !!unresolved || !credential || !writable || logoutBusy"
                @click="change('resume')"
              >
                恢复演练
              </Button>
              <Button
                v-if="canEnd"
                data-testid="end-exercise"
                variant="danger"
                size="small"
                :disabled="pending || ending || !!unresolved || !canRecover || logoutBusy"
                @click="endPrompt = true"
              >
                结束演练
              </Button>
              <Button
                v-if="exercise.state === 'PREPARATION_FAILED'"
                size="small"
                :disabled="pending || !!unresolved"
                @click="change('preparation-retry')"
              >
                使用新实例重试准备
              </Button>
              <Button
                v-if="['READY', 'PREPARATION_FAILED'].includes(exercise.state)"
                variant="danger"
                size="small"
                :disabled="pending || !!unresolved"
                @click="change('abandon')"
              >
                放弃本次演练
              </Button>
            </div>
            <details class="exercise-metadata">
              <summary>版本与执行详情</summary>
              <div class="metadata-grid">
                <p
                  v-if="exercise.preparationFailureReason"
                  role="status"
                >
                  {{ failureReasons[exercise.preparationFailureReason] ?? '准备失败，请联系维护者核对。' }}
                </p>
                <p v-if="exercise.cleanupState">
                  旧实例清理：{{ exercise.cleanupState === 'SUCCEEDED' ? '已确认' : '等待确认' }}
                </p>
                <p v-if="exercise.deadlineAt">
                  服务器截止时间：{{ new Date(exercise.deadlineAt).toLocaleString() }}
                </p>
              </div>
            </details>
            <p
              v-if="['PAUSING', 'PAUSED', 'RESUMING'].includes(exercise.state)"
              class="summary-notice"
            >
              本地草稿保留，禁止新的作答和收费请求；已提交修改不回滚。恢复演练后，保留的 Copilot 队列仍须显式重新授权。
            </p>
            <p
              v-if="exercise.state === 'PREPARING'"
              class="summary-notice"
            >
              后台正在核对准备结果。结果未知时不能另开重试或放弃请求。
            </p>
            <section
              v-if="endPrompt && canEnd"
              class="end-confirm-panel"
              role="dialog"
              aria-modal="true"
              aria-label="结束演练确认"
            >
              <p>结束后不可继续作答。可先保存、明确丢弃，或只保留本地草稿而不提交新保存。保留草稿不需要本页操作权；最终验收使用服务器关闭并核对后的已提交版本。</p>
              <p
                v-if="testBusy"
                class="end-warning"
              >
                仍有测试运行或状态待核对，结束后将由服务器停止并核对，不会把未知结果视为已停止。
              </p>
              <div class="end-confirm-actions">
                <Button
                  variant="primary"
                  size="small"
                  :disabled="ending || pending || answeringClosed || !credential || !writable"
                  @click="endExercise('save')"
                >
                  保存后结束
                </Button>
                <Button
                  variant="danger"
                  size="small"
                  :disabled="ending || pending || answeringClosed || !credential || !writable"
                  @click="endExercise('discard')"
                >
                  丢弃并结束
                </Button>
                <Button
                  size="small"
                  :disabled="ending || pending || !canRecover"
                  @click="endExercise('retain')"
                >
                  保留草稿并结束
                </Button>
                <Button
                  variant="ghost"
                  size="small"
                  :disabled="ending || pending"
                  @click="endPrompt = false"
                >
                  返回编辑
                </Button>
              </div>
            </section>
            <section
              v-if="exercise.finalization"
              class="finalization-panel"
              aria-label="最终验收结果"
            >
              <p>最终冻结修订：<code>{{ exercise.finalization.finalRevision ?? '尚未确认，正在核对' }}</code></p>
              <p>{{ ({ PENDING: '最终验收待核对', PASSED: '最终验收通过', FAILED: '最终验收失败', TIMED_OUT: '最终验收超时', UNAVAILABLE: '最终验收结果不可用' })[exercise.finalization.verification] }}</p>
              <p>系统验收尝试 {{ exercise.finalization.attempts }} / {{ exercise.finalization.maxAttempts }}。</p>
              <RouterLink
                v-if="exercise.state === 'ENDED'"
                class="ui-button ui-button-primary ui-button-small"
                :to="{ name: routes.report.name, query: { exercise: exercise.exerciseId } }"
              >进入回顾与评价报告</RouterLink>
              <p v-if="exercise.finalization.status === 'RECONCILIATION_REQUIRED'">
                冻结事实未确认，不能虚构最终版本，请稍后刷新。
              </p>
            </section>
          </section>
          <p
            v-if="problem && !problem.executionAvailable"
            role="status"
            class="ws-banner"
          >
            这个固定题目版本尚未配备正式执行配置，测试不可用；不会替换已有工作区版本。
          </p>
          <Dialog
            v-if="['READY', 'ACTIVE'].includes(exercise.state)"
            v-model:open="modelOpen"
            persistent
            title="本场 Copilot 模型"
            description="选择连接与具体模型，显式应用到本次演练。面试官使用平台模型，不受此选择影响。"
          >
            <ExerciseModelPicker
              :key="owner + exercise.exerciseId"
              :owner="owner"
              :exercise-id="exercise.exerciseId"
              :credential="credential"
              :preparing="exercise.state === 'READY'"
              :disabled="!writable || expired || pending || !!unresolved"
              @unauthorized="credential = ''; account.refetch()"
            />
          </Dialog>
          <p
            v-if="challengeBlocked"
            role="alert"
            class="ws-banner ws-banner-alert"
          >
            挑战阶段尚未确认，暂不启动新的 AI 或测试请求；请刷新核对。
          </p>
          <ChallengePanel
            v-if="challenge.data.value?.available"
            :owner="owner"
            :exercise-id="exercise.exerciseId"
            :credential="credential"
            :state="challenge.data.value"
            :disabled="!writable || answeringClosed || pending || !!unresolved || ending || endPrompt || logoutBusy || historyBusy"
          />
          <WorkbenchLayout>
            <template #assistant>
              <div class="assistant-stack">
                <InterviewerPanel
                  v-if="['READY', ...historyStates].includes(exercise.state)"
                  :key="`interviewer:${owner}:${exercise.exerciseId}`"
                  :owner="owner"
                  :exercise-id="exercise.exerciseId"
                  :credential="credential"
                  :phase="exercise.state"
                  :disabled="challengeBlocked || !writable || !['READY', 'ACTIVE'].includes(exercise.state) || expired || pending || !!unresolved || ending || endPrompt || logoutBusy"
                  @unauthorized="credential = ''; account.refetch()"
                />
                <CopilotConversations
                  v-if="historyStates.includes(exercise.state)"
                  :key="owner + exercise.exerciseId"
                  :owner="owner"
                  :exercise-id="exercise.exerciseId"
                  :credential="credential"
                  :disabled="challengeBlocked || !editorView || !writable || answeringClosed || pending || !!unresolved || ending || endPrompt || logoutBusy || historyBusy"
                  :paths="exercise.paths"
                  :prepare="prepareCopilot"
                  @inspect-test="testView?.inspect($event)"
                  @changed="refreshWorkspace"
                  @unauthorized="credential = ''; account.refetch()"
                />
              </div>
            </template>
            <template #commands>
              <WorkspaceCommands
                v-if="historyStates.includes(exercise.state)"
                :key="owner + exercise.workspaceInstanceId"
                :owner="owner"
                :exercise="exercise"
                :credential="credential"
                :disabled="!editorView || !writable || answeringClosed || pending || !!unresolved || ending || endPrompt || logoutBusy || testBusy || historyBusy"
                :prepare="prepareCommand"
                @locked="commandBusy = $event"
                @changed="refreshWorkspace"
                @unauthorized="credential = ''; account.refetch()"
              />
              <WorkspaceHistory
                v-if="historyStates.includes(exercise.state)"
                :key="owner + exercise.workspaceInstanceId"
                :owner="owner"
                :exercise="exercise"
                :credential="credential"
                :disabled="!writable || answeringClosed || pending || !!unresolved || ending || endPrompt || logoutBusy || commandBusy || editorWriteBusy"
                @locked="historyBusy = $event"
                @changed="refreshWorkspace"
                @unauthorized="credential = ''; account.refetch()"
              />
            </template>
            <template #storage>
              <WorkspaceStorage
                :key="owner + exercise.exerciseId"
                :owner="owner"
                :exercise-id="exercise.exerciseId"
                @deleted="acceptedDeletion"
              />
            </template>
            <template #tests>
              <WorkspaceTests
                v-if="historyStates.includes(exercise.state)"
                :key="owner + exercise.workspaceInstanceId"
                ref="testView"
                :owner="owner"
                :exercise="exercise"
                :credential="writable && !logoutBusy && !answeringClosed && !ending ? credential : ''"
                :target="testTarget"
                :recovery-allowed="canRecover && !logoutBusy && !answeringClosed && !ending"
                @locked="testBusy = $event"
                @stale-control="credential = ''"
              />
              <ClosedDrafts
                v-if="answeringClosed && ['CLOSING', 'FINAL_TESTING', 'ENDED'].includes(exercise.state)"
                :key="owner + exercise.exerciseId"
                :owner="owner"
                :exercise-id="exercise.exerciseId"
              />
            </template>
            <template #files>
              <section
                class="files-pane"
                aria-label="题目与文件"
              >
                <div class="pane-block">
                  <div class="pane-heading">
                    <h2>题目要求</h2>
                    <Tooltip :text="`允许编辑：${problem?.editableRoots.join('、') ?? '尚未确认'}；pom.xml 与验收测试受保护。`">
                      <Button
                        variant="ghost"
                        size="small"
                      >
                        编辑范围说明
                      </Button>
                    </Tooltip>
                  </div>
                  <ProblemRequirements :source="problem?.requirements ?? ''" />
                </div>
                <div class="pane-block">
                  <h2>工作区文件</h2>
                  <p
                    v-if="!exercise.paths.length"
                    class="empty-files"
                  >
                    尚无已确认文件。
                  </p>
                  <ul class="file-list">
                    <li
                      v-for="path in exercise.paths"
                      :key="path"
                    >
                      <button
                        type="button"
                        data-testid="workspace-file"
                        :aria-pressed="selected === path"
                        @click="selected = path"
                      >
                        <FileCode2
                          :size="14"
                          aria-hidden="true"
                        />
                        <span>{{ path }}</span>
                      </button>
                    </li>
                  </ul>
                  <details
                    v-for="(content, path) in problem?.acceptanceFiles"
                    :key="path"
                    class="acceptance-file"
                  >
                    <summary>受保护验收测试：{{ path }}</summary>
                    <pre class="requirements">{{ content }}</pre>
                  </details>
                </div>
              </section>
            </template>
            <template #editor>
              <p
                v-if="source.isFetching.value"
                role="status"
                class="editor-status-msg"
              >
                正在读取文件…
              </p>
              <p
                v-if="source.isError.value"
                role="alert"
                class="editor-status-msg editor-status-err"
              >
                {{ api.message(source.error.value) }}
              </p>
              <Button
                v-if="source.isError.value"
                size="small"
                @click="source.refetch()"
              >
                重试文件读取
              </Button>
              <WorkspaceEditor
                v-if="hasEditor"
                :key="owner + exercise.workspaceInstanceId + editorGeneration"
                ref="editorView"
                :locked="logoutBusy || ending || historyBusy || pending || !!unresolved"
                :answering-closed="answeringClosed"
                :test-busy="challengeBlocked || testBusy || commandBusy || historyBusy || !problem?.executionAvailable"
                :test-target="testTarget"
                :owner="owner"
                :exercise="exercise"
                :file="source.data.value"
                :selected="selected"
                :credential="writable && !historyBusy ? credential : ''"
                @saved="refreshWorkspace"
                @write-busy="editorWriteBusy = $event"
                @run-test="!challengeBlocked && !commandBusy && !historyBusy && testView?.start($event)"
                @select="selected = $event"
                @stale-control="credential = ''"
              />
              <section
                v-if="!hasEditor || answeringClosed"
                class="readonly-source"
                aria-label="只读源码"
              >
                <h2>{{ selected || '选择文件查看源码' }}</h2>
                <p
                  v-if="selected && source.isPending.value"
                  role="status"
                >
                  正在读取已提交文件…
                </p>
                <p
                  v-else-if="source.isError.value"
                  role="alert"
                >
                  {{ api.message(source.error.value) }}
                </p>
                <pre
                  v-else-if="selected && source.data.value"
                  data-testid="source"
                  tabindex="0"
                >{{ source.data.value.content }}</pre>
                <p v-else>
                  这里不会显示准备中的半成品文件。
                </p>
              </section>
            </template>
          </WorkbenchLayout>
        </template>
      </template>
    </template>
  </main>
</template>

<style scoped>
.workspace-page {
  width: 100%;
  height: 100dvh;
  margin: 0;
  padding: 0 18px 18px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
}

.workspace-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 12px 0;
  flex-shrink: 0;
}

.workspace-header h1 {
  font-size: 14px;
  margin: 0;
  color: var(--oc-fg-muted);
  font-weight: 550;
  letter-spacing: -0.01em;
}

.header-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ws-banner {
  margin: 4px 0 8px;
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  font-size: 13px;
  color: var(--oc-fg-muted);
  flex-shrink: 0;
}

.ws-banner-alert {
  color: var(--oc-warning);
  background: var(--oc-warning-bg);
  border-color: var(--oc-warning-border);
}

/* HUD Status Card */
.exercise-summary {
  display: grid;
  gap: 10px;
  padding: 14px 18px;
  border: 1px solid var(--oc-border);
  border-radius: 12px;
  background:
    radial-gradient(circle at 0% 0%, rgba(160, 196, 165, 0.06), transparent 45%),
    var(--oc-surface);
  box-shadow: var(--oc-hairline-top), var(--oc-shadow-xs);
  margin-bottom: 12px;
  flex-shrink: 0;
}

.summary-primary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 12px;
}

.exercise-summary h2 {
  margin: 0;
  font-size: 17px;
  font-weight: 650;
  margin-right: auto;
  overflow-wrap: anywhere;
}

.status-pill,
.control-pill,
.timer-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 550;
}

.status-pill {
  background: var(--oc-accent-soft);
  border: 1px solid var(--oc-border-accent);
  color: var(--oc-accent);
}

.control-pill {
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  color: var(--oc-fg-muted);
}

.control-pill[data-held="true"] {
  color: var(--oc-fg);
  border-color: var(--oc-border-strong);
}

.timer-pill {
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border-strong);
  color: var(--oc-fg);
  font-variant-numeric: tabular-nums;
}

.summary-controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.exercise-metadata {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.exercise-metadata summary {
  cursor: pointer;
}

.metadata-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin-top: 8px;
}

.metadata-grid p {
  margin: 0;
}

.summary-notice {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.end-confirm-panel,
.finalization-panel {
  padding: 14px 16px;
  border-radius: 10px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border-strong);
  display: grid;
  gap: 10px;
}

.end-confirm-panel p,
.finalization-panel p {
  margin: 0;
  font-size: 13px;
  color: var(--oc-fg-muted);
  line-height: 1.6;
}

.end-warning {
  color: var(--oc-warning) !important;
}

.end-confirm-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.finalization-panel a {
  width: fit-content;
  text-decoration: none;
}

.workspace-empty {
  margin: auto;
  text-align: center;
  padding: 48px 32px;
  max-width: 480px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
  box-shadow: var(--oc-shadow-sm), var(--oc-hairline-top);
  color: var(--oc-fg-muted);
}

.workspace-empty h2 {
  margin: 0 0 8px;
  font-size: 20px;
}

.workspace-empty p {
  margin: 0 0 20px;
  font-size: 14px;
  line-height: 1.6;
}

.workspace-empty a {
  text-decoration: none;
}

.files-pane,
.assistant-stack {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 20px;
}

.pane-block {
  display: grid;
  gap: 10px;
}

.pane-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--oc-border);
}

.pane-block h2 {
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
  margin: 0;
}

.empty-files {
  margin: 0;
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.file-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 4px;
}

.file-list button {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  text-align: left;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--oc-fg-muted);
  font-family: var(--oc-font-mono);
  font-size: 12.5px;
  line-height: 1.45;
  overflow-wrap: anywhere;
  cursor: pointer;
  transition:
    background-color 150ms var(--oc-ease),
    border-color 150ms var(--oc-ease),
    color 150ms var(--oc-ease);
}

.file-list button svg {
  margin-top: 2px;
  flex-shrink: 0;
}

.file-list button:hover {
  background: rgba(255, 255, 255, 0.045);
  color: var(--oc-fg);
}

.file-list button[aria-pressed="true"] {
  background: var(--oc-selected);
  border-color: var(--oc-border-accent);
  color: var(--oc-fg);
  font-weight: 550;
}

.file-list button[aria-pressed="true"] svg {
  color: var(--oc-accent);
}

.acceptance-file {
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.acceptance-file summary {
  cursor: pointer;
}

.workspace-page section {
  min-width: 0;
}

pre {
  font-family: var(--oc-font-mono);
  font-size: 13px;
  line-height: 1.65;
  overflow: auto;
  max-height: 65vh;
  padding: 12px;
  border-radius: 8px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
}

.requirements {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.editor-status-msg {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.editor-status-err {
  color: var(--oc-danger);
}

.readonly-source {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--oc-border);
}

.readonly-source h2 {
  margin: 0 0 10px;
  font-size: 14px;
  font-family: var(--oc-font-mono);
}

@media (max-width: 1100px) {
  .workspace-page {
    height: auto;
    min-height: 100dvh;
    overflow: visible;
    padding: 0 12px 24px;
  }
  .exercise-summary {
    max-height: none;
  }
  .workspace-header {
    flex-wrap: wrap;
    padding: 10px 4px;
  }
}
</style>
