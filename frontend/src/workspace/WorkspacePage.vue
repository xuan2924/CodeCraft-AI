<script setup lang="ts">
import { computed, defineAsyncComponent, onScopeDispose, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { NButton, useThemeVars } from 'naive-ui';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { session } from '../auth/api';
import { ApiError } from '../api/request';
import * as api from './api';
import WorkspaceLogout from './WorkspaceLogout.vue';
import WorkspaceTests from './WorkspaceTests.vue';
import WorkspaceCommands from './WorkspaceCommands.vue';
import WorkspaceStorage from './WorkspaceStorage.vue';
import ExerciseModelPicker from '../modelaccess/ExerciseModelPicker.vue';
import CopilotPanel from '../copilot/CopilotConversations.vue';
import ClosedDrafts from './ClosedDrafts.vue';
import WorkspaceRetention from './WorkspaceRetention.vue';
import { cancelSaveLogout, saveLogoutOwner } from './logoutIntent';
import { confirmExerciseDeletion } from './drafts';

const WorkspaceEditor = defineAsyncComponent(() => import('./WorkspaceEditor.vue'));
const theme = useThemeVars();
const client = useQueryClient();
const account = useQuery({ queryKey: ['identity-session'], queryFn: ({ signal }) => session(signal), retry: false, gcTime: 0 });
const allowed = computed(() => !!account.data.value && !account.data.value.passwordChangeRequired &&
  !(account.error.value instanceof ApiError && [401, 403].includes(account.error.value.status)));
const owner = computed(() => account.data.value?.id ?? '');
const catalogue = useQuery({ queryKey: ['workspace', owner, 'problems'], queryFn: ({ signal }) => api.problems(signal), enabled: allowed, retry: false });
const route = useRoute();
const router = useRouter();
const lastExercise = ref(typeof route.query.exercise === 'string' ? route.query.exercise : undefined);
const active = useQuery({ queryKey: ['workspace', owner, 'current', lastExercise], queryFn: ({ signal }) => api.current(signal, lastExercise.value),
  enabled: allowed, retry: false, refetchInterval: 2000 });
watch(() => route.query.exercise, (id) => {
  lastExercise.value = typeof id === 'string' ? id : undefined;
  if (id !== deletedExercise.value) deletedExercise.value = '';
});
const deletedExercise = ref('');
const exercise = computed(() => active.data.value?.exerciseId === deletedExercise.value ? null : active.data.value);
const problem = computed(() => catalogue.data.value?.find((item) => item.problemVersionId === exercise.value?.problemVersionId));
const selected = ref('');
const editorView = ref<{ saveForLogout: () => Promise<boolean>; prepareEnd: (choice: 'save' | 'discard' | 'retain') => Promise<boolean> }>();
const now = ref(Date.now());
const tick = globalThis.setInterval(() => { now.value = Date.now(); }, 1000);
const expired = computed(() => exercise.value?.state === 'ACTIVE' && !!exercise.value.deadlineAt && now.value >= Date.parse(exercise.value.deadlineAt));
const answeringClosed = computed(() => expired.value || exercise.value?.state !== 'ACTIVE');
const remaining = computed(() => {
  const seconds = Math.max(0, Math.ceil((Date.parse(exercise.value?.deadlineAt ?? '') - now.value) / 1000));
  return Number.isFinite(seconds) ? `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}` : '';
});
const endPrompt = ref(false);
const ending = ref(false);
const hasEditor = ref(false);
watch(() => exercise.value?.exerciseId, (id) => {
  if (id) {
    lastExercise.value = id;
    if (route.query.exercise !== id) void router.replace({ query: { ...route.query, exercise: id } });
  }
  hasEditor.value = exercise.value?.state === 'ACTIVE';
  endPrompt.value = false;
});
watch(() => exercise.value?.state, (state) => { if (state === 'ACTIVE') hasEditor.value = true; });
watch(expired, (value) => { if (value) { endPrompt.value = false; void active.refetch(); } });
const logoutBusy = ref(false);
const testView = ref<{ start: (revision: string) => void; inspect: (taskId: string) => void }>();
const testBusy = ref(true);
const commandBusy = ref(true);
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
  enabled: computed(() => allowed.value && !!exercise.value && ['READY', 'ACTIVE'].includes(exercise.value.state)), retry: false, refetchInterval: 2000 });
