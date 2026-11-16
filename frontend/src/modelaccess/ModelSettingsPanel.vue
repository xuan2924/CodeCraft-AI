<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import * as api from './api';
import { errorMessage, identityFailure } from './errors';
import ConnectionForm from './ConnectionForm.vue';
import ConnectionModels from './ConnectionModels.vue';
import DeleteConfiguration from './DeleteConfiguration.vue';
const props = defineProps<{ owner: string }>();
const emit = defineEmits<{ unauthorized: [] }>();
const client = useQueryClient();
const cursor = ref<string | null>(null);
const selected = ref<string | null>(null);
const form = ref<'new' | 'edit' | null>(null);
const notice = ref('');
watch(selected, () => { notice.value = ''; }, { flush: 'sync' });
watch(() => props.owner, () => { cursor.value = null; selected.value = null; form.value = null; notice.value = ''; }, { flush: 'sync' });
const list = useQuery({ queryKey: computed(() => ['model-access', props.owner, 'connections', cursor.value]),
  queryFn: ({ signal }) => api.connections(cursor.value, signal, props.owner), retry: false, gcTime: 0 });
const detail = useQuery({ queryKey: computed(() => ['model-access', props.owner, 'connection', selected.value]),
  queryFn: ({ signal }) => api.getConnection(selected.value!, signal, props.owner), enabled: computed(() => !!selected.value), retry: false, gcTime: 0 });
watch([list.error, detail.error], (errors) => { if (errors.some(identityFailure)) emit('unauthorized'); });
function refresh() { void client.invalidateQueries({ queryKey: ['model-access', props.owner] }); }
function saved(connection: api.Connection) { selected.value = connection.id; form.value = null; notice.value = '连接已保存，未自动发起检查。'; refresh(); }
function deleted() { selected.value = null; form.value = null; notice.value = '连接及其模型配置已删除。'; refresh(); }
</script>
<template>
  <section
    class="settings-layout"
    aria-label="个人连接管理"
  >
    <nav
      class="directory"
      aria-label="连接目录"
    >
      <h2>连接目录</h2>
      <div class="toolbar">
        <Button
          variant="primary"
          @click="form = 'new'"
        >
          新建连接
        </Button><Button @click="refresh">
          重新读取
        </Button>
      </div>
      <p
        v-if="list.isPending.value"
        role="status"
      >
        正在读取连接…
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
          此页没有连接。先保存连接与 Key，再添加模型。
        </p>
        <ul v-else>
          <li
            v-for="connection in list.data.value.items"
            :key="connection.id"
          >
            <button
              type="button"
              :aria-pressed="selected === connection.id && form !== 'new'"
              @click="selected = connection.id; form = null"
            >
              {{ connection.name }}<span>{{ connection.protocol }}</span>
            </button>
          </li>
        </ul>
        <div
          v-if="cursor || list.data.value.nextCursor"
          class="toolbar"
        >
          <Button
            :disabled="!cursor"
            @click="cursor = null"
          >
            连接首页
          </Button><Button
            :disabled="!list.data.value.nextCursor"
            @click="cursor = list.data.value.nextCursor"
          >
            下一页连接
          </Button>
        </div>
      </template>
    </nav>
    <div class="detail">
      <p
        v-if="notice && !form"
        role="status"
      >
        {{ notice }}
      </p>
      <ConnectionForm
        v-if="form === 'new'"
        :key="`${owner}/new`"
        :owner="owner"
        :connection="null"
        @saved="saved"
        @cancel="form = null; refresh()"
        @unauthorized="emit('unauthorized')"
      />
      <template v-else-if="selected">
        <p
          v-if="detail.isPending.value"
          role="status"
        >
          正在读取连接详情…
        </p>
        <p
          v-else-if="detail.isError.value"
          role="alert"
          class="failure"
        >
          {{ errorMessage(detail.error.value) }}
        </p>
        <template v-else-if="detail.data.value && !detail.data.value.deleted">
          <ConnectionForm
            v-if="form === 'edit'"
            :key="`${owner}/${selected}`"
            :owner="owner"
            :connection="detail.data.value"
            @saved="saved"
            @cancel="form = null; refresh()"
            @unauthorized="emit('unauthorized')"
          />
          <template v-else>
            <h2>{{ detail.data.value.name }}</h2><p><code>{{ detail.data.value.baseUrl }}</code></p>
            <p>{{ detail.data.value.protocol }} · {{ detail.data.value.preset }} · {{ detail.data.value.keyConfigured ? 'Key 已配置，不提供回显' : 'Key 未配置' }}</p>
            <div class="toolbar">
              <Button @click="form = 'edit'">
                编辑连接
              </Button><DeleteConfiguration
                :key="selected"
                :owner="owner"
                kind="connection"
                :entity="detail.data.value"
                @deleted="deleted"
                @unauthorized="emit('unauthorized')"
              />
            </div>
            <ConnectionModels
              :key="`${owner}/${selected}`"
              :owner="owner"
              :connection="detail.data.value"
              @unauthorized="emit('unauthorized')"
            />
          </template>
        </template>
      </template>
      <div
        v-else
        class="empty"
      >
        <h2>接入你的 Copilot 模型</h2><p>一个连接可以管理多个模型。凭证仅供本人使用，不会用于平台面试官。</p>
        <ol class="connection-steps">
          <li><strong>保存连接</strong><p>填写协议、服务地址与 Key，保存后不会立即调用模型。</p></li><li><strong>添加具体模型</strong><p>填写模型 ID 和参数，分别检查连通性与工具能力。</p></li><li><strong>在演练中选择</strong><p>显式选择连接与模型版本，不自动替换已有选择。</p></li>
        </ol>
      </div>
    </div>
  </section>
