<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { Plus, RefreshCw } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import StatePanel from '../components/ui/StatePanel.vue';
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

const list = useQuery({
  queryKey: computed(() => ['model-access', props.owner, 'connections', cursor.value]),
  queryFn: ({ signal }) => api.connections(cursor.value, signal, props.owner),
  retry: false,
  gcTime: 0,
});
const detail = useQuery({
  queryKey: computed(() => ['model-access', props.owner, 'connection', selected.value]),
  queryFn: ({ signal }) => api.getConnection(selected.value!, signal, props.owner),
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
function saved(connection: api.Connection) { selected.value = connection.id; form.value = null; notice.value = '连接已保存。'; refresh(); }
function deleted() { selected.value = null; form.value = null; notice.value = '连接及其模型配置已删除。'; refresh(); }
</script>

<template>
  <section
    class="settings-layout ui-surface-flat"
    aria-label="个人连接管理"
  >
    <nav
      class="directory"
      aria-label="连接目录"
    >
      <div class="directory-head">
        <h2>连接目录</h2>
        <div class="directory-actions">
          <Button
            variant="ghost"
            size="small"
            title="重新读取"
            @click="refresh"
          >
            <RefreshCw
              :size="13"
              aria-hidden="true"
            />
            <span class="sr-only-text">重新读取</span>
          </Button>
          <Button
            variant="secondary"
            size="small"
            @click="form = 'new'"
          >
            <Plus
              :size="13"
              aria-hidden="true"
            />
            <span>新建连接</span>
          </Button>
        </div>
      </div>

      <p
        v-if="list.isPending.value"
        role="status"
        class="dir-status"
      >
        正在读取连接…
      </p>
      <p
        v-else-if="list.isError.value"
        role="alert"
        class="failure dir-status"
      >
        {{ errorMessage(list.error.value) }}
      </p>
      <template v-else-if="list.data.value">
        <p
          v-if="!list.data.value.items.length"
          class="dir-empty"
        >
          暂无已保存连接
        </p>
        <ul
          v-else
          class="connection-list ui-divider-list"
        >
          <li
            v-for="connection in list.data.value.items"
            :key="connection.id"
          >
            <button
              type="button"
              :aria-pressed="selected === connection.id && form !== 'new'"
              @click="selected = connection.id; form = null"
            >
              <span class="conn-name">{{ connection.name }}</span>
              <span class="conn-proto font-mono-num">{{ connection.protocol }}</span>
            </button>
          </li>
        </ul>
        <div
          v-if="cursor || list.data.value.nextCursor"
          class="dir-pagination"
        >
          <Button
            variant="ghost"
            size="small"
            :disabled="!cursor"
            @click="cursor = null"
          >
            连接首页
          </Button>
          <Button
            variant="ghost"
            size="small"
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
        class="detail-notice"
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
          class="dir-status"
        >
          正在读取连接详情…
        </p>
        <p
          v-else-if="detail.isError.value"
          role="alert"
          class="failure dir-status"
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
            <div class="detail-header">
              <div>
                <h2>{{ detail.data.value.name }}</h2>
                <p class="detail-meta font-mono-num">
                  <code>{{ detail.data.value.baseUrl }}</code>
                  <span>{{ detail.data.value.protocol }} · {{ detail.data.value.preset }}</span>
                  <span>{{ detail.data.value.keyConfigured ? 'Key 已配置' : 'Key 未配置' }}</span>
                </p>
              </div>
              <div class="toolbar">
                <Button
                  variant="ghost"
                  size="small"
                  @click="form = 'edit'"
                >
                  编辑连接
                </Button>
                <DeleteConfiguration
                  :key="selected"
                  :owner="owner"
                  kind="connection"
                  :entity="detail.data.value"
                  @deleted="deleted"
                  @unauthorized="emit('unauthorized')"
                />
              </div>
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
        class="empty-detail"
      >
        <StatePanel
          title="还没有个人模型连接"
          description="保存连接地址与 API Key 后，即可添加具体模型并测试连通性。"
        >
          <Button
            variant="primary"
            @click="form = 'new'"
          >
            新建连接
          </Button>
        </StatePanel>
      </div>
    </div>
  </section>
</template>

<style scoped>
.settings-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  min-height: min(640px, calc(100dvh - 180px));
  overflow: hidden;
}

.directory {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
  min-width: 0;
}

.directory-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--oc-border);
}

.directory-head h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--oc-fg-muted);
}

.directory-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.sr-only-text {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
}

.dir-status,
.dir-empty {
  padding: 14px;
  margin: 0;
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.connection-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
}

.connection-list button {
  width: 100%;
  text-align: left;
  padding: 11px 14px;
  font: inherit;
  color: var(--oc-fg);
  background: transparent;
  border: 0;
  border-left: 2px solid transparent;
  cursor: pointer;
  overflow-wrap: anywhere;
  transition: background-color 120ms var(--oc-ease);
}

.connection-list button:hover {
  background: rgba(255, 255, 255, 0.03);
}

.connection-list button[aria-pressed="true"] {
  background: var(--oc-surface-2);
  border-left-color: var(--oc-accent);
}

.conn-name {
  display: block;
  font-size: 13.5px;
  font-weight: 550;
}

.conn-proto {
  display: block;
  color: var(--oc-fg-muted);
  font-size: 11px;
  margin-top: 3px;
}

.dir-pagination {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  margin-top: auto;
  border-top: 1px solid var(--oc-border);
}

.detail {
  padding: 22px 26px;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.detail-notice {
  margin: 0 0 14px;
  padding: 8px 12px;
  border-radius: var(--radius-control);
  background: var(--oc-accent-soft);
  color: var(--oc-accent);
  font-size: 12.5px;
}

.detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}

.detail-header h2 {
  margin: 0 0 6px;
  font-size: 18px;
  font-weight: 650;
}

.detail-meta {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.detail-meta code {
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--oc-bg-input);
  color: var(--oc-fg);
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.empty-detail {
  margin: auto;
  max-width: 440px;
  width: 100%;
}

.failure {
  color: var(--oc-danger);
}

@media (max-width: 780px) {
  .settings-layout {
    grid-template-columns: 1fr;
    min-height: auto;
  }
  .directory {
    border-right: 0;
    border-bottom: 1px solid var(--oc-border);
  }
  .detail {
    padding: 18px 16px;
  }
}
</style>
