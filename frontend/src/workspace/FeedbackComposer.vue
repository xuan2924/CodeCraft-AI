<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import Button from '../components/ui/Button.vue';
import { message } from './api';
import * as api from './feedbackApi';
const props = defineProps<{ owner: string; exerciseId: string; targetType: 'REPORT' | 'INTERVIEWER'; targetId: string; generation: number; choices?: { id: string; summary: string }[] }>();
const client = useQueryClient();
const description = ref('');
const selected = ref<string[]>([]);
const input = ref<api.FeedbackInput | null>(null);
const confirmed = ref(false);
const sent = ref(false);
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.targetType}/${props.targetId}/${props.generation}`);
watch([description, selected], () => { input.value = null; confirmed.value = false; }, { deep: true });
watch(scope, () => { description.value = ''; selected.value = []; input.value = null; sent.value = false; confirmed.value = false; });
const preview = useQuery({ queryKey: computed(() => ['feedback-preview', scope.value, input.value]), gcTime: 0, retry: false, enabled: computed(() => input.value !== null),
  queryFn: ({ signal }) => api.preview(props.owner, props.exerciseId, input.value!, signal) });
watch(() => preview.data.value?.fingerprint, () => { confirmed.value = false; });
const command = useCommand<api.Feedback>(() => scope.value, () => { sent.value = true; void client.invalidateQueries({ queryKey: ['feedback'] }); }, {
  definitiveRejection: error => error instanceof ApiError && [400, 401, 403, 404, 409, 410, 429].includes(error.status), errorMessage: message, identityFailure: () => false });
const busy = computed(() => command.pending.value || command.uncertain.value);
const valid = computed(() => description.value.trim().length > 0 && new globalThis.TextEncoder().encode(description.value).length <= 4096 && selected.value.length <= 4);
function inspect() {
  if (!valid.value || busy.value) return;
  confirmed.value = false;
  input.value = { targetType: props.targetType, targetId: props.targetId, generation: props.generation, description: description.value, evidenceIds: [...selected.value] };
}
function submit() {
  if (!input.value || !preview.data.value || preview.isError.value || preview.isFetching.value || !confirmed.value || busy.value) return;
  const payload = { ...input.value, previewFingerprint: preview.data.value.fingerprint };
  const { owner, exerciseId } = props;
  void command.submit((key, signal) => api.submit(owner, exerciseId, payload, key, signal));
}
</script>
<template>
  <section
    class="feedback-composer"
    aria-label="授权反馈"
  >
    <h3>授权反馈</h3><p>只发送下方预览的快照。默认不附 Copilot 对话、Skill、凭证或完整工程；不承诺改分或修改配置。</p>
    <p>反馈、附件及回复与原演练共用容量和保留期限。演练删除或到期后，用户与管理员均无法继续读取，随后一并清理。</p>
    <p
      v-if="sent"
      role="status"
    >
      反馈已提交，可在“授权反馈”页查看状态和一次性处理回复。
    </p>
    <template v-else>
      <label>反馈描述（最多 4 KiB）<textarea
        v-model="description"
        rows="4"
        :disabled="busy"
      /></label>
      <fieldset
        v-if="choices?.length"
        :disabled="busy"
      >
        <legend>额外授权证据（默认不选，至多 4 项）</legend><label
          v-for="item in choices"
          :key="item.id"
          class="choice"
        ><input
          v-model="selected"
          type="checkbox"
          :value="item.id"
        >{{ item.summary }}</label>
      </fieldset>
      <Button
        variant="secondary"
        :disabled="!valid || busy || preview.isFetching.value"
        @click="inspect"
      >
        预览实际发送范围
      </Button>
      <p
        v-if="preview.isFetching.value"
        role="status"
      >
        正在核对来源与权限…
      </p><p
        v-if="preview.isError.value"
        role="alert"
      >
        {{ message(preview.error.value) }}
      </p>
      <template v-if="input && preview.data.value && !preview.isError.value">
        <h4>完整授权快照</h4><pre>{{ preview.data.value.snapshot }}</pre><label class="choice"><input
          v-model="confirmed"
          type="checkbox"
          :disabled="busy"
        >我已核对完整内容与保留规则，授权管理员读取此快照</label><Button
          variant="primary"
          :disabled="!confirmed || busy || preview.isFetching.value"
          @click="submit"
        >
          确认授权并提交
        </Button>
      </template>
      <p
        v-if="command.error.value"
        role="alert"
      >
        {{ command.error.value }}
      </p><Button
        v-if="command.uncertain.value"
        variant="secondary"
        :disabled="command.pending.value"
        @click="command.retry"
      >
        核对原反馈请求
      </Button>
    </template>
  </section>
</template>
<style scoped>
.feedback-composer {
  margin-top: 16px;
  border-top: 1px solid var(--oc-border);
  padding-top: 16px;
  display: grid;
  gap: 10px;
}
h3, h4 { margin: 0; font-size: 14px; font-weight: 600; }
p { margin: 0; line-height: 1.6; font-size: 13px; color: var(--oc-fg-muted); }
label { display: grid; gap: 6px; font-size: 13px; font-weight: 550; }
textarea {
  display: block;
  width: 100%;
  box-sizing: border-box;
  font: inherit;
  color: var(--oc-fg);
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border-input);
  border-radius: 8px;
  padding: 10px 12px;
}
.choice {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin: 6px 0;
  font-weight: 450;
  overflow-wrap: anywhere;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 400px;
  overflow: auto;
  padding: 14px;
  border-radius: 8px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  font-size: 12.5px;
}
fieldset {
  border: 1px solid var(--oc-border);
  border-radius: 8px;
  padding: 12px;
  margin: 4px 0;
}
legend { font-size: 12.5px; color: var(--oc-fg-muted); padding: 0 6px; }
</style>
