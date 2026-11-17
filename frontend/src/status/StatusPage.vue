<script setup lang="ts">
import { computed } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { RouterLink } from 'vue-router';
import type { Identity } from '../auth/api';
import Button from '../components/ui/Button.vue';
import { routes } from '../routes';
import { fetchStatus } from './api';

const client = useQueryClient();
const cachedIdentity = computed(() => client.getQueryData<Identity | null>(['identity-session']) ?? null);
const showTabs = computed(() => !!cachedIdentity.value && !cachedIdentity.value.passwordChangeRequired);

const { data, dataUpdatedAt, isFetching, isError, refetch } = useQuery({
  queryKey: ['internal-dependency-status'],
  queryFn: ({ signal }) => fetchStatus(signal),
  retry: false,
  refetchOnWindowFocus: false,
  refetchOnReconnect: false,
  staleTime: 0,
});
</script>

<template>
  <main class="status-page">
    <header class="status-header">
      <div class="header-top-row">
        <h1>{{ showTabs ? '设置' : '服务状态' }}</h1>
        <div class="status-meta-pills">
          <span
            v-if="!isFetching && !isError && dataUpdatedAt"
            class="status-checked-at font-mono-num"
          >
            {{ new Date(dataUpdatedAt).toLocaleTimeString() }}
          </span>
          <span
            v-if="!isFetching && !isError && data"
            class="status-pill"
            :data-tone="data.database === 'AVAILABLE' && data.sandbox === 'AVAILABLE' ? 'ok' : 'danger'"
          >
            {{ data.database === 'AVAILABLE' && data.sandbox === 'AVAILABLE' ? '核心链路正常' : '存在不可用依赖' }}
          </span>
        </div>
      </div>
      <nav
        v-if="showTabs && cachedIdentity"
        class="ui-underline-tabs"
        aria-label="设置切换"
      >
        <RouterLink
          class="ui-underline-tab"
          :to="{ name: routes.account.name }"
        >
          {{ cachedIdentity.role === 'ADMIN' ? '账号管理' : '账号设置' }}
        </RouterLink>
        <RouterLink
          class="ui-underline-tab is-active"
          aria-current="page"
          :to="{ name: routes.status.name }"
        >
          服务状态
        </RouterLink>
      </nav>
    </header>

    <section
      class="status-panel"
      aria-label="依赖检查结果"
      aria-live="polite"
      :aria-busy="isFetching"
    >
      <div class="status-toolbar">
        <h2>依赖检查</h2>
        <Button
          variant="secondary"
          size="small"
          :disabled="isFetching"
          :loading="isFetching"
          @click="refetch()"
        >
          重新检查
        </Button>
      </div>

      <p
        v-if="isFetching"
        role="status"
        class="status-message"
      >
        正在检查依赖服务…
      </p>
      <p
        v-else-if="isError"
        role="alert"
        class="status-message failure"
      >
        无法取得有效状态。请检查服务连通性后重试。
      </p>
      <div
        v-else-if="data"
        class="status-table-wrap"
      >
        <table class="status-table">
          <thead>
            <tr>
              <th scope="col">
                依赖组件
              </th>
              <th scope="col">
                服务角色
              </th>
              <th scope="col">
                当前状态
              </th>
            </tr>
          </thead>
          <tbody>
            <tr data-testid="database">
              <th scope="row">
                PostgreSQL 数据库
              </th>
              <td class="status-role">
                主业务持久化
              </td>
              <td>
                <span
                  class="health-badge"
                  :data-health="data.database"
                ><span
                  class="health-dot"
                  aria-hidden="true"
                />{{ data.database === 'AVAILABLE' ? '可用' : '不可用' }}</span>
              </td>
            </tr>
            <tr data-testid="sandbox">
              <th scope="row">
                Go 服务
              </th>
              <td class="status-role">
                工作区文件与沙箱调度
              </td>
              <td>
                <span
                  class="health-badge"
                  :data-health="data.sandbox"
                ><span
                  class="health-dot"
                  aria-hidden="true"
                />{{ data.sandbox === 'AVAILABLE' ? '可用' : '不可用' }}</span>
              </td>
            </tr>
            <tr data-testid="execution-environment">
              <th scope="row">
                Docker 执行环境
              </th>
              <td class="status-role">
                按题目按需制备
              </td>
              <td>
                <span
                  class="health-badge"
                  data-health="UNKNOWN"
                ><span
                  class="health-dot"
                  aria-hidden="true"
                />未检测</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p
        v-else
        class="status-message"
      >
        尚未取得检查结果。
      </p>

      <p
        v-if="!isFetching && !isError && data && (data.database === 'UNAVAILABLE' || data.sandbox === 'UNAVAILABLE')"
        class="status-alert-bar"
      >
        存在不可用依赖，请检查后重试。
      </p>
      <p class="status-footnote">
        此页只检查底层依赖；执行环境以题目准备结果为准。
      </p>
    </section>
  </main>
</template>

<style scoped>
.status-page {
  max-width: 768px;
  margin: 0 auto;
  padding: 32px 24px 64px;
  min-width: 0;
}

.status-header {
  display: grid;
  gap: 12px;
  margin-bottom: 24px;
}

.header-top-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.status-header h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.status-meta-pills {
  display: flex;
  align-items: center;
  gap: 10px;
}

.status-checked-at {
  font-size: 12px;
  color: var(--oc-fg-dim);
}

.status-pill {
  font-size: 11.5px;
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--oc-border);
  background: var(--oc-surface);
  color: var(--oc-fg-muted);
}

.status-pill[data-tone="danger"] {
  background: var(--oc-danger-bg);
  color: var(--oc-danger);
  border-color: var(--oc-danger-border);
}

.ui-underline-tabs a {
  text-decoration: none;
}

.status-panel {
  display: grid;
  gap: 12px;
}

.status-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.status-toolbar h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--oc-fg-muted);
}

.status-table-wrap {
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  overflow-x: auto;
}

.status-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.status-table th,
.status-table td {
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid var(--oc-border);
}

.status-table thead th {
  font-size: 11.5px;
  font-weight: 500;
  color: var(--oc-fg-dim);
  background: var(--oc-bg-subtle);
}

.status-table tbody tr:last-child th,
.status-table tbody tr:last-child td {
  border-bottom: 0;
}

.status-table tbody th {
  font-weight: 500;
  color: var(--oc-fg);
}

.status-role {
  color: var(--oc-fg-muted);
}

.health-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.health-dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: var(--oc-fg-dim);
}

.health-badge[data-health="AVAILABLE"] {
  color: var(--oc-fg);
}

.health-badge[data-health="AVAILABLE"] .health-dot {
  background: #34d399;
}

.health-badge[data-health="UNAVAILABLE"] {
  color: var(--oc-danger);
}

.health-badge[data-health="UNAVAILABLE"] .health-dot {
  background: var(--oc-danger);
}

.status-message {
  margin: 0;
  padding: 16px;
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.status-message.failure,
.status-alert-bar {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-danger-bg);
  color: var(--oc-danger);
  font-size: 13px;
}

.status-footnote {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-dim);
}

@media (max-width: 640px) {
  .status-page {
    padding: 20px 16px 40px;
  }
}
</style>