</template>
<style scoped>
.settings-layout {
  display: grid;
  grid-template-columns: 310px minmax(0, 1fr);
  gap: 20px;
  padding: 0;
  align-items: start;
}

.directory {
  padding: 20px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
  box-shadow: var(--oc-shadow-sm), var(--oc-hairline-top);
}

.detail {
  padding: 26px;
  min-width: 0;
  min-height: 460px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
  box-shadow: var(--oc-shadow-sm), var(--oc-hairline-top);
}

.connection-steps {
  padding-left: 0;
  list-style: none;
  margin-top: 24px;
  display: grid;
  gap: 12px;
  counter-reset: step;
}

.connection-steps li {
  position: relative;
  padding: 14px 16px 14px 48px;
  border-radius: 10px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
}

.connection-steps li::before {
  counter-increment: step;
  content: counter(step);
  position: absolute;
  left: 14px;
  top: 14px;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: var(--oc-accent-soft);
  border: 1px solid var(--oc-border-accent);
  color: var(--oc-accent);
  font-size: 12px;
  font-weight: 600;
}

.connection-steps p,
.empty > p {
  margin: 4px 0 0;
  color: var(--oc-fg-muted);
  font-size: 13.5px;
  line-height: 1.65;
}

h2 {
  font-size: 18px;
  font-weight: 650;
  margin: 0 0 16px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.directory ul {
  list-style: none;
  padding: 0;
  margin: 0 0 16px;
  display: grid;
  gap: 8px;
}

.directory li {
  border-bottom: 0;
}

.directory li button {
  width: 100%;
  text-align: left;
  padding: 12px 14px;
  font: inherit;
  font-size: 13.5px;
  font-weight: 550;
  color: var(--oc-fg);
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  border-radius: 10px;
  cursor: pointer;
  overflow-wrap: anywhere;
  transition: background-color 160ms var(--oc-ease), border-color 160ms var(--oc-ease);
}

.directory li button:hover {
  background: var(--oc-surface-hover);
  border-color: var(--oc-border-strong);
}

.directory li button[aria-pressed="true"] {
  background: var(--oc-selected);
  border-color: var(--oc-border-accent);
}

.directory li span {
  display: block;
  color: var(--oc-fg-muted);
  font-size: 12px;
  font-weight: 450;
  margin-top: 4px;
}

code {
  overflow-wrap: anywhere;
  padding: 2px 6px;
  border-radius: 5px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  font-size: 12.5px;
}

.empty {
  padding: 8px 0;
  max-width: 580px;
}

@media (max-width: 780px) {
  .settings-layout {
    grid-template-columns: 1fr;
  }
}
</style>
