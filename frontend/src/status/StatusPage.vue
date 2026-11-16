<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import Button from '../components/ui/Button.vue';
import { fetchStatus } from './api';

const { data, isFetching, isError, refetch } = useQuery({
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
    <header>
      <p class="status-eyebrow">
        INFRASTRUCTURE / HEALTH
      </p>
      <h1>服务状态</h1>
      <p>此页只检查底层依赖的可用性；演练执行环境以具体题目的准备结果为准。</p>
    </header>
    <section
      aria-label="依赖检查结果"
      aria-live="polite"
      :aria-busy="isFetching"
    >
      <div class="status-heading">
        <h2>依赖检查</h2>
        <Button
          variant="secondary"
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
      >
        正在查询 Java、数据库与 Go 服务…
      </p>
      <p
        v-else-if="isError"
        role="alert"
        class="failure"
      >
        无法取得有效状态。请检查 Java 服务与本机代理后重试；先前结果不代表当前状态。
      </p>
      <table v-else-if="data">
        <caption>本次真实查询结果</caption>
        <thead>
          <tr>
            <th scope="col">
              依赖
            </th>
            <th scope="col">
              状态
            </th>
          </tr>
        </thead>
        <tbody>
          <tr data-testid="database">
            <th scope="row">
              PostgreSQL 数据库
            </th>
            <td>
              <span
                class="health-badge"
                :data-health="data.database"
              ><span aria-hidden="true">●</span> {{ data.database === 'AVAILABLE' ? '可用' : '不可用' }}</span>
            </td>
          </tr>
          <tr data-testid="sandbox">
            <th scope="row">
              Go 服务
            </th>
            <td>
              <span
                class="health-badge"
                :data-health="data.sandbox"
              ><span aria-hidden="true">●</span> {{ data.sandbox === 'AVAILABLE' ? '可用' : '不可用' }}</span>
            </td>
          </tr>
          <tr data-testid="execution-environment">
            <th scope="row">
              Docker / 沙箱执行环境
            </th>
            <td>
              <span
                class="health-badge"
                data-health="UNKNOWN"
              >未检测</span>
              <p class="health-helper">
                当前检查不包含执行环境，请在演练准备时确认。
              </p>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else>
        尚未取得检查结果。
      </p>
      <p
        v-if="!isFetching && !isError && data && (data.database === 'UNAVAILABLE' || data.sandbox === 'UNAVAILABLE')"
        class="failure"
      >
        依赖不可用，请检查对应服务后重新检查。
      </p>
    </section>
  </main>
</template>
