<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { ApiError } from '../api/request';
import * as api from './api';
import { errorMessage, identityFailure } from './errors';
import { useModelCommand } from './useModelCommand';
import ModelCheckUsage from './ModelCheckUsage.vue';
const props = defineProps<{ owner: string; model: api.Model; connectionVersion: string }>();
const emit = defineEmits<{ unauthorized: [] }>();
const scope = computed(() => `${props.owner}/${props.model.id}/${props.model.configurationVersion}/${props.connectionVersion}`);
const selectedGroup = ref<string | null>(null);
const fee = ref(false);
watch(scope, () => { selectedGroup.value = null; fee.value = false; }, { flush: 'sync' });
const result = useQuery({ queryKey: computed(() => ['model-access', props.owner, 'check', scope.value, selectedGroup.value]),
  queryFn: ({ signal }) => selectedGroup.value ? api.getCheck(selectedGroup.value, signal, props.owner) : api.latestCheck(props.model.id, signal, props.owner),
  retry: false, gcTime: 0, enabled: computed(() => !!props.owner),
  refetchInterval: (query) => query.state.data && ['QUEUED', 'RUNNING'].includes(query.state.data.check.status) ? 1000 : 30000 });
watch(result.error, (error) => { if (identityFailure(error)) emit('unauthorized'); });
const command = useModelCommand<api.Check>(() => scope.value, (check) => { selectedGroup.value = check.id; fee.value = false; }, () => emit('unauthorized'));
const now = ref(Date.now());
const tick = globalThis.setInterval(() => { now.value = Date.now(); }, 1000);
onScopeDispose(() => globalThis.clearInterval(tick));
const active = computed(() => !!result.data.value && ['QUEUED', 'RUNNING'].includes(result.data.value.check.status));
function valid(item: api.CheckItem) { return item.valid && !!item.validUntil && Date.parse(item.validUntil) > now.value; }
const bothValid = computed(() => !!result.data.value && valid(result.data.value.basic) && valid(result.data.value.tool));
function label(item: api.CheckItem) {
  if (valid(item)) return '通过，可复用';
  if (item.status === 'SUCCEEDED') return '历史通过，当前不可复用';
  if (item.status === 'RUNNING') return '进行中';
  if (['PENDING', 'NOT_RUN'].includes(item.status)) return '尚未完成';
  return '未通过';
}
const rows = computed(() => result.data.value ? [{ name: '基本连通', item: result.data.value.basic }, { name: '工具闭环', item: result.data.value.tool }] : []);
function start() {
  if (!fee.value || active.value || bothValid.value || result.isPending.value || result.isError.value || command.pending.value || command.uncertain.value) return;
  const modelId = props.model.id; const owner = props.owner;
  void command.submit((key, signal) => api.startCheck(modelId, key, signal, owner));
}
</script>
<template>
  <div class="check-view">
    <h4>模型检查</h4>
    <p
      v-if="result.isPending.value"
      role="status"
    >
      正在读取检查结果…
    </p>
    <p
      v-else-if="result.isError.value"
      role="alert"
      class="failure"
    >
      {{ errorMessage(result.error.value) }}
    </p>
    <template v-else-if="result.data.value">
      <p
        v-if="active"
        role="status"
      >
        {{ result.data.value.check.status === 'QUEUED' ? '已排队，等待后台派发。' : '检查进行中。' }}
      </p>
      <p v-if="!result.data.value.currentConfiguration">
        配置已变更，旧检查不再代表当前能力。
      </p>
      <dl>
        <template
          v-for="row in rows"
          :key="row.name"
        >
          <dt>{{ row.name }}：{{ label(row.item) }}</dt>
          <dd>
            <template v-if="row.item.completedAt">
              完成于 <time :datetime="row.item.completedAt">{{ new Date(row.item.completedAt).toLocaleString() }}</time>。
            </template>
            <template v-if="row.item.validUntil">
              有效期至 <time :datetime="row.item.validUntil">{{ new Date(row.item.validUntil).toLocaleString() }}</time>。
            </template>
            <span v-if="row.item.reused">复用原结果，不延长原有效期。</span>
            <span
              v-if="row.item.errorCode"
              class="failure"
            >{{ errorMessage(new ApiError(400, row.item.errorCode)) }}</span>
          </dd>
        </template>
      </dl>
      <ModelCheckUsage
        :key="`${scope}/${result.data.value.check.id}`"
        :owner="owner"
        :check-id="result.data.value.check.id"
        @unauthorized="emit('unauthorized')"
      />
    </template>
    <p v-else>
      暂无保留的检查记录。历史记录可能已清理，保存配置不代表供应商兼容性已验证。
    </p>
    <Button
      :disabled="result.isFetching.value"
      @click="result.refetch()"
    >
      重新读取结果
    </Button>
    <p class="help">
      最多 3 次真实模型请求，可能收费；失败不自动重试。关闭页面或取消等待不代表供应商未收费。通过结果自原完成时间起 30 分钟内可复用；终态检查记录保留至少 30 天。
    </p>
    <label :for="`fee-${model.id}`"><input
      :id="`fee-${model.id}`"
      v-model="fee"
      type="checkbox"
      :disabled="command.pending.value || command.uncertain.value || active || bothValid"
    >我确认检查可能产生费用</label>
    <p
      v-if="command.error.value"
      role="alert"
      class="failure"
    >
      {{ command.error.value }}
    </p>
    <p
      v-if="command.uncertain.value"
      role="status"
    >
      启动结果未确认；重试只核对原请求，不创建新的一组。
    </p>
    <div class="actions">
      <Button
        v-if="command.uncertain.value"
        :disabled="command.pending.value"
        :loading="command.pending.value"
        @click="command.retry"
      >
        核对原检查请求
      </Button>
      <Button
        v-else
        variant="primary"
        :disabled="!fee || active || bothValid || result.isPending.value || result.isError.value || command.pending.value"
        :loading="command.pending.value"
        @click="start"
      >
        {{ bothValid ? '两项结果均可复用' : '开始检查' }}
      </Button>
    </div>
  </div>
</template>
<style scoped>
.check-view { border-top: 1px solid var(--oc-border); margin-top: 16px; padding-top: 16px; display: grid; gap: 10px; }
h4 { font-size: 15px; font-weight: 600; margin: 0; }
dl { margin: 0; display: grid; gap: 8px; padding: 12px; border-radius: 10px; background: var(--oc-surface-2); border: 1px solid var(--oc-border); }
dt { font-weight: 600; font-size: 13px; }
dd { margin: 0; font-size: 12.5px; color: var(--oc-fg-muted); line-height: 1.6; overflow-wrap: anywhere; }
.help { color: var(--oc-fg-muted); font-size: 12.5px; margin: 0; line-height: 1.6; }
.actions { margin-top: 4px; }
label { display: flex; align-items: center; gap: 8px; font-size: 13px; }
</style>
