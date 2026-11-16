<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import NButton from '../components/ui/Button.vue';
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
      <h3>此连接下的模型</h3><NButton @click="form = 'new'">
        新增模型
      </NButton><NButton @click="refresh">
        重新读取
      </NButton>
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
        <NButton
          :disabled="!cursor"
          @click="cursor = null"
        >
          模型首页
        </NButton><NButton
          :disabled="!list.data.value.nextCursor"
          @click="cursor = list.data.value.nextCursor"
        >
          下一页模型
        </NButton>
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
            <NButton @click="form = 'edit'">
              编辑模型
            </NButton><DeleteConfiguration
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
.models { margin-top: 28px; border-top: 1px solid var(--line); padding-top: 16px; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-bottom: 16px; }
h3, h4 { margin: 0; } h3 { margin-right: auto; }
ul { list-style: none; padding: 0; }
li { border-bottom: 1px solid var(--line); }
li button { display: flex; flex-wrap: wrap; gap: 12px; width: 100%; text-align: left; padding: 12px 4px; font: inherit; color: inherit; background: transparent; border: 0; cursor: pointer; }
li button[aria-pressed="true"] { font-weight: 700; border-left: 3px solid var(--primary); padding-left: 12px; }
code { overflow-wrap: anywhere; }
.model-detail { margin-top: 24px; }
</style>
