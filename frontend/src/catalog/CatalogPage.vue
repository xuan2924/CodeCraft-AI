<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useIntervalFn, useTimestamp } from '@vueuse/core';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { RouterLink, useRouter } from 'vue-router';
import { Star, ArrowRight } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import StatePanel from '../components/ui/StatePanel.vue';
import { session } from '../auth/api';
import { ApiError } from '../api/request';
import { useCommand } from '../api/useCommand';
import * as workspace from '../workspace/api';
import * as api from './api';
import { routes } from '../routes';

const client = useQueryClient();
const router = useRouter();
const account = useQuery({ queryKey: ['identity-session'], queryFn: ({ signal }) => session(signal), retry: false });
const owner = computed(() => account.data.value?.id ?? '');
const now = useTimestamp({ scheduler: callback => useIntervalFn(callback, 1000) });
const allowed = computed(() => account.isFetchedAfterMount.value && !!owner.value && account.data.value?.role === 'USER'
  && !account.data.value.passwordChangeRequired && !account.isError.value && Date.parse(account.data.value.expiresAt) > now.value);
const catalogue = useQuery({ queryKey: ['catalog', owner], queryFn: ({ signal }) => workspace.problems(signal), enabled: allowed, retry: false });
const favorites = useQuery({ queryKey: ['favorites', owner], queryFn: ({ signal }) => api.favorites(owner.value, signal), enabled: allowed, retry: false });
const current = useQuery({ queryKey: ['workspace', owner, 'current'], queryFn: ({ signal }) => workspace.current(signal, undefined, owner.value), enabled: allowed, retry: false, refetchInterval: 5000 });
watch([favorites.error, current.error], (errors) => {
  if (errors.some(error => error instanceof ApiError && [401, 403].includes(error.status))) {
    client.setQueryData(['identity-session'], null);
    void account.refetch();
  }
});
const search = ref('');
const onlyFavorites = ref(false);
const selected = ref('');
const page = ref(0);
const saved = computed(() => new Set(favorites.data.value?.map((entry) => entry.problemId)));
const filtered = computed(() => (catalogue.data.value ?? []).filter((entry) => entry.title.toLowerCase().includes(search.value.toLowerCase())
  && (!onlyFavorites.value || !!entry.problemId && saved.value.has(entry.problemId))));
const visible = computed(() => filtered.value.slice(page.value * 12, (page.value + 1) * 12));
const detail = computed(() => visible.value.find((entry) => entry.problemVersionId === selected.value) ?? visible.value[0]);
const withdrawn = computed(() => (favorites.data.value ?? []).filter((entry) => !entry.available));
watch([search, onlyFavorites, owner], () => { page.value = 0; selected.value = ''; });
watch(owner, () => { search.value = ''; onlyFavorites.value = false; });
watch(() => filtered.value.length, () => { if (page.value * 12 >= filtered.value.length) page.value = 0; });
const policy = { definitiveRejection: (failure: unknown) => failure instanceof ApiError && [400, 401, 403, 404, 409, 429].includes(failure.status),
  errorMessage: workspace.message, identityFailure: (failure: unknown) => failure instanceof ApiError && failure.status === 401 };
const favorite = useCommand<void>(() => owner.value, () => { void client.invalidateQueries({ queryKey: ['favorites', owner.value] }); }, policy);
const create = useCommand<{ exerciseId: string; state: string }>(() => owner.value, (result) => {
  void client.invalidateQueries({ queryKey: ['workspace', owner.value, 'current'] });
  void router.push({ name: routes.workspace.name, query: { exercise: result.exerciseId } });
}, policy);
const startReason = computed(() => !detail.value?.executionAvailable ? '该版本执行环境不可用' : current.data.value ? '请先继续或结束当前演练'
  : current.isPending.value || current.isError.value ? '请先成功确认进行中的演练' : create.pending.value ? '正在创建，请稍候'
  : create.uncertain.value ? '请先核对原创建操作' : '');
function toggle(problem: string) {
  const user = owner.value, desired = !saved.value.has(problem);
  void favorite.submit((_key, signal) => api.setFavorite(user, problem, desired, signal));
}
function start() {
  if (!detail.value || !detail.value.executionAvailable || current.data.value || current.isError.value || current.isPending.value) return;
  const version = detail.value.problemVersionId, user = owner.value;
  void create.submit((key, signal) => workspace.act({ path: '/api/exercises', body: { problemVersionId: version }, key }, signal, undefined, user));
}
</script>

