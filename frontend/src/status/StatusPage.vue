<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
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
      <p class="project-name">
        CodeCraft / 工程底座
      </p>
      <h1>服务链路检查</h1>
      <p>仅用于本机内部联调，不是完整演练入口。</p>
    </header>
    <section
      aria-label="依赖检查结果"
      aria-live="polite"
      :aria-busy="isFetching"
    >
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
            </th><th scope="col">
              状态
            </th>
          </tr>
        </thead>
        <tbody>
          <tr data-testid="database">
            <th scope="row">
              PostgreSQL 数据库
            </th>
            <td>{{ data.database === 'AVAILABLE' ? '可用' : '不可用' }}</td>
          </tr>
          <tr data-testid="sandbox">
            <th scope="row">
              Go 服务
            </th>
            <td>{{ data.sandbox === 'AVAILABLE' ? '可用' : '不可用' }}</td>
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
      <NButton
        type="primary"
        :disabled="isFetching"
        :loading="isFetching"
        @click="refetch()"
      >
        重新检查
      </NButton>
    </section>
    <footer>
      <p>Go 在线仅表示 gRPC 可响应，不代表 Docker 或沙箱执行已就绪。</p>
      <p>账号、AI 与完整演练尚未接入。</p>
    </footer>
  </main>
</template>
