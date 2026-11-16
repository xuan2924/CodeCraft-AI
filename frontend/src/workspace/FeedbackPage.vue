<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { useIntervalFn, useTimestamp } from '@vueuse/core';
import { session } from '../auth/api';
import Button from '../components/ui/Button.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import FeedbackEntry from './FeedbackEntry.vue';
import { message } from './api';
import * as api from './feedbackApi';
const account = useQuery({ queryKey: ['identity-session'], queryFn: ({ signal }) => session(signal), retry: false });
const owner = computed(() => account.data.value?.id ?? '');
const admin = computed(() => account.data.value?.role === 'ADMIN');
const now = useTimestamp({ scheduler: callback => useIntervalFn(callback, 1000) });
const allowed = computed(() => account.isFetchedAfterMount.value && !!owner.value && !account.isError.value && !account.data.value?.passwordChangeRequired && Date.parse(account.data.value!.expiresAt) > now.value);
const after = ref<string | null>(null);
watch(owner, () => { after.value = null; });
const listing = useQuery({ queryKey: computed(() => ['feedback', owner.value, admin.value, after.value]), gcTime: 0, retry: false, enabled: allowed,
  queryFn: ({ signal }) => api.list(owner.value, admin.value, after.value, signal) });
</script>
<template>
  <main class="feedback-page">
    <h1>{{ admin ? '授权反馈处理' : '我的授权反馈' }}</h1><p>仅包含明确授权的快照，不允许浏览整场私有演练。原演练删除或到期后，反馈与回复也不可继续读取。</p>
    <StatePanel
      v-if="account.isPending.value || !account.isFetchedAfterMount.value"
      kind="loading"
      title="正在确认身份"
      description="请稍候。"
    />
    <StatePanel
      v-else-if="!allowed"
      kind="error"
      title="需要有效登录"
      description="请登录并完成必要的密码更新。"
    />
    <template v-else>
      <Button
        variant="secondary"
        @click="listing.refetch()"
      >
        刷新反馈
      </Button>
      <StatePanel
        v-if="listing.isPending.value"
        kind="loading"
        title="正在读取反馈"
        description="只读取授权且仍在保留期内的快照。"
      />
      <StatePanel
        v-else-if="listing.isError.value"
        kind="error"
        title="无法读取反馈"
        :description="message(listing.error.value)"
      />
      <template v-else>
        <StatePanel
          v-if="!listing.data.value?.length"
          title="暂无反馈"
          description="可以从面试官问答或报告页发起授权反馈；已删除演练的反馈不会继续展示。"
        /><FeedbackEntry
          v-for="item in listing.data.value"
          :key="`${owner}/${item.id}`"
          :owner="owner"
          :admin="admin"
          :value="item"
        />
      </template>
      <div class="pagination">
        <Button
          variant="secondary"
          :disabled="!after || listing.isFetching.value"
          @click="after = null"
        >
          返回第一页
        </Button><Button
          variant="secondary"
          :disabled="listing.data.value?.length !== 20 || listing.isFetching.value || listing.isError.value"
          @click="after = listing.data.value?.at(-1)?.id ?? null"
        >
          下一页
        </Button>
      </div>
    </template>
  </main>
</template>
<style scoped>
.feedback-page { max-width:1000px; padding:32px 24px; margin:auto; } h1 { font-size:28px; } p { line-height:1.65; } .pagination { display:flex; gap:16px; } @media(max-width:600px) { .feedback-page { padding:16px; } }
</style>