<template>
  <main class="catalog-page">
    <header class="catalog-header">
      <div>
        <p class="eyebrow">
          CODECRAFT / ENGINEERING PRACTICE
        </p><h1>从一个真实问题开始</h1><p>选择题目，检查环境，在工作区中完成你的工程实践。</p>
      </div><RouterLink :to="{ name: routes.history.name }">历史演练 <ArrowRight :size="16" /></RouterLink>
    </header>
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
      description="请登录并完成临时密码修改。"
    >
      <RouterLink :to="{ name: routes.account.name }">前往账号页</RouterLink>
    </StatePanel>
    <template v-else>
      <section
        class="current-exercise"
        aria-label="进行中演练"
      >
        <div>
          <h2>继续你的演练</h2><p v-if="current.isPending.value">
            正在查询…
          </p><p
            v-else-if="current.isError.value"
            role="alert"
          >
            无法确认进行中的演练；暂不创建新的演练。
          </p><p v-else-if="!current.data.value">
            没有进行中的演练。准备好后，选择一道题目。
          </p><p v-else>
            演练 {{ current.data.value.exerciseId }} · {{ current.data.value.state }}
          </p>
        </div>
        <RouterLink
          v-if="current.data.value && !current.isError.value"
          :to="{ name: routes.workspace.name, query: { exercise: current.data.value.exerciseId } }"
        >继续演练 →</RouterLink>
        <Button
          v-if="current.isError.value"
          variant="secondary"
          @click="current.refetch()"
        >
          重新查询
        </Button>
      </section>
      <div class="catalog-filters">
        <label>搜索题目 <input
          v-model="search"
          type="search"
          maxlength="128"
        ></label><label><input
          v-model="onlyFavorites"
          type="checkbox"
          :disabled="favorites.isError.value || favorites.isPending.value"
        >仅收藏</label><span>{{ filtered.length }} 个版本</span>
      </div>
      <StatePanel
        v-if="catalogue.isPending.value"
        kind="loading"
        title="正在读取题库"
        description="只展示已发布的可用版本。"
      />
      <StatePanel
        v-else-if="catalogue.isError.value"
        kind="error"
        title="题库读取失败"
        description="不会使用过期列表创建演练。"
      >
        <Button @click="catalogue.refetch()">
          重试
        </Button>
      </StatePanel>
      <StatePanel
        v-else-if="!filtered.length"
        title="没有符合筛选的题目"
        description="调整搜索或收藏条件后重试。"
      />
      <div
        v-else
        class="catalog-columns"
      >
        <section
          class="problem-list"
          aria-label="已发布题目"
        >
          <Tooltip
            v-for="entry in visible"
            :key="entry.problemVersionId"
            :text="entry.title"
          >
            <button
              class="problem-choice"
              :aria-pressed="detail?.problemVersionId === entry.problemVersionId"
              @click="selected = entry.problemVersionId"
            >
              <strong>{{ entry.title }}</strong><span>Java · {{ entry.version }} · {{ entry.durationSeconds / 60 }} 分钟</span>
            </button>
          </Tooltip>
        </section>
        <section
          v-if="detail"
          class="problem-detail"
          aria-label="题目详情"
        >
          <header>
            <div>
              <p class="eyebrow">
                JAVA / {{ detail.version }}
              </p><h2>{{ detail.title }}</h2>
            </div><Button
              v-if="detail.problemId"
              variant="ghost"
              :aria-label="saved.has(detail.problemId) ? '取消收藏' : '收藏题目'"
              :aria-pressed="saved.has(detail.problemId)"
              :disabled="favorite.pending.value || favorite.uncertain.value || favorites.isError.value || favorites.isPending.value"
              @click="toggle(detail.problemId)"
            >
              <Star
                :size="20"
                :fill="saved.has(detail.problemId) ? 'currentColor' : 'none'"
              />
            </Button>
          </header>
          <p class="requirements">
            {{ detail.requirements }}
          </p><p>时限 {{ detail.durationSeconds / 60 }} 分钟 · 服务端执行环境</p>
          <div class="start-action">
            <p v-if="!detail.executionAvailable">
              该版本执行环境不可用，不能开始。
            </p><p v-else-if="current.data.value">
              请先继续或结束当前演练。
            </p><p v-else>
              创建后先完成模型与环境检查，不会立即开始计时。
            </p><Tooltip :text="startReason || '进入准备页，检查模型与执行环境'">
              <span :tabindex="startReason ? 0 : undefined"><Button
                variant="primary"
                :disabled="!!startReason"
                @click="start"
              >
                {{ create.pending.value ? '正在创建…' : '准备演练' }}
              </Button></span>
            </Tooltip>
          </div>
        </section>
      </div>
      <nav
        v-if="filtered.length > 12"
        aria-label="题库分页"
      >
        <Button
          variant="secondary"
          :disabled="page === 0"
          @click="page--"
        >
          上一页
        </Button><span>第 {{ page + 1 }} 页</span><Button
          variant="secondary"
          :disabled="(page + 1) * 12 >= filtered.length"
          @click="page++"
        >
          下一页
        </Button>
      </nav>
      <p
        v-if="favorites.isError.value"
        role="alert"
      >
        收藏读取失败。<Button
          variant="secondary"
          @click="favorites.refetch()"
        >
          重试收藏
        </Button>
      </p>
      <p
        v-if="favorite.error.value"
        role="alert"
      >
        {{ favorite.error.value }}
      </p><Button
        v-if="favorite.uncertain.value"
        :disabled="favorite.pending.value"
        @click="favorite.retry()"
      >
        核对并重试原收藏操作
      </Button>
      <p
        v-if="create.error.value"
        role="alert"
      >
        {{ create.error.value }}
      </p><Button
        v-if="create.uncertain.value"
        :disabled="create.pending.value"
        @click="create.retry()"
      >
        核对并重试原创建
      </Button>
      <section
        v-if="withdrawn.length"
        aria-label="已下架收藏"
      >
        <h2>已下架收藏</h2><p>收藏仍保留，但不能创建新演练。</p><div
          v-for="entry in withdrawn"
          :key="entry.problemId"
          class="withdrawn"
        >
          <span>{{ entry.title }} · 不可开始</span><Button
            variant="secondary"
            :disabled="favorite.pending.value || favorite.uncertain.value"
            @click="toggle(entry.problemId)"
          >
            取消收藏
          </Button>
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.catalog-page { max-width: 1320px; margin: auto; padding: 24px; }
.catalog-header, .current-exercise, .problem-detail header, .catalog-filters, nav, .withdrawn { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.catalog-header { margin-bottom: 32px; } h1 { font-size: clamp(24px, 3vw, 36px); } h2 { font-size: 20px; overflow-wrap: anywhere; }
.eyebrow { color: var(--accent); font-size: 12px; letter-spacing: .08em; }
p { line-height: 1.7; } .current-exercise p, .catalog-header p:not(.eyebrow), .problem-choice span { color: var(--muted); }
.current-exercise { border: 1px solid var(--border); background: var(--surface); border-radius: var(--radius-panel); padding: 20px; overflow-wrap: anywhere; }
.catalog-filters { margin: 24px 0; } input[type="search"] { max-width: 100%; padding: 8px; background: var(--background); border: 1px solid var(--control-border); border-radius: var(--radius-control); color: var(--foreground); }
.catalog-columns { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.4fr); gap: 24px; align-items: start; }
.problem-list { display: grid; gap: 12px; } .problem-choice { padding: 20px; text-align: left; display: grid; gap: 12px; color: var(--foreground); background: var(--surface); border: 1px solid var(--control-border); border-radius: var(--radius-panel); cursor: pointer; overflow-wrap: anywhere; }
.problem-choice strong { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; min-height: 2.8em; }
.problem-choice[aria-pressed="true"] { border-color: var(--accent); background: var(--selected); }
.problem-detail { min-width: 0; border: 1px solid var(--border); border-radius: var(--radius-panel); padding: 24px; background: var(--surface); }
.problem-detail header > div { flex: 1; min-width: 0; } .requirements { white-space: pre-wrap; overflow-wrap: anywhere; }
.start-action { border-top: 1px solid var(--border); padding-top: 16px; margin-top: 24px; } nav { justify-content: center; margin: 20px 0; }
.withdrawn { padding: 12px 0; border-bottom: 1px solid var(--border); overflow-wrap: anywhere; }
@media (max-width: 850px) { .catalog-columns { grid-template-columns: minmax(0, 1fr); } .catalog-page { padding: 16px; } }
</style>
