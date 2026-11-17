<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { ChevronRight, Plus } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import Dialog from '../components/ui/Dialog.vue';
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
function deleted() { selected.value = null; form.value = null; notice.value = '连接已删除。'; refresh(); }
</script>

<template>
  <section
    class="settings-flow"
    aria-label="个人连接管理"
  >
    <p
      v-if="notice"
      role="status"
      class="flow-notice"
    >
      {{ notice }}
    </p>

    <div class="group-block">
      <div class="group-header">
        <h2>模型连接</h2>
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

      <p
        v-if="list.isPending.value"
        role="status"
        class="group-status"
      >
        正在读取连接…
      </p>
      <p
        v-else-if="list.isError.value"
        role="alert"
        class="group-alert"
      >
        {{ errorMessage(list.error.value) }}
      </p>
      <template v-else-if="list.data.value">
        <StatePanel
          v-if="!list.data.value.items.length"
          title="还没有个人模型连接"
          description="添加 HTTPS 连接地址与 API Key，用于演练期间调用 Copilot。"
        >
          <Button
            variant="primary"
            size="small"
            @click="form = 'new'"
          >
            新建连接
          </Button>
        </StatePanel>
        <nav
          v-else
          aria-label="连接目录"
          class="list-surface"
        >
          <ul class="ui-divide-list conn-list">
            <li
              v-for="connection in list.data.value.items"
              :key="connection.id"
            >
              <button
                type="button"
                class="conn-row-btn"
                :aria-pressed="selected === connection.id"
                @click="selected = connection.id"
              >
                <span class="conn-row-main">
                  <strong class="conn-name">{{ connection.name }}</strong>
                  <span class="conn-meta font-mono-num">{{ connection.protocol }} · {{ connection.preset }}</span>
                </span>
                <ChevronRight
                  :size="14"
                  class="conn-chevron"
                  aria-hidden="true"
                />
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
        </nav>
      </template>
    </div>

    <div
      v-if="selected"
      class="group-block selected-group"
    >
      <p
        v-if="detail.isPending.value"
        role="status"
        class="group-status"
      >
        正在读取连接详情…
      </p>
      <p
        v-else-if="detail.isError.value"
        role="alert"
        class="group-alert"
      >
        {{ errorMessage(detail.error.value) }}
      </p>
      <template v-else-if="detail.data.value && !detail.data.value.deleted">
        <div class="detail-bar">
          <div class="detail-copy">
            <h3>{{ detail.data.value.name }}</h3>
            <p class="detail-meta font-mono-num">
              <code>{{ detail.data.value.baseUrl }}</code>
              <span>{{ detail.data.value.keyConfigured ? 'Key 已配置' : 'Key 未配置' }}</span>
            </p>
          </div>
          <div class="detail-actions">
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
    </div>

    <Dialog
      :open="form !== null"
      :title="form === 'edit' ? '编辑连接' : '新建连接'"
      @update:open="(val) => { if (!val) { form = null; refresh(); } }"
    >
      <ConnectionForm
        v-if="form === 'new'"
        :key="`${owner}/new`"
        :owner="owner"
        :connection="null"
        @saved="saved"
        @cancel="form = null; refresh()"
        @unauthorized="emit('unauthorized')"
      />
      <ConnectionForm
        v-else-if="form === 'edit' && detail.data.value && !detail.data.value.deleted"
        :key="`${owner}/${selected}`"
        :owner="owner"
        :connection="detail.data.value"
        @saved="saved"
        @cancel="form = null; refresh()"
        @unauthorized="emit('unauthorized')"
      />
    </Dialog>
  </section>
</template>

<style scoped>
.settings-flow {
  display: grid;
  gap: 28px;
  min-width: 0;
}

.flow-notice {
  margin: 0;
  padding: 8px 12px;
  border-radius: var(--radius-control);
  background: var(--oc-accent-bg);
  color: var(--oc-accent);
  font-size: 12.5px;
}

.group-block {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.selected-group {
  padding-top: 22px;
  border-top: 1px solid var(--oc-border);
}

.group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.group-header h2,
.detail-copy h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--oc-fg);
}

.group-status {
  margin: 0;
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.group-alert {
  margin: 0;
  font-size: 13px;
  color: var(--oc-danger);
}

.list-surface {
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: 10px;
  overflow: hidden;
}

.conn-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.conn-row-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 0;
  background: transparent;
  color: var(--oc-fg);
  text-align: left;
  cursor: pointer;
  transition: background-color 120ms var(--oc-ease);
}

.conn-row-btn:hover {
  background: rgba(255, 255, 255, 0.03);
}

.conn-row-btn[aria-pressed="true"] {
  background: var(--oc-surface-2);
}

.conn-row-main {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.conn-name {
  font-size: 13.5px;
  font-weight: 550;
}

.conn-meta {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.conn-chevron {
  flex-shrink: 0;
  color: var(--oc-fg-dim);
}

.conn-row-btn[aria-pressed="true"] .conn-chevron {
  color: var(--oc-accent);
}

.list-pagination {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid var(--oc-border);
}

.detail-bar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.detail-copy {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.detail-meta {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.detail-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
</style>