watch(() => controlState.data.value?.generation, (value) => {
  if (value && credential.value && value !== credentialGeneration.value) credential.value = '';
});
watch(() => exercise.value?.workspaceInstanceId, () => { selected.value = ''; credential.value = ''; testBusy.value = true; commandBusy.value = true; });
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
  globalThis.clearInterval(tick);
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
    const result = await api.act(action, controller.signal, credential.value || undefined);
    if (disposed || generation !== startedGeneration) return;
    if (action.path === '/api/exercises') {
      lastExercise.value = result.exerciseId;
      await router.replace({ query: { ...route.query, exercise: result.exerciseId } });
      if (disposed || generation !== startedGeneration) return;
    }
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
  if (ending.value || pending.value || unresolved.value || answeringClosed.value || (choice === 'retain' ? !canRecover.value : !writable.value || !credential.value)) return;
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
    if (disposed || started !== generation || id !== exercise.value?.exerciseId || answeringClosed.value) return;
    endPrompt.value = false;
    await perform({ path: `/api/exercises/${encodeURIComponent(id!)}/${choice === 'retain' ? 'recovery-end' : 'end'}`, body: {}, key: globalThis.crypto.randomUUID() });
  } finally { if (started === generation) ending.value = false; }
}
function create(version: string) { void perform({ path: '/api/exercises', body: { problemVersionId: version }, key: globalThis.crypto.randomUUID() }); }
function change(kind: 'start' | 'preparation-retry' | 'abandon') {
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
  ACTIVE: '已开始，请显式保存修改', CLOSING: '正在收尾', FINAL_TESTING: '正在最终验收', ENDED: '已结束' };
</script>

