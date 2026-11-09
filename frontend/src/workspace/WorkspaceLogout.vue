<script setup lang="ts">
import { onMounted, onScopeDispose, ref } from 'vue';
import { NButton } from 'naive-ui';
import { onBeforeRouteLeave } from 'vue-router';
import { useAccount } from '../auth/useAccount';
import { listAccountDrafts, readDraftEpoch, removeDraft, releaseDraftLogout, sealDraftsForLogout, type Draft } from './drafts';
import { cancelSaveLogout } from './logoutIntent';

const props = defineProps<{ owner: string; exerciseId?: string; save: () => Promise<boolean> }>();
const emit = defineEmits<{ select: [path: string]; busy: [value: boolean]; restart: [] }>();
const { identity, signOut } = useAccount();
const drafts = ref<Draft[]>([]);
const pending = ref(false);
const error = ref('');
let epoch: number | undefined;
let disposed = false;
const owner = props.owner;
onBeforeRouteLeave(() => !pending.value);
function beforeUnload(event: globalThis.BeforeUnloadEvent) {
  if (pending.value) { event.preventDefault(); event.returnValue = ''; }
}
globalThis.addEventListener('beforeunload', beforeUnload);
onScopeDispose(() => { disposed = true; globalThis.removeEventListener('beforeunload', beforeUnload); });
async function refresh() {
  const found = await listAccountDrafts(owner);
  if (!disposed) drafts.value = found;
}
async function load() {
  if (pending.value) return;
  pending.value = true;
  try { epoch = await readDraftEpoch(owner); await refresh(); }
  catch { if (!disposed) error.value = '无法读取账号草稿，不能确认安全退出。请重试或取消。'; }
  finally { if (!disposed) pending.value = false; }
}
onMounted(load);
async function discard(draft: Draft) {
  if (pending.value || !globalThis.confirm(`只丢弃这份备份？${draft.path} · ${draft.id}。编辑器中的内容不会改变。`)) return;
  pending.value = true;
  try { await removeDraft(draft); await refresh(); }
  catch { if (!disposed) error.value = '草稿清理失败，请重试。'; }
  finally { if (!disposed) pending.value = false; }
}
async function finish() {
  if (pending.value || epoch === undefined || identity.value?.id !== owner) return;
  pending.value = true; emit('busy', true); error.value = '';
  let sealed = false;
  let logoutConfirmed = false;
  const sealedEpoch = epoch + 1;
  try {
    if (!await props.save()) { error.value = '保存未确认或仍有新输入，请处理后再退出。'; return; }
    if (disposed || identity.value?.id !== owner) return;
    await refresh();
    if (disposed) return;
    if (drafts.value.length) { error.value = '仍有未处理备份：请逐份恢复并保存，或明确丢弃，不能自动选择冲突版本。'; return; }
    sealed = await sealDraftsForLogout(owner, epoch);
    if (!sealed) { await refresh(); error.value = '草稿或账号状态已变化，未退出。请刷新列表或取消后重新发起。'; return; }
    if (disposed || identity.value?.id !== owner) return;
    logoutConfirmed = await signOut('saved');
    if (logoutConfirmed) { cancelSaveLogout(); return; }
    // A failed/unknown logout must not pretend success or leave local storage sealed.
    error.value = '退出尚未确认，登录仍保留。请重新申请操作权后重试。';
  } catch { if (!disposed) error.value = '保存或草稿核对失败，未退出。请重试或取消。'; }
  finally {
    if (sealed && !logoutConfirmed) {
      try {
        const released = await releaseDraftLogout(owner, sealedEpoch);
        if (released && !disposed && identity.value?.id === owner) { epoch = await readDraftEpoch(owner); emit('restart'); }
      } catch { if (!disposed) error.value = '本地草稿存储未恢复，请重新登录后再编辑。'; }
    }
    if (!disposed) { pending.value = false; emit('busy', false); }
  }
}
</script>

<template>
  <section aria-label="保存后退出处理">
    <h2>保存后退出</h2>
    <p>先显式申请或接管操作权，再逐份恢复、比较并保存。其他演练或已结束演练的备份不能在此保存，请取消退出处理，或明确丢弃该份备份。</p>
    <p>同一文件的不同页面备份各自保留；保存一份不代表同意丢弃其他份。刷新页面会取消本次退出意图。</p>
    <p
      v-if="error"
      role="alert"
    >
      {{ error }}
    </p>
    <ul>
      <li
        v-for="draft in drafts"
        :key="draft.exercise + '/' + draft.path + '/' + draft.id"
      >
        <p>{{ draft.exercise }} · {{ draft.path }} · 页面 {{ draft.id }} · {{ new Date(draft.updatedAt).toLocaleString() }}</p>
        <details><summary>查看这份备份</summary><pre>{{ draft.content }}</pre></details>
        <NButton
          :disabled="pending || draft.exercise !== exerciseId"
          @click="emit('select', draft.path)"
        >
          打开文件处理备份
        </NButton>
        <NButton
          :disabled="pending"
          @click="discard(draft)"
        >
          丢弃这份备份
        </NButton>
      </li>
    </ul>
    <NButton
      :disabled="pending"
      @click="load"
    >
      刷新草稿列表
    </NButton>
    <NButton
      :disabled="pending"
      @click="finish"
    >
      保存编辑内容并完成退出
    </NButton>
    <NButton
      :disabled="pending"
      @click="cancelSaveLogout"
    >
      取消退出
    </NButton>
  </section>
</template>
