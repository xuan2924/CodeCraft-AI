<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import NButton from '../components/ui/Button.vue';
import { RouterLink } from 'vue-router';
import * as api from './api';
import ModelCheckView from './ModelCheckView.vue';
import { errorMessage, identityFailure } from './errors';
import { useModelCommand } from './useModelCommand';

const props = defineProps<{ owner: string; exerciseId: string; credential: string; preparing: boolean; disabled: boolean }>();
const emit = defineEmits<{ unauthorized: [] }>();
const connectionCursor = ref<string | null>(null);
const modelCursor = ref<string | null>(null);
const connectionId = ref('');
const modelId = ref('');
const scope = computed(() => `${props.owner}/${props.exerciseId}/${props.credential}`);
const connections = useQuery({ queryKey: computed(() => ['model-access', props.owner, 'picker-connections', connectionCursor.value]),
  queryFn: ({ signal }) => api.connections(connectionCursor.value, signal, props.owner), retry: false, gcTime: 0 });
const connection = computed(() => connections.data.value?.items.find((value) => value.id === connectionId.value));
const models = useQuery({ queryKey: computed(() => ['model-access', props.owner, 'picker-models', connectionId.value, modelCursor.value]),
  queryFn: ({ signal }) => api.models(connectionId.value, modelCursor.value, signal, props.owner), enabled: computed(() => !!connectionId.value), retry: false, gcTime: 0 });
const model = computed(() => models.data.value?.items.find((value) => value.id === modelId.value));
const selection = useQuery({ queryKey: computed(() => ['model-access', props.owner, 'exercise-selection', props.exerciseId]),
  queryFn: ({ signal }) => api.exerciseSelection(props.exerciseId, signal, props.owner), retry: false, gcTime: 0, refetchInterval: 2000 });
watch([connections.error, models.error, selection.error], (errors) => { if (errors.some(identityFailure)) emit('unauthorized'); });
watch(connectionId, () => { modelId.value = ''; modelCursor.value = null; });
const command = useModelCommand<api.ExerciseModel>(() => scope.value, () => { void selection.refetch(); }, () => emit('unauthorized'));
function select() {
  if (!connection.value || !model.value || !props.credential || props.disabled) return;
  const reference: api.ModelReference = { connectionId: connection.value.id, modelConfigurationId: model.value.id,
    connectionConfigurationVersion: connection.value.configurationVersion, modelConfigurationVersion: model.value.configurationVersion };
  const { owner, exerciseId, credential } = props;
  void command.submit((key, signal) => api.selectExerciseModel(exerciseId, reference, credential, key, signal, owner));
}
const availability = computed(() => {
  if (!selection.data.value) return '尚未确认当前选择';
  switch (selection.data.value.availability) {
    case 'SELECTED': return '所选版本可用；保存选择不代表检查通过';
    case 'SELECTION_REQUIRED': return '请先选择模型';
    case 'MODEL_VERSION_CONFLICT': return '配置已更新，请显式选择使用更新版本';
    case 'MODEL_CONFIGURATION_NOT_FOUND':
    case 'MODEL_CONNECTION_NOT_FOUND': return '所选配置已删除，请重新选择';
    default: return '所选配置不可用，请检查个人设置';
  }
});
</script>
<template>
  <section aria-label="演练模型选择">
    <h3>本次工程练习使用的模型</h3>
    <p>此选择仅用于个人 Copilot，不影响平台面试官与评测者。配置编辑不自动应用到演练；不自动回退其他模型。</p>
    <RouterLink :to="{ name: 'models' }">管理个人模型连接</RouterLink>
    <p role="status">
      {{ availability }}
    </p>
    <p v-if="selection.data.value?.selected">
      当前选择：{{ selection.data.value.selected.connectionId }} / {{ selection.data.value.selected.modelConfigurationId }}，
      配置版本 {{ selection.data.value.selected.connectionConfigurationVersion }} / {{ selection.data.value.selected.modelConfigurationVersion }}
    </p>
    <p v-if="connections.isPending.value">
      正在读取连接…
    </p>
    <p
      v-else-if="connections.isError.value"
      role="alert"
    >
      {{ errorMessage(connections.error.value) }}
    </p>
    <p v-else-if="!connections.data.value?.items.length">
      暂无连接，请先在个人设置中添加。
    </p>
    <label :for="`connection-${exerciseId}`">连接</label>
    <select
      :id="`connection-${exerciseId}`"
      v-model="connectionId"
      :disabled="command.pending.value || command.uncertain.value"
    >
      <option value="">
        请选择连接
      </option>
      <option
        v-for="item in connections.data.value?.items"
        :key="item.id"
        :value="item.id"
      >
        {{ item.name }} · {{ item.protocol }}
      </option>
    </select>
    <NButton
      v-if="connections.data.value?.nextCursor"
      @click="connectionCursor = connections.data.value.nextCursor; connectionId = ''"
    >
      下一页连接
    </NButton>
    <NButton
      v-if="connectionCursor"
      @click="connectionCursor = null; connectionId = ''"
    >
      连接首页
    </NButton>
    <p v-if="connectionId && models.isPending.value">
      正在读取模型…
    </p>
    <p
      v-if="models.isError.value"
      role="alert"
    >
      {{ errorMessage(models.error.value) }}
    </p>
    <label :for="`model-${exerciseId}`">具体模型</label>
    <select
      :id="`model-${exerciseId}`"
      v-model="modelId"
      :disabled="!connection || command.pending.value || command.uncertain.value"
    >
      <option value="">
        请选择模型
      </option>
      <option
        v-for="item in models.data.value?.items"
        :key="item.id"
        :value="item.id"
      >
        {{ connection?.name }} / {{ item.name }} · {{ item.modelId }}
      </option>
    </select>
    <NButton
      v-if="models.data.value?.nextCursor"
      @click="modelCursor = models.data.value.nextCursor; modelId = ''"
    >
      下一页模型
    </NButton>
    <NButton
      v-if="modelCursor"
      @click="modelCursor = null; modelId = ''"
    >
      模型首页
    </NButton>
    <p
      v-if="selection.isError.value"
      role="alert"
    >
      {{ errorMessage(selection.error.value) }}
    </p>
    <p
      v-if="command.error.value"
      role="alert"
    >
      {{ command.error.value }}
    </p>
    <NButton
      v-if="command.uncertain.value"
      :disabled="command.pending.value || disabled"
      @click="command.retry"
    >
      核对原模型选择
    </NButton>
    <NButton
      v-else
      :disabled="!model || !connection || !credential || disabled || command.pending.value"
      @click="select"
    >
      显式使用此版本
    </NButton>
    <NButton @click="connections.refetch(); models.refetch(); selection.refetch()">
      刷新配置与选择
    </NButton>
    <p v-if="!preparing">
      将在下次请求使用；当前请求及工具批次不受选择影响，待审批保留。切换不发起额外检查。
    </p>
    <template v-if="preparing">
      <p>开始计时前服务器复核所选版本基础连接与工具闭环均在有效期内。请先选择上方模型，再显式确认收费并检查。</p>
      <ModelCheckView
        v-if="model && connection"
        :key="model.id + connection.configurationVersion"
        :owner="owner"
        :model="model"
        :connection-version="connection.configurationVersion"
        @unauthorized="emit('unauthorized')"
      />
    </template>
  </section>
</template>