<template>
  <main
    class="workspace-page"
    :style="{ '--primary': theme.primaryColor }"
  >
    <header>
      <RouterLink :to="{ name: 'account' }">账号与登录</RouterLink>
      <h1>题目与工作区</h1>
      <p>第二阶段工程练习：支持编辑、显式保存与稳定版本测试。开始前需选择并检查个人模型；无面试官、挑战或 AI 报告，不代表完整面试流程。</p>
    </header>
    <p
      v-if="account.isPending.value"
      role="status"
    >
      正在检查登录状态…
    </p>
    <p
      v-else-if="account.isError.value"
      role="alert"
    >
      无法确认登录状态，请刷新页面重试。
    </p>
    <p v-else-if="!allowed">
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
        class="failure"
      >
        {{ error }}
      </p>
      <NButton
        v-if="unresolved"
        :disabled="pending"
        @click="perform(unresolved)"
      >
        核对并重试原操作
      </NButton>
      <WorkspaceRetention
        :key="owner"
        :owner="owner"
        @deleted="acceptedDeletion"
        @select="router.push({ query: { ...route.query, exercise: $event } })"
      />
      <p
        v-if="cleanupFailed"
        role="alert"
      >
        本地草稿清理失败，演练访问仍已撤销。<NButton @click="acceptedDeletion(cleanupFailed)">
          重试清理本地草稿
        </NButton>
      </p>
      <p
        v-if="deletedExercise"
        role="status"
      >
        演练已删除，不能恢复作答；后台清理进度可继续查询。
      </p>
      <p
        v-if="catalogue.isError.value || active.isError.value && !deletedExercise"
        role="alert"
        class="failure"
      >
        {{ api.message(catalogue.error.value ?? active.error.value) }} 先前数据不代表当前状态。
      </p>
      <p
        v-if="controlState.isError.value"
        role="alert"
      >
        无法确认操作权，已暂停写入。{{ api.message(controlState.error.value) }}
      </p>
      <NButton
        :disabled="pending || active.isFetching.value"
        @click="catalogue.refetch(); active.refetch(); exercise && controlState.refetch()"
      >
        刷新查询
      </NButton>
      <p
        v-if="catalogue.isPending.value || active.isPending.value"
        role="status"
      >
        正在读取题目与当前演练…
      </p>
      <template v-else-if="exercise || (!catalogue.isError.value && !active.isError.value)">
        <template v-if="!exercise || exercise.state === 'ENDED'">
          <p v-if="!catalogue.data.value?.length">
            尚无已发布题目，请联系维护者。
          </p>
          <section
            v-for="item in catalogue.data.value"
            :key="item.problemVersionId"
            class="problem-summary"
          >
            <h2>{{ item.title }} <small>{{ item.version }}</small></h2>
            <pre class="requirements">{{ item.requirements }}</pre>
            <p>允许编辑范围：{{ item.editableRoots.join('、') }}。开始计时后可保存。</p>
            <p>开始后时长 {{ item.durationSeconds / 60 }} 分钟；准备阶段不计时。</p>
            <NButton
              type="primary"
              data-testid="create-workspace"
              :disabled="pending || !!unresolved"
              @click="create(item.problemVersionId)"
            >
              创建独立工作区
            </NButton>
          </section>
        </template>
        <template v-if="exercise">
          <section
            class="exercise-summary"
            aria-label="演练状态"
          >
            <h2>{{ problem?.title ?? '固定版本演练' }}</h2>
            <p role="status">
              {{ labels[exercise.state] }} · 修订 {{ exercise.revision ?? '尚未确认' }}
            </p>
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
            <p
              v-if="exercise.state === 'ACTIVE'"
              role="status"
            >
              {{ expired ? '作答已到期，等待服务器确认；本地草稿不会自动提交。' : `剩余时间 ${remaining}` }}
            </p>
            <NButton
              v-if="exercise.state === 'ACTIVE' && !expired"
              data-testid="end-exercise"
              :disabled="pending || ending || !!unresolved || !canRecover || logoutBusy"
              @click="endPrompt = true"
            >
              结束演练
            </NButton>
            <section
              v-if="endPrompt && !answeringClosed"
              role="dialog"
              aria-modal="true"
              aria-label="结束演练确认"
            >
              <p>结束后不可继续作答。可先保存、明确丢弃，或只保留本地草稿而不提交新保存。保留草稿不需要本页操作权；最终验收使用服务器关闭并核对后的已提交版本。</p>
              <p v-if="testBusy">
                仍有测试运行或状态待核对，结束后将由服务器停止并核对，不会把未知结果视为已停止。
              </p>
              <NButton
                :disabled="ending || pending || !credential || !writable"
                @click="endExercise('save')"
              >
                保存后结束
              </NButton>
              <NButton
                :disabled="ending || pending || !credential || !writable"
                @click="endExercise('discard')"
              >
                丢弃并结束
              </NButton>
              <NButton
                :disabled="ending || pending || !canRecover"
                @click="endExercise('retain')"
              >
                保留草稿并结束
              </NButton>
              <NButton
                :disabled="ending || pending"
                @click="endPrompt = false"
              >
                返回编辑
              </NButton>
            </section>
            <section
              v-if="exercise.finalization"
              aria-label="最终验收结果"
            >
              <p>最终冻结修订：{{ exercise.finalization.finalRevision ?? '尚未确认，正在核对' }}</p>
              <p>{{ ({ PENDING: '最终验收待核对', PASSED: '最终验收通过', FAILED: '最终验收失败', TIMED_OUT: '最终验收超时', UNAVAILABLE: '最终验收结果不可用' })[exercise.finalization.verification] }}</p>
              <p>系统验收尝试 {{ exercise.finalization.attempts }} / {{ exercise.finalization.maxAttempts }}；报告状态 UNAVAILABLE_NOT_IMPLEMENTED，AI 报告尚未接入。</p>
              <p v-if="exercise.finalization.status === 'RECONCILIATION_REQUIRED'">
                冻结事实未确认，不能虚构最终版本，请稍后刷新。
              </p>
            </section>
            <p v-if="exercise.state === 'PREPARING'">
              后台正在核对准备结果。结果未知时不能另开重试或放弃请求。
            </p>
            <p
              v-if="['READY', 'ACTIVE'].includes(exercise.state)"
              role="status"
            >
              {{ credential ? '当前页面持有操作权' : '当前页面只读，可保留本地草稿' }}
            </p>
            <NButton
              v-if="!credential && ['READY', 'ACTIVE'].includes(exercise.state)"
              :disabled="pending || controlState.data.value?.state === 'PENDING'"
              @click="takeControl(controlState.data.value?.held ? 'takeover' : 'acquire')"
            >
              {{ controlState.data.value?.held ? '接管操作权' : '申请操作权' }}
            </NButton>
            <NButton
              v-if="exercise.state === 'READY'"
              :disabled="pending || !!unresolved || !credential"
              @click="change('start')"
            >
              确认开始计时
            </NButton>
            <NButton
              v-if="exercise.state === 'PREPARATION_FAILED'"
              :disabled="pending || !!unresolved"
              @click="change('preparation-retry')"
            >
              使用新实例重试准备
            </NButton>
            <NButton
              v-if="['READY', 'PREPARATION_FAILED'].includes(exercise.state)"
              :disabled="pending || !!unresolved"
              @click="change('abandon')"
            >
              放弃本次演练
            </NButton>
          </section>
          <p
            v-if="problem && !problem.executionAvailable"
            role="status"
          >
            这个固定题目版本尚未配备正式执行配置，测试不可用；不会替换已有工作区版本。
          </p>
          <ExerciseModelPicker
            v-if="['READY', 'ACTIVE'].includes(exercise.state)"
            :key="owner + exercise.exerciseId"
            :owner="owner"
            :exercise-id="exercise.exerciseId"
            :credential="credential"
            :preparing="exercise.state === 'READY'"
            :disabled="!writable || expired || pending || !!unresolved"
            @unauthorized="credential = ''; account.refetch()"
          />
          <CopilotPanel
            v-if="['ACTIVE', 'CLOSING', 'FINAL_TESTING', 'ENDED'].includes(exercise.state)"
            :key="owner + exercise.exerciseId"
            :owner="owner"
            :exercise-id="exercise.exerciseId"
            :credential="credential"
            :disabled="!editorView || !writable || answeringClosed || pending || !!unresolved || ending || endPrompt || logoutBusy"
            :prepare="prepareCopilot"
            @inspect-test="testView?.inspect($event)"
            @changed="refreshWorkspace"
            @unauthorized="credential = ''; account.refetch()"
          />
          <WorkspaceCommands
            v-if="['ACTIVE', 'CLOSING', 'FINAL_TESTING', 'ENDED'].includes(exercise.state)"
            :key="owner + exercise.workspaceInstanceId"
            :owner="owner"
            :exercise="exercise"
            :credential="credential"
            :disabled="!editorView || !writable || answeringClosed || pending || !!unresolved || ending || endPrompt || logoutBusy || testBusy"
            :prepare="prepareCommand"
            @locked="commandBusy = $event"
            @changed="refreshWorkspace"
            @unauthorized="credential = ''; account.refetch()"
          />
          <WorkspaceStorage
            :key="owner + exercise.exerciseId"
            :owner="owner"
            :exercise-id="exercise.exerciseId"
            @deleted="acceptedDeletion"
          />
          <WorkspaceTests
            v-if="['ACTIVE', 'CLOSING', 'FINAL_TESTING', 'ENDED'].includes(exercise.state)"
            :key="owner + exercise.workspaceInstanceId"
            ref="testView"
            :owner="owner"
            :exercise="exercise"
            :credential="writable && !logoutBusy && !answeringClosed && !ending ? credential : ''"
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
          <div class="workspace-panels">
            <section aria-label="题目与文件">
              <h2>题目要求</h2>
              <pre class="requirements">{{ problem?.requirements }}</pre>
              <p>允许编辑范围：{{ problem?.editableRoots.join('、') }}；pom.xml 与验收测试受保护。</p>
              <h2>工作区文件</h2>
              <p v-if="!exercise.paths.length">
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
                    {{ path }}
                  </button>
                </li>
              </ul>
              <details
                v-for="(content, path) in problem?.acceptanceFiles"
                :key="path"
              >
                <summary>受保护验收测试：{{ path }}</summary>
                <pre class="requirements">{{ content }}</pre>
              </details>
            </section>
            <p
              v-if="source.isFetching.value"
              role="status"
            >
              正在读取文件…
            </p>
            <p
              v-if="source.isError.value"
              role="alert"
            >
              {{ api.message(source.error.value) }}
            </p>
            <NButton
              v-if="source.isError.value"
              @click="source.refetch()"
            >
              重试文件读取
            </NButton>
            <WorkspaceEditor
              v-if="hasEditor"
              :key="owner + exercise.workspaceInstanceId + editorGeneration"
              ref="editorView"
              :locked="logoutBusy || ending"
              :answering-closed="answeringClosed"
              :test-busy="testBusy || commandBusy || !problem?.executionAvailable"
              :owner="owner"
              :exercise="exercise"
              :file="source.data.value"
              :selected="selected"
              :credential="writable ? credential : ''"
              @saved="refreshWorkspace"
              @run-test="!commandBusy && testView?.start($event)"
              @select="selected = $event"
              @stale-control="credential = ''"
            />
            <section
              v-if="!hasEditor || answeringClosed"
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
          </div>
        </template>
      </template>
    </template>
  </main>
</template>

<style scoped>
.workspace-page { max-width: 1440px; margin: 32px auto; padding: 0 24px; }
.workspace-page header { max-width: 75ch; }
.workspace-page h2 { font-size: 20px; overflow-wrap: anywhere; }
.workspace-page section { margin-top: 20px; min-width: 0; }
.workspace-panels { display: grid; grid-template-columns: minmax(260px, 1fr) minmax(0, 2fr); gap: 20px; }
pre { font-family: Consolas, "Cascadia Code", monospace; font-size: 14px; line-height: 1.65; overflow: auto; max-height: 65vh; }
.requirements { white-space: pre-wrap; overflow-wrap: anywhere; }
.file-list { list-style: none; padding: 0; }
.file-list button { width: 100%; padding: 10px 0; text-align: left; border: 0; background: transparent; color: var(--primary); overflow-wrap: anywhere; cursor: pointer; }
.file-list button[aria-pressed="true"] { font-weight: 700; text-decoration: underline; }
@media (max-width: 760px) { .workspace-panels { grid-template-columns: 1fr; } .workspace-page { padding: 0 16px; } }
</style>
