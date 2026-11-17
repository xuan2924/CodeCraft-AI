<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { Plus } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import Dialog from '../components/ui/Dialog.vue';
import * as api from './api';
import { errorMessage, identityFailure } from './errors';
import ModelForm from './ModelForm.vue';
import ModelCheckView from './ModelCheckView.vue';
import DeleteConfiguration from './DeleteConfiguration.vue';

const props = defineProps<{ owner: string; connection: api.Connection }>();
const emit = defineEmits<{ unauthorized: [] }>();
const client = useQueryClient();
const scope = computed(() => `${props.owner}/${props.connection.id}/${props.connection.configurationVersion}`);
const cursor = ref<string | null>(null);
const selected = ref<string | null>(null);
const form = ref<'new' | 'edit' | null>(null);
const notice = ref('');

watch(selected, () => { notice.value = ''; }, { flush: 'sync' });
watch(scope, () => { cursor.value = null; selected.value = null; form.value = null; notice.value = ''; }, { flush: 'sync' });

const list = useQuery({
  queryKey: computed(() => ['model-access', props.owner, 'models', scope.value, cursor.value]),
  queryFn: ({ signal }) => api.models(props.connection.id, cursor.value, signal, props.owner),
  retry: false,
  gcTime: 0,
});
const detail = useQuery({
  queryKey: computed(() => ['model-access', props.owner, 'model', scope.value, selected.value]),
  queryFn: ({ signal }) => api.getModel(props.connection.id, selected.value!, signal, props.owner),
  enabled: computed(() => !!selected.value),
  retry: false,
  gcTime: 0,
});

watch(() => list.data.value?.items, (items) => {
  if (!items?.length || form.value === 'new') return;
  if (!selected.value || !items.some((item) => item.id === selected.value)) {
    selected.value = items[0]!.id;
  }
}, { immediate: true });

watch([list.error, detail.error], (errors) => { if (errors.some(identityFailure)) emit('unauthorized'); });

function refresh() { void client.invalidateQueries({ queryKey: ['model-access', props.owner] }); }
function saved(model: api.Model) { selected.value = model.id; form.value = null; notice.value = '模型已保存。'; refresh(); }
function deleted() { selected.value = null; form.value = null; notice.value = '模型已删除。'; refresh(); }
</script>

<template>
  <div class="models">
    <p
      v-if="notice"
      role="status"
      class="models-notice"
    >
      {{ notice }}
    </p>
    <div class="models-header">
      <h4>模型列表</h4>
      <Button
        variant="secondary"
        size="small"
        @click="form = 'new'"
      >
        <Plus
          :size="13"
          aria-hidden="true"
        />
        <span>新增模型</span>
      </Button>
    </div>

    <p
      v-if="list.isPending.value"
      role="status"
      class="models-status"
    >
      正在读取模型…
    </p>
    <p
      v-else-if="list.isError.value"
      role="alert"
      class="failure"
    >
      {{ errorMessage(list.error.value) }}
    </p>
    <template v-else-if="list.data.value">
      <p
        v-if="!list.data.value.items.length"
        class="models-status"
      >
        尚无模型配置，点击“新增模型”添加。
      </p>
      <div
        v-else
        class="models-surface"
      >
        <ul class="ui-divide-list model-rows">
          <li
            v-for="model in list.data.value.items"
            :key="model.id"
          >
            <button
              type="button"
              class="model-row-btn"
              :aria-pressed="selected === model.id"
              @click="selected = model.id"
            >
              <span class="model-row-title">{{ model.name }}</span>
              <code class="font-mono-num">{{ model.modelId }}</code>
            </button>
          </li>
        </ul>
        <div
          v-if="cursor || list.data.value.nextCursor"
          class="list-pagination"
        >
          <Button
            variant="ghost"
            size="small"
            :disabled="!cursor"
            @click="cursor = null"
          >
            模型首页
          </Button>
          <Button
            variant="ghost"
            size="small"
            :disabled="!list.data.value.nextCursor"
            @click="cursor = list.data.value.nextCursor"
          >
            下一页模型
          </Button>
        </div>
      </div>
    </template>

    <template v-if="selected">
      <p
        v-if="detail.isPending.value"
        role="status"
        class="models-status"
      >
        正在读取模型详情…
      </p>
      <p
        v-else-if="detail.isError.value"
        role="alert"
        class="failure"
      >
        {{ errorMessage(detail.error.value) }}
      </p>
      <div
        v-else-if="detail.data.value && !detail.data.value.deleted"
        class="model-detail"
      >
        <div class="model-detail-head">
          <div class="model-detail-meta">
            <strong>{{ detail.data.value.name }}</strong>
            <span class="font-mono-num">
              <code>{{ detail.data.value.modelId }}</code> · 思考{{ detail.data.value.thinkingMode === 'ON' ? '开启' : '关闭' }} · 上下文 {{ detail.data.value.contextWindow }} / 输出 {{ detail.data.value.maxOutputTokens }}
            </span>
          </div>
          <div class="toolbar">
            <Button
              variant="ghost"
              size="small"
              @click="form = 'edit'"
            >
              编辑模型
            </Button>
            <DeleteConfiguration
              :key="selected"
              :owner="owner"
              kind="model"
              :entity="detail.data.value"
              @deleted="deleted"
              @unauthorized="emit('unauthorized')"
            />
          </div>
        </div>
        <ModelCheckView
          :key="`${scope}/${selected}`"
          :owner="owner"
          :model="detail.data.value"
          :connection-version="connection.configurationVersion"
          @unauthorized="emit('unauthorized')"
        />
      </div>
    </template>

    <Dialog
      :open="form !== null"
      :title="form === 'edit' ? '编辑模型' : '新增模型'"
      @update:open="(val) => { if (!val) { form = null; refresh(); } }"
    >
      <ModelForm
        v-if="form === 'new'"
        :key="`${scope}/new`"
        :owner="owner"
        :connection="connection"
        :model="null"
        @saved="saved"
        @cancel="form = null; refresh()"
        @unauthorized="emit('unauthorized')"
      />
      <ModelForm
        v-else-if="form === 'edit' && detail.data.value && !detail.data.value.deleted"
        :key="`${scope}/${selected}`"
        :owner="owner"
        :connection="connection"
        :model="detail.data.value"
        @saved="saved"
        @cancel="form = null; refresh()"
        @unauthorized="emit('unauthorized')"
      />
    </Dialog>
  </div>
</template>

<style scoped>
.models {
  margin-top: 8px;
  display: grid;
  gap: 12px;
  min-width: 0;
}

.models-notice {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-accent);
}

.models-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.models-header h4 {
  margin: 0;
  font-size: 12.5px;
  font-weight: 550;
  color: var(--oc-fg-muted);
}

.models-status {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.models-surface {
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: 10px;
  overflow: hidden;
}

.model-rows {
  list-style: none;
  padding: 0;
  margin: 0;
}

.model-row-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border: 0;
  background: transparent;
  color: var(--oc-fg);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.model-row-btn:hover {
  background: rgba(255, 255, 255, 0.03);
}

.model-row-btn[aria-pressed="true"] {
  background: var(--oc-surface-2);
}

.model-row-title {
  font-weight: 550;
}

.list-pagination {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid var(--oc-border);
}

.model-detail {
  padding-top: 12px;
  border-top: 1px solid var(--oc-border);
  display: grid;
  gap: 12px;
}

.model-detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.model-detail-meta {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.model-detail-meta strong {
  font-size: 13.5px;
  font-weight: 600;
}

.model-detail-meta span {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
}

.failure {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-danger);
}
</style>
