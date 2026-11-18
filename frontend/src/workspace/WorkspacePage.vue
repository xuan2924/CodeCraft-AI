<script setup lang="ts">
import { computed, defineAsyncComponent, onScopeDispose, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { ArrowLeft, Clock, Cpu, Info, PanelLeftClose, PanelLeftOpen, RefreshCw, ShieldCheck } from '@lucide/vue';
import { session } from '../auth/api';
import { routes } from '../routes';
import { ApiError } from '../api/request';
import * as api from './api';
import { formatShortTimestamp } from '../copilot/feeConsent';
import WorkspaceLogout from './WorkspaceLogout.vue';
import WorkbenchLayout from './WorkbenchLayout.vue';
import CodeEditor from './CodeEditor.vue';
import FileTree from './FileTree.vue';
import WorkspaceTests from './WorkspaceTests.vue';
import WorkspaceCommands from './WorkspaceCommands.vue';
import InteractivePtyTerminal from './InteractivePtyTerminal.vue';
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
import Popover from '../components/ui/Popover.vue';
import SegmentedTabs from '../components/ui/SegmentedTabs.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import { cancelSaveLogout, saveLogoutOwner } from './logoutIntent';
import { confirmExerciseDeletion } from './drafts';
import { useExerciseTimer } from './useExerciseTimer';

const modelOpen = ref(false);
const leftCollapsed = ref(false);
const workbenchRef = ref<{ openTool: (tab: 'commands' | 'tests' | 'history' | 'storage') => void } | null>(null);
const leftTab = ref<'files' | 'requirements' | 'acceptance'>('files');
const assistantTab = ref<'copilot' | 'btw' | 'interviewer'>('copilot');
const headerActionsTarget = ref<globalThis.HTMLElement | null>(null);

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
const boundProblemId = computed(() => exercise.value?.problemVersionId ?? '');
const boundProblem = useQuery({
  queryKey: ['workspace', owner, 'problem', boundProblemId],
  queryFn: ({ signal }) => api.problem(boundProblemId.value, signal),
  enabled: computed(() => allowed.value && !!boundProblemId.value && !!catalogue.data.value
    && !catalogue.data.value.some((item) => item.problemVersionId === boundProblemId.value)),
  retry: false,
});
const problem = computed(() => catalogue.data.value?.find((item) => item.problemVersionId === exercise.value?.problemVersionId) ?? boundProblem.data.value);
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
watch(() => exercise.value?.state, (state, previous) => {
  if (state && implementingStates.includes(state)) hasEditor.value = true;
  if (state === 'READY') assistantTab.value = 'interviewer';
  else if (state && historyStates.includes(state) && (!previous || previous === 'READY')) assistantTab.value = 'copilot';
}, { immediate: true });
watch(expired, (value) => { if (value) { endPrompt.value = false; void active.refetch(); } });
const logoutBusy = ref(false);
const testView = ref<{ start: (revision: string) => void; inspect: (taskId: string) => void }>();
const testBusy = ref(true);
const singleCommandBusy = ref(true);
const ptyBusy = ref(false);
const commandBusy = computed(() => singleCommandBusy.value || ptyBusy.value);
const terminalMode = ref<'command' | 'pty'>('command');
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
watch(() => exercise.value?.workspaceInstanceId, () => { selected.value = ''; credential.value = ''; testBusy.value = true; singleCommandBusy.value = true; ptyBusy.value = false; historyBusy.value = false; editorWriteBusy.value = false; });
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
  ACTIVE: '进行中', PAUSING: '正在暂停：核对在途操作，仍在计时', PAUSED: '已暂停', RESUMING: '正在恢复', CLOSING: '正在收尾', FINAL_TESTING: '正在最终验收', ENDED: '已结束' };

const leftTabItems = computed(() => {
  const items: Array<{ id: string; label: string; badge?: string }> = [
    { id: 'files', label: '文件' },
    { id: 'requirements', label: '题目', badge: challenge.data.value?.available ? (challenge.data.value.entered ? '挑战' : '可选') : undefined },
  ];
  if (Object.keys(problem.value?.acceptanceFiles ?? {}).length) {
    items.push({ id: 'acceptance', label: '验收' });
  }
  return items;
});

