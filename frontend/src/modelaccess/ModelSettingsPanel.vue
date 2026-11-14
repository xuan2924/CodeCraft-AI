<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
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
        <NButton
          type="primary"
          @click="form = 'new'"
        >
          新建连接
        </NButton><NButton @click="refresh">
          重新读取
        </NButton>
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
        <div class="toolbar">
          <NButton
            :disabled="!cursor"
            @click="cursor = null"
          >
            连接首页
          </NButton><NButton
            :disabled="!list.data.value.nextCursor"
            @click="cursor = list.data.value.nextCursor"
          >
            下一页连接
          </NButton>
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
              <NButton @click="form = 'edit'">
                编辑连接
              </NButton><DeleteConfiguration
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
        <h2>一个连接，多个模型</h2><p>连接共用协议、地址与加密 Key。模型各自保存参数，基本连通与工具闭环分别检查。</p><p>从连接目录选择，或新建你的第一个连接。</p>
      </div>
    </div>
  </section>
</template>
<style scoped>
.settings-layout { display: grid; grid-template-columns: minmax(210px, 260px) minmax(0, 1fr); padding: 0; }
.directory { padding: 24px; border-right: 1px solid var(--line); }
.detail { padding: 24px; min-width: 0; }
h2 { font-size: 21px; margin: 0 0 20px; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-bottom: 16px; }
ul { list-style: none; padding: 0; }
li { border-bottom: 1px solid var(--line); }
li button { width: 100%; text-align: left; padding: 16px 4px; font: inherit; color: inherit; background: transparent; border: 0; cursor: pointer; overflow-wrap: anywhere; }
li button[aria-pressed="true"] { font-weight: 700; border-left: 3px solid var(--primary); padding-left: 12px; }
li span { display: block; color: var(--muted); font-size: 12px; margin-top: 8px; }
code { overflow-wrap: anywhere; }
.empty { padding: 20px 0; max-width: 560px; }
@media (max-width: 760px) { .settings-layout { grid-template-columns: 1fr; } .directory { border-right: 0; border-bottom: 1px solid var(--line); } }
</style>
