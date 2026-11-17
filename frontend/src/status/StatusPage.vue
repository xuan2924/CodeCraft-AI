<script setup lang="ts">
import { computed } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import type { Identity } from '../auth/api';
import Button from '../components/ui/Button.vue';
import SectionTabs from '../components/ui/SectionTabs.vue';
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
      <div class="status-title-group">
        <h1>{{ showTabs ? '设置' : '服务状态' }}</h1>
        <SectionTabs
          v-if="showTabs && cachedIdentity"
          label="设置切换"
          :active="routes.status.name"
          :items="[
            { name: routes.account.name, label: cachedIdentity.role === 'ADMIN' ? '账号管理' : '账号设置' },
            { name: routes.status.name, label: '服务状态' },
          ]"
        />
      </div>
      <div class="status-meta-pills">
        <span
          v-if="!isFetching && !isError && dataUpdatedAt"
          class="status-checked-at font-mono-num"
        >
          {{ new Date(dataUpdatedAt).toLocaleTimeString() }}
        </span>
        <span
          v-if="!isFetching && !isError && data"
          class="ui-pill"
          :data-tone="data.database === 'AVAILABLE' && data.sandbox === 'AVAILABLE' ? 'accent' : 'danger'"
        >
          {{ data.database === 'AVAILABLE' && data.sandbox === 'AVAILABLE' ? '核心链路正常' : '存在不可用依赖' }}
        </span>
      </div>
    </header>
    <section
      class="status-panel"
      aria-label="依赖检查结果"
      aria-live="polite"
      :aria-busy="isFetching"
    >
      <div class="status-heading">
        <div class="status-heading-meta">
          <h2>依赖检查</h2>
          <span class="status-caption">实时探测 PostgreSQL 与 Go 执行服务</span>
        </div>
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
        正在查询 Java、数据库与 Go 服务…
      </p>
      <p
        v-else-if="isError"
        role="alert"
        class="status-message failure"
      >
        无法取得有效状态。请检查 Java 服务与本机代理后重试；先前结果不代表当前状态。
      </p>
      <div
        v-else-if="data"
        class="status-table-wrap"
      >
        <table>
          <caption>此页只检查底层依赖的可用性；演练执行环境以具体题目的准备结果为准。</caption>
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
                主业务持久化与状态存储
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
                Docker / 沙箱执行环境
              </th>
              <td class="status-role">
                当前检查不包含执行环境，请在演练准备时确认。
              </td>
              <td>
                <span
                  class="health-badge"
                  data-health="UNKNOWN"
                >未检测</span>
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
        class="status-alert-bar failure"
      >
        依赖不可用，请检查对应服务后重新检查。
      </p>
    </section>
  </main>
</template>