const assistantTabItems = computed(() => {
  if (!exercise.value) return [];
  if (historyStates.includes(exercise.value.state)) {
    return [
      { id: 'copilot', label: '对话' },
      { id: 'btw', label: 'BTW 旁支' },
      { id: 'interviewer', label: '面试官澄清' },
    ];
  }
  return [{ id: 'interviewer', label: '面试官澄清' }];
});
</script>

<template>
  <main class="workspace-page">
    <header
      v-if="!exercise"
      class="workspace-topbar"
    >
      <div class="topbar-left">
        <RouterLink
          :to="{ name: routes.catalog.name }"
          class="topbar-back"
          aria-label="返回题库"
        >
          <ArrowLeft
            :size="15"
            aria-hidden="true"
          />
        </RouterLink>
        <h1 class="topbar-title">当前演练</h1>
      </div>
      <div class="topbar-right">
        <Button
          variant="ghost"
          size="icon"
          title="刷新查询"
          :disabled="pending || active.isFetching.value"
          @click="catalogue.refetch(); active.refetch()"
        >
          <RefreshCw
            :size="13"
            aria-hidden="true"
          />
          <span class="sr-only-text">刷新查询</span>
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
      请先登录并完成账号初始化。
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
      <div
        v-if="unresolved"
        class="ws-banner-action"
      >
        <Button
          size="small"
          :disabled="pending"
          @click="perform(unresolved)"
        >
          核对并重试原操作
        </Button>
      </div>
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
        正在读取演练…
      </p>

      <template v-else-if="exercise || (!catalogue.isError.value && !active.isError.value)">
        <section
          v-if="!exercise"
          class="workspace-empty"
        >
          <StatePanel
            title="还没有当前演练"
            description="从题库选择题目并检查环境后开始。"
          >
            <RouterLink
              class="ui-button ui-button-primary"
              :to="{ name: routes.catalog.name }"
            >前往题库选择题目</RouterLink>
          </StatePanel>
        </section>

        <template v-if="exercise">
          <header
            class="workspace-topbar"
            aria-label="演练状态"
          >
            <div class="topbar-left">
              <RouterLink
                :to="{ name: routes.catalog.name }"
                class="topbar-back"
                title="返回题库"
                aria-label="返回题库"
              >
                <ArrowLeft
                  :size="15"
                  aria-hidden="true"
                />
              </RouterLink>
              <h1 class="topbar-title">
                {{ problem?.title ?? '固定版本演练' }}
              </h1>
              <span
                v-if="problem?.version"
                class="topbar-version font-mono-num"
              >v{{ problem.version }}</span>
              <span
                role="status"
                class="topbar-pill font-mono-num"
              >
                {{ labels[exercise.state] }} · 修订 {{ exercise.revision ?? '尚未确认' }}
              </span>
              <span
                v-if="['READY', ...implementingStates].includes(exercise.state)"
                role="status"
                class="topbar-pill topbar-control-pill"
                :data-held="!!credential"
              >
                <ShieldCheck
                  :size="12"
                  aria-hidden="true"
                />
                <span>{{ credential ? '持有操作权' : '只读草稿' }}</span>
              </span>
              <span
                v-if="problem && !problem.executionAvailable"
                class="topbar-pill is-warn"
                role="status"
              >
                <span
                  class="ui-status-dot"
                  data-tone="warning"
                />
                <span>无执行环境</span>
              </span>
              <Popover label="演练详情">
                <template #trigger>
                  <Button
                    variant="ghost"
                    size="icon"
                    class="topbar-icon-btn"
                    aria-label="演练详情"
                    title="演练详情"
                  >
                    <Info
                      :size="13"
                      aria-hidden="true"
                    />
                  </Button>
                </template>
                <div class="meta-popover-body">
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
                    截止时间：{{ formatShortTimestamp(exercise.deadlineAt) }}
                  </p>
                  <p class="font-mono-num">
                    修订：{{ exercise.revision ?? '尚未确认' }}
                  </p>
                </div>
              </Popover>
            </div>

            <div class="topbar-right">
              <span
                v-if="implementingStates.includes(exercise.state)"
                role="status"
                class="topbar-timer font-mono-num"
              >
                <Clock
                  :size="12"
                  aria-hidden="true"
                />
                <span class="sr-only-text">剩余时间 </span><span>{{ expired ? '作答已到期' : remaining }}</span>
              </span>
              <Button
                variant="ghost"
                size="icon"
                class="topbar-icon-btn"
                aria-label="切换左侧面板"
                title="显示或隐藏左侧面板"
                :aria-pressed="leftCollapsed"
                @click="leftCollapsed = !leftCollapsed"
              >
                <component
                  :is="leftCollapsed ? PanelLeftOpen : PanelLeftClose"
                  :size="14"
                  aria-hidden="true"
                />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                class="topbar-icon-btn"
                title="刷新查询"
                :disabled="pending || active.isFetching.value"
                @click="catalogue.refetch(); active.refetch(); exercise && controlState.refetch()"
              >
                <RefreshCw
                  :size="13"
                  aria-hidden="true"
                />
                <span class="sr-only-text">刷新查询</span>
              </Button>
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
                variant="secondary"
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
          </header>

          <p
            v-if="['PAUSING', 'PAUSED', 'RESUMING'].includes(exercise.state)"
            class="ws-state-strip"
          >
            演练处于{{ labels[exercise.state] }}；本地草稿保留，暂停写入与 AI 请求。
          </p>
          <p
            v-if="exercise.state === 'PREPARING'"
            class="ws-state-strip"
          >
            正在准备运行实例，请稍候。
          </p>
          <p
            v-if="challengeBlocked"
            role="alert"
            class="ws-banner ws-banner-alert"
          >
            挑战阶段状态待核对，暂不发起新请求；请刷新。
          </p>
          <section
            v-if="exercise.finalization"
            class="finalization-strip"
            aria-label="最终验收结果"
          >
            <span>冻结修订：<code>{{ exercise.finalization.finalRevision ?? '尚未确认' }}</code></span>
            <span>{{ ({ PENDING: '最终验收待核对', PASSED: '最终验收通过', FAILED: '最终验收失败', TIMED_OUT: '最终验收超时', UNAVAILABLE: '最终验收结果不可用' })[exercise.finalization.verification] }}</span>
            <span class="font-mono-num">{{ exercise.finalization.attempts }} / {{ exercise.finalization.maxAttempts }}</span>
            <span v-if="exercise.finalization.status === 'RECONCILIATION_REQUIRED'">
              冻结事实待确认，请稍后刷新。
            </span>
            <RouterLink
              v-if="exercise.state === 'ENDED'"
              class="ui-button ui-button-primary ui-button-small"
              :to="{ name: routes.report.name, query: { exercise: exercise.exerciseId } }"
            >进入回顾与评价报告</RouterLink>
          </section>

          <div
            v-if="endPrompt && canEnd"
            class="end-confirm-backdrop"
          >
            <section
              class="end-confirm-modal"
              role="dialog"
              aria-modal="true"
              aria-label="结束演练确认"
            >
              <h2>结束演练</h2>
              <p>结束后不可继续作答；最终验收以服务端关闭并核对后的已保存版本为准。</p>
              <p
                v-if="testBusy"
                class="end-warning"
              >
                仍有测试正在运行或待核对，结束后将由服务端统一停止。
              </p>
              <div class="end-confirm-actions">
                <Button
                  variant="ghost"
                  size="small"
                  :disabled="ending || pending"
                  @click="endPrompt = false"
                >
                  返回编辑
                </Button>
                <Button
                  size="small"
                  :disabled="ending || pending || !canRecover"
                  @click="endExercise('retain')"
                >
                  保留草稿并结束
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
                  variant="primary"
                  size="small"
                  :disabled="ending || pending || answeringClosed || !credential || !writable"
                  @click="endExercise('save')"
                >
                  保存后结束
                </Button>
              </div>
            </section>
          </div>

          <Dialog
            v-if="['READY', 'ACTIVE'].includes(exercise.state)"
            v-model:open="modelOpen"
            persistent
            title="本场 Copilot 模型"
            description="选择连接与具体模型并应用到本次演练。面试官使用平台模型，不受影响。"
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

          <WorkbenchLayout
            ref="workbenchRef"
            :left-hidden="leftCollapsed"
          >
            <template #files>
              <section
                class="left-rail"
                aria-label="题目与文件"
              >
                <div class="left-rail-header">
                  <SegmentedTabs
                    :model-value="leftTab"
                    :items="leftTabItems"
                    label="工作区侧栏"
                    size="compact"
                    @update:model-value="(val) => (leftTab = val as 'files' | 'requirements' | 'acceptance')"
                  />
                </div>
                <div class="left-rail-body">
                  <div
                    v-show="leftTab === 'files'"
                    class="rail-panel"
                  >
                    <div class="files-subbar">
                      <span>工作区文件</span>
                      <Tooltip :text="`允许编辑：${problem?.editableRoots.join('、') ?? '尚未确认'}；构建脚本与验收测试受保护。`">
                        <button
                          type="button"
                          class="scope-info-btn"
                          aria-label="编辑范围说明"
                        >
                          ?
                        </button>
                      </Tooltip>
                    </div>
                    <FileTree
                      v-if="exercise.paths.length"
                      :paths="exercise.paths"
                      :selected="selected"
                      @select="selected = $event"
                    />
                    <p
                      v-else
                      class="empty-quiet"
                    >
                      工作区文件尚未就绪。
                    </p>
                  </div>
                  <div
                    v-if="leftTab === 'requirements'"
                    class="rail-panel rail-prose"
                  >
                    <ProblemRequirements
                      :source="problem?.requirements ?? ''"
                      :problem-title="problem?.title"
                    />
                    <ChallengePanel
                      v-if="challenge.data.value?.available"
                      :owner="owner"
                      :exercise-id="exercise.exerciseId"
                      :credential="credential"
                      :state="challenge.data.value"
                      :disabled="!writable || answeringClosed || pending || !!unresolved || ending || endPrompt || logoutBusy || historyBusy"
                    />
                  </div>
                  <div
                    v-if="leftTab === 'acceptance'"
                    class="rail-panel rail-acceptance"
                  >
                    <div
                      v-for="(content, path) in problem?.acceptanceFiles"
                      :key="path"
                      class="acceptance-file"
                    >
                      <div class="acceptance-path font-mono-num">
                        {{ path }}
                      </div>
                      <pre class="acceptance-code">{{ content }}</pre>
                    </div>
                  </div>
                </div>
              </section>
            </template>

            <template #editor>
              <div
                v-if="source.isFetching.value || source.isError.value"
                class="editor-fetch-bar"
              >
                <span
                  v-if="source.isFetching.value"
                  role="status"
                  class="editor-status-msg"
                >
                  正在读取文件…
                </span>
                <span
                  v-if="source.isError.value"
                  role="alert"
                  class="editor-status-msg editor-status-err"
                >
                  {{ api.message(source.error.value) }}
                </span>
                <Button
                  v-if="source.isError.value"
                  size="small"
                  @click="source.refetch()"
                >
                  重试文件读取
                </Button>
              </div>
              <WorkspaceEditor
                v-if="hasEditor"
                :key="owner + exercise.workspaceInstanceId + editorGeneration"
                ref="editorView"
                :locked="logoutBusy || ending || historyBusy || pending || !!unresolved"
                :answering-closed="answeringClosed"
                :test-busy="challengeBlocked || testBusy || commandBusy || historyBusy || !problem?.executionAvailable || problem?.executionSupported === false"
                :test-target="testTarget"
                :owner="owner"
                :exercise="exercise"
                :file="source.data.value"
                :selected="selected"
                :editable-roots="problem?.editableRoots"
                :credential="writable && !historyBusy ? credential : ''"
                @saved="refreshWorkspace"
                @write-busy="editorWriteBusy = $event"
                @run-test="if (!challengeBlocked && !commandBusy && !historyBusy) { workbenchRef?.openTool('tests'); testView?.start($event); }"
                @select="selected = $event"
                @stale-control="credential = ''"
              />
              <section
                v-if="!hasEditor || answeringClosed"
                class="readonly-source"
                aria-label="只读源码"
              >
                <div class="readonly-source-bar">
                  <span class="font-mono-num">{{ selected || '选择文件查看源码' }}</span>
                  <span
                    v-if="selected"
                    class="readonly-tag"
                  >只读</span>
                </div>
                <p
                  v-if="selected && source.isPending.value"
                  role="status"
                  class="readonly-msg"
                >
                  正在读取文件…
                </p>
                <p
                  v-else-if="source.isError.value"
                  role="alert"
                  class="readonly-msg"
                >
                  {{ api.message(source.error.value) }}
                </p>
                <CodeEditor
                  v-else-if="selected && source.data.value"
                  :path="selected"
                  :model-value="source.data.value.content"
                  :readonly="true"
                />
                <p
                  v-else
                  class="readonly-msg"
                >
                  从左侧选择一个文件以只读查看源码。
                </p>
              </section>
            </template>

            <template #commands>
              <div
                v-if="historyStates.includes(exercise.state)"
                class="terminal-mode-deck"
              >
                <div
                  class="terminal-mode-tabs"
                  role="tablist"
                  aria-label="终端模式切换"
                >
                  <button
                    type="button"
                    role="tab"
                    :aria-selected="terminalMode === 'command'"
                    class="terminal-mode-tab"
                    :class="{ 'is-active': terminalMode === 'command' }"
                    @click="terminalMode = 'command'"
                  >
                    单次命令与历史
                  </button>
                  <button
                    type="button"
                    role="tab"
                    :aria-selected="terminalMode === 'pty'"
                    class="terminal-mode-tab"
                    :class="{ 'is-active': terminalMode === 'pty' }"
                    @click="terminalMode = 'pty'"
                  >
                    <span
                      v-if="ptyBusy"
                      class="terminal-mode-live"
                      aria-hidden="true"
                    />
                    实时交互终端 (Dev PTY)
                  </button>
                </div>
                <WorkspaceCommands
                  v-show="terminalMode === 'command'"
                  :key="`cmd-${owner}-${exercise.workspaceInstanceId}`"
                  :owner="owner"
                  :exercise="exercise"
                  :credential="credential"
                  :disabled="!editorView || !writable || answeringClosed || pending || !!unresolved || ending || endPrompt || logoutBusy || testBusy || historyBusy || ptyBusy"
                  :prepare="prepareCommand"
                  @locked="singleCommandBusy = $event"
                  @changed="refreshWorkspace"
                  @unauthorized="credential = ''; account.refetch()"
                />
                <InteractivePtyTerminal
                  v-show="terminalMode === 'pty'"
                  :key="`pty-${owner}-${exercise.workspaceInstanceId}`"
                  :owner="owner"
                  :exercise="exercise"
                  :credential="credential"
                  :disabled="!editorView || !writable || answeringClosed || pending || !!unresolved || ending || endPrompt || logoutBusy || testBusy || historyBusy || singleCommandBusy"
                  @locked="ptyBusy = $event"
                  @changed="refreshWorkspace"
                  @unauthorized="credential = ''; account.refetch()"
                />
              </div>
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

            <template #history>
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

            <template #assistant>
              <div class="assistant-shell">
                <div class="assistant-topbar">
                  <SegmentedTabs
                    :model-value="assistantTab"
                    :items="assistantTabItems"
                    label="AI 协作切换"
                    size="compact"
                    @update:model-value="(val) => (assistantTab = val as 'copilot' | 'btw' | 'interviewer')"
                  />
                  <div class="assistant-topbar-right">
                    <div
                      v-show="assistantTab !== 'interviewer'"
                      ref="headerActionsTarget"
                      class="assistant-header-slot"
                    />
                    <Button
                      v-if="['READY', 'ACTIVE'].includes(exercise.state)"
                      variant="ghost"
                      size="icon"
                      class="topbar-icon-btn"
                      aria-label="模型设置"
                      title="配置或检查本场 Copilot 模型"
                      @click="modelOpen = true"
                    >
                      <Cpu
                        :size="14"
                        aria-hidden="true"
                      />
                    </Button>
                  </div>
                </div>
                <div class="assistant-body">
                  <div
                    v-if="historyStates.includes(exercise.state)"
                    v-show="assistantTab !== 'interviewer'"
                    class="assistant-panel-slot"
                  >
                    <CopilotConversations
                      :key="owner + exercise.exerciseId"
                      :owner="owner"
                      :exercise-id="exercise.exerciseId"
                      :credential="credential"
                      :disabled="challengeBlocked || !editorView || !writable || answeringClosed || pending || !!unresolved || ending || endPrompt || logoutBusy || historyBusy"
                      :paths="exercise.paths"
                      :prepare="prepareCopilot"
                      :subview="assistantTab === 'btw' ? 'btw' : 'chat'"
                      :header-target="headerActionsTarget"
                      @update:subview="(v) => (assistantTab = v === 'btw' ? 'btw' : 'copilot')"
                      @inspect-test="workbenchRef?.openTool('tests'); testView?.inspect($event)"
                      @changed="refreshWorkspace"
                      @unauthorized="credential = ''; account.refetch()"
                    />
                  </div>
                  <div
                    v-if="['READY', ...historyStates].includes(exercise.state)"
                    v-show="assistantTab === 'interviewer' || !historyStates.includes(exercise.state)"
                    class="assistant-panel-slot"
                  >
                    <InterviewerPanel
                      :key="`interviewer:${owner}:${exercise.exerciseId}`"
                      :owner="owner"
                      :exercise-id="exercise.exerciseId"
                      :credential="credential"
                      :phase="exercise.state"
                      :stage="challenge.data.value?.entered ? 'CHALLENGE' : 'BASE'"
                      :disabled="challengeBlocked || !writable || !['READY', 'ACTIVE'].includes(exercise.state) || expired || pending || !!unresolved || ending || endPrompt || logoutBusy"
                      @unauthorized="credential = ''; account.refetch()"
                    />
                  </div>
                </div>
              </div>
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
  padding: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
  background: var(--oc-bg);
}

