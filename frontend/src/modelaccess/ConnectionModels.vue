<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
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
const list = useQuery({ queryKey: computed(() => ['model-access', props.owner, 'models', scope.value, cursor.value]),
  queryFn: ({ signal }) => api.models(props.connection.id, cursor.value, signal, props.owner), retry: false, gcTime: 0 });
const detail = useQuery({ queryKey: computed(() => ['model-access', props.owner, 'model', scope.value, selected.value]),
  queryFn: ({ signal }) => api.getModel(props.connection.id, selected.value!, signal, props.owner), enabled: computed(() => !!selected.value), retry: false, gcTime: 0 });
watch([list.error, detail.error], (errors) => { if (errors.some(identityFailure)) emit('unauthorized'); });
function refresh() { void client.invalidateQueries({ queryKey: ['model-access', props.owner] }); }
function saved(model: api.Model) { selected.value = model.id; form.value = null; notice.value = '模型已保存，检查需单独确认费用。'; refresh(); }
function deleted() { selected.value = null; form.value = null; notice.value = '模型已删除，共用连接与其他模型不受影响。'; refresh(); }
</script>
<template>
  <div class="models">
    <p
      v-if="notice && !form"
      role="status"
    >
      {{ notice }}
    </p>
    <div class="toolbar">
      <h3>此连接下的模型</h3><Button @click="form = 'new'">
        新增模型
      </Button><Button @click="refresh">
        重新读取
      </Button>
    </div>
    <p
      v-if="list.isPending.value"
      role="status"
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
      <p v-if="!list.data.value.items.length">
        此页没有模型配置。
      </p>
      <ul v-else>
        <li
          v-for="model in list.data.value.items"
          :key="model.id"
        >
          <button
            type="button"
            :aria-pressed="selected === model.id"
            @click="selected = model.id; form = null"
          >
            {{ model.name }} <code>{{ model.modelId }}</code>
          </button>
        </li>
      </ul>
      <div class="toolbar">
        <Button
          :disabled="!cursor"
          @click="cursor = null"
        >
          模型首页
        </Button><Button
          :disabled="!list.data.value.nextCursor"
          @click="cursor = list.data.value.nextCursor"
        >
          下一页模型
        </Button>
      </div>
    </template>
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
    <template v-else-if="selected">
      <p
        v-if="detail.isPending.value"
        role="status"
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
      <template v-else-if="detail.data.value && !detail.data.value.deleted">
        <ModelForm
          v-if="form === 'edit'"
          :key="`${scope}/${selected}`"
          :owner="owner"
          :connection="connection"
          :model="detail.data.value"
          @saved="saved"
          @cancel="form = null; refresh()"
          @unauthorized="emit('unauthorized')"
        />
        <div
          v-else
          class="model-detail"
        >
          <h4>{{ detail.data.value.name }}</h4><p><code>{{ detail.data.value.modelId }}</code> · 思考 {{ detail.data.value.thinkingMode === 'ON' ? '开启' : '关闭' }}</p>
          <p>上下文 {{ detail.data.value.contextWindow }} Token（用户提供） · 输出上限 {{ detail.data.value.maxOutputTokens }} Token</p>
          <div class="toolbar">
            <Button @click="form = 'edit'">
              编辑模型
            </Button><DeleteConfiguration
              :key="selected"
              :owner="owner"
              kind="model"
              :entity="detail.data.value"
              @deleted="deleted"
              @unauthorized="emit('unauthorized')"
            />
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
    </template>
  </div>
</template>
<style scoped>
.models { margin-top: 24px; border-top: 1px solid var(--oc-border); padding-top: 20px; display: grid; gap: 14px; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
h3, h4 { margin: 0; font-size: 15px; font-weight: 600; }
h3 { margin-right: auto; }
ul { list-style: none; padding: 0; margin: 0; display: grid; gap: 8px; }
li button {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  width: 100%;
  text-align: left;
  padding: 12px 14px;
  font: inherit;
  font-size: 13.5px;
  color: var(--oc-fg);
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  border-radius: 10px;
  cursor: pointer;
  transition: background-color 160ms var(--oc-ease), border-color 160ms var(--oc-ease);
}
li button:hover { background: var(--oc-surface-hover); border-color: var(--oc-border-strong); }
li button[aria-pressed="true"] {
  background: var(--oc-selected);
  border-color: var(--oc-border-accent);
  font-weight: 600;
}
code {
  overflow-wrap: anywhere;
  padding: 2px 6px;
  border-radius: 5px;
  background: var(--oc-bg-input);
  border: 1px solid var(--oc-border);
  font-size: 12px;
}
.model-detail {
  padding: 18px;
  border-radius: 12px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  display: grid;
  gap: 10px;
}
.model-detail p { margin: 0; font-size: 13px; color: var(--oc-fg-muted); }
</style>