.workspace-topbar {
  flex-shrink: 0;
  min-height: 42px;
  padding: 4px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  background: var(--oc-bg-subtle);
  border-bottom: 1px solid var(--oc-border);
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1 1 280px;
}

.topbar-back {
  width: 26px;
  height: 26px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  color: var(--oc-fg-muted);
  text-decoration: none;
  flex-shrink: 0;
}

.topbar-back:hover {
  background: var(--oc-surface-2);
  color: var(--oc-fg);
}

.topbar-title {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--oc-fg);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar-version {
  font-size: 11.5px;
  color: var(--oc-fg-dim);
  flex-shrink: 0;
}

.topbar-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.05);
  color: var(--oc-fg-muted);
  font-size: 11.5px;
  white-space: nowrap;
  flex-shrink: 0;
}

.topbar-control-pill[data-held="true"] {
  color: var(--oc-accent);
  background: var(--oc-accent-bg);
}

.topbar-pill.is-warn {
  color: var(--oc-warning);
  background: var(--oc-warning-bg);
}

.topbar-icon-btn {
  width: 26px;
  height: 26px;
  min-height: 26px;
  padding: 0;
}

.meta-popover-body {
  display: grid;
  gap: 6px;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.meta-popover-body p {
  margin: 0;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 1;
  max-width: 100%;
  flex-wrap: wrap;
}

.topbar-timer {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  border-radius: 6px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  font-size: 12px;
  color: var(--oc-fg);
  white-space: nowrap;
}

.sr-only-text {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}

.ws-banner {
  flex-shrink: 0;
  margin: 0;
  padding: 6px 12px;
  font-size: 12px;
  color: var(--oc-fg-muted);
  background: var(--oc-surface);
  border-bottom: 1px solid var(--oc-border);
}

.ws-banner-alert {
  color: var(--oc-danger);
  background: var(--oc-danger-bg);
  border-color: var(--oc-danger-border);
}

.ws-banner-action {
  flex-shrink: 0;
  padding: 6px 12px;
  background: var(--oc-surface);
  border-bottom: 1px solid var(--oc-border);
}

.ws-state-strip,
.finalization-strip {
  flex-shrink: 0;
  margin: 0;
  padding: 6px 12px;
  font-size: 12px;
  color: var(--oc-fg-muted);
  background: var(--oc-surface);
  border-bottom: 1px solid var(--oc-border);
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.finalization-strip a {
  text-decoration: none;
  margin-left: auto;
}

.workspace-empty {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 24px;
}

.workspace-empty a {
  text-decoration: none;
}

.end-confirm-backdrop {
  position: fixed;
  inset: 0;
  z-index: 90;
  background: rgba(9, 9, 11, 0.72);
  display: grid;
  place-items: center;
  padding: 16px;
}

.end-confirm-modal {
  width: min(460px, 100%);
  padding: 20px;
  border-radius: var(--radius-panel);
  border: 1px solid var(--oc-border-strong);
  background: var(--oc-surface-2);
  box-shadow: var(--oc-shadow-lg);
  display: grid;
  gap: 10px;
}

.end-confirm-modal h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.end-confirm-modal p {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
  line-height: 1.55;
}

.end-warning {
  color: var(--oc-warning) !important;
}

.end-confirm-actions {
  margin-top: 6px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.left-rail {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.left-rail-header {
  flex-shrink: 0;
  height: 38px;
  padding: 0 10px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--oc-border);
}

.left-rail-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.rail-panel {
  min-width: 0;
  padding: 8px 10px;
}

.rail-prose {
  padding: 12px;
}

.files-subbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px 4px 6px;
  font-size: 11.5px;
  font-weight: 500;
  color: var(--oc-fg-dim);
}

.scope-info-btn {
  width: 18px;
  height: 18px;
  border-radius: 999px;
  border: 1px solid var(--oc-border);
  background: transparent;
  color: var(--oc-fg-dim);
  font-size: 11px;
  cursor: help;
}

.empty-quiet {
  margin: 12px 4px;
  font-size: 12px;
  color: var(--oc-fg-dim);
}

.rail-acceptance {
  display: grid;
  gap: 10px;
}

.acceptance-file {
  display: grid;
  gap: 4px;
}

.acceptance-path {
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}

.acceptance-code {
  margin: 0;
  padding: 8px 10px;
  border-radius: var(--radius-control);
  background: var(--oc-bg);
  border: 1px solid var(--oc-border);
  font-size: 12px;
  line-height: 1.5;
  overflow-x: auto;
  white-space: pre-wrap;
  overflow-wrap: break-word;
}

.editor-fetch-bar {
  flex-shrink: 0;
  padding: 4px 10px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  border-bottom: 1px solid var(--oc-border);
}

.editor-status-msg {
  color: var(--oc-fg-muted);
}

.editor-status-err {
  color: var(--oc-danger);
}

.readonly-source {
  flex: 1 1 0%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--oc-bg);
}

.readonly-source-bar {
  flex-shrink: 0;
  height: 32px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--oc-fg-muted);
  border-bottom: 1px solid var(--oc-border);
}

.readonly-tag {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.06);
}

.readonly-msg {
  padding: 14px;
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.assistant-shell {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.assistant-topbar {
  flex-shrink: 0;
  min-height: 40px;
  padding: 4px 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  border-bottom: 1px solid var(--oc-border);
}

.assistant-topbar-right {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.assistant-header-slot {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.assistant-body {
  flex: 1 1 0%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.assistant-panel-slot {
  flex: 1 1 0%;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

@media (max-width: 1100px) {
  .workspace-page {
    height: auto;
    min-height: 100dvh;
    overflow-x: hidden;
    overflow-y: visible;
  }

  .left-rail {
    height: auto;
    max-height: 340px;
  }

  .assistant-shell {
    height: 100%;
    min-height: 460px;
  }
}

.terminal-mode-deck {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.terminal-mode-tabs {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 4px 10px 0;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-bg);
}

.terminal-mode-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px 6px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--oc-fg-muted);
  font-size: 11.5px;
  cursor: pointer;
}

.terminal-mode-tab:hover {
  color: var(--oc-fg);
}

.terminal-mode-tab.is-active {
  color: var(--oc-fg);
  border-bottom-color: var(--oc-accent);
  font-weight: 500;
}

.terminal-mode-live {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--oc-accent);
}
</style>
