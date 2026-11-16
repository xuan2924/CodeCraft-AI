<script setup lang="ts">
import { computed, nextTick, useTemplateRef, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { Activity, BookOpen, Braces, History, Library, Menu, Settings2, ShieldCheck, Terminal, UserRound } from '@lucide/vue';
import type { Identity } from '../auth/api';
import Button from '../components/ui/Button.vue';
import { routes } from '../routes';
import { useLayoutStore } from './layoutStore';

const props = defineProps<{ identity: Identity | null; pending: boolean; failed: boolean }>();
defineEmits<{ retry: [] }>();
const layout = useLayoutStore();
const route = useRoute();
const navigation = useTemplateRef<globalThis.HTMLElement>('navigation');
const content = useTemplateRef<globalThis.HTMLElement>('content');
const ready = computed(() => !props.pending && !props.failed && props.identity && !props.identity.passwordChangeRequired);
const links = computed(() => [
  ...(ready.value && props.identity?.role === 'USER' ? [
    { ...routes.catalog, label: '题库首页', icon: Library },
    { ...routes.workspace, label: '演练工作区', icon: Braces },
    { ...routes.history, label: '历史演练', icon: History },
    { ...routes.models, label: '个人模型', icon: Settings2 },
    { ...routes.skills, label: '个人 Skill', icon: BookOpen },
  ] : []),
  ...(ready.value && props.identity?.role === 'ADMIN' ? [{ ...routes.platformAgents, label: '平台角色配置', icon: Settings2 }] : []),
  { ...routes.account, label: ready.value && props.identity?.role === 'ADMIN' ? '账号管理' : props.identity ? '账号设置' : '受邀登录', icon: ready.value && props.identity?.role === 'ADMIN' ? ShieldCheck : UserRound },
  { ...routes.status, label: '服务状态', icon: Activity },
]);
watch(() => [props.identity?.id, props.identity?.role, props.identity?.passwordChangeRequired, props.failed], () => layout.closeNavigation());
watch(() => route.fullPath, async () => {
  const navigationHadFocus = navigation.value?.contains(globalThis.document.activeElement);
  layout.closeNavigation();
  await nextTick();
  if (navigationHadFocus) content.value?.focus();
});
</script>

<template>
  <div
    class="app-shell"
    :class="{ 'navigation-expanded': layout.navigationExpanded }"
  >
    <a
      class="skip-link"
      href="#application-content"
    >跳到主要内容</a>
    <aside
      class="app-sidebar"
      aria-label="应用导航"
    >
      <div class="app-brand-row">
        <RouterLink
          :to="{ name: identity?.role === 'USER' && ready ? routes.catalog.name : routes.account.name }"
          class="app-brand"
          aria-label="CodeCraft 首页"
        >
          <Terminal
            :size="24"
            aria-hidden="true"
          /><span>CodeCraft</span>
        </RouterLink>
        <Button
          class="app-nav-toggle"
          variant="ghost"
          size="icon"
          aria-label="展开或收起导航"
          aria-controls="application-navigation"
          :aria-expanded="layout.navigationExpanded"
          @click="layout.toggleNavigation"
        >
          <Menu
            :size="20"
            aria-hidden="true"
          />
        </Button>
      </div>
      <nav
        id="application-navigation"
        ref="navigation"
        aria-label="主要页面"
        class="app-navigation"
      >
        <RouterLink
          v-for="link in links"
          :key="link.name"
          :to="{ name: link.name }"
          class="app-nav-link"
          :title="link.label"
        >
          <component
            :is="link.icon"
            :size="20"
            aria-hidden="true"
          /><span>{{ link.label }}</span>
        </RouterLink>
      </nav>
      <div
        class="app-session"
        aria-live="polite"
      >
        <p
          v-if="pending"
          role="status"
        >
          正在确认身份…
        </p>
        <template v-else-if="failed">
          <p role="alert">
            无法确认登录状态
          </p>
          <Button
            data-testid="retry-session"
            size="small"
            @click="$emit('retry')"
          >
            重新检查
          </Button>
        </template>
        <template v-else-if="identity">
          <UserRound
            :size="20"
            aria-hidden="true"
          />
          <div>
            <span
              class="app-username"
              :title="identity.username"
            >{{ identity.username }}</span><small>{{ identity.passwordChangeRequired ? '需要修改临时密码' : identity.role === 'ADMIN' ? '管理员' : '受邀用户' }}</small>
          </div>
        </template>
        <p v-else>
          仅向受邀用户开放
        </p>
      </div>
    </aside>
    <div
      id="application-content"
      ref="content"
      class="app-content"
      tabindex="-1"
    >
      <slot />
    </div>
  </div>
</template>

<style scoped>
.app-shell { min-height: 100dvh; display: grid; grid-template-columns: 220px minmax(0, 1fr); }
.app-sidebar { position: sticky; top: 0; height: 100dvh; display: flex; flex-direction: column; padding: 24px 12px 16px; border-right: 1px solid var(--border); background: var(--background); }
.app-brand-row { display: flex; align-items: center; justify-content: space-between; gap: 4px; margin-bottom: 32px; }
.app-brand { display: flex; align-items: center; gap: 10px; padding-left: 8px; color: var(--foreground); text-decoration: none; font-size: 20px; font-weight: 650; }
.app-navigation { display: grid; gap: 8px; }
.app-nav-link { display: flex; gap: 12px; align-items: center; min-height: 44px; padding: 10px 12px; border-left: 3px solid transparent; border-radius: 0 6px 6px 0; color: var(--foreground); text-decoration: none; font-size: 14px; }
.app-nav-link:hover { background: var(--surface); }
.app-nav-link.router-link-exact-active { background: var(--selected); border-left-color: var(--accent); }
.app-nav-link svg { flex-shrink: 0; }
.app-session { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: auto; padding: 20px 8px 0; border-top: 1px solid var(--border); font-size: 13px; }
.app-session > div { min-width: 0; flex: 1; }
.app-session small { display: block; color: var(--muted); margin-top: 4px; }
.app-session p { margin: 0; color: var(--muted); }
.app-username { display: block; overflow-wrap: anywhere; }
.app-content { min-width: 0; }
.skip-link { position: fixed; top: -80px; left: 16px; z-index: 200; padding: 12px 16px; background: var(--primary); color: var(--primary-foreground); border-radius: 6px; }
.skip-link:focus { top: 12px; }
@media (min-width: 901px) {
  .app-nav-toggle { display: none; }
}
@media (max-width: 900px) {
  .app-shell { display: block; }
  .app-sidebar { position: relative; height: auto; padding: 12px 16px; border-right: 0; border-bottom: 1px solid var(--border); }
  .app-brand-row { margin: 0; }
  .app-navigation, .app-session { display: none; }
  .navigation-expanded .app-navigation { display: grid; margin-top: 16px; }
  .navigation-expanded .app-session { display: flex; margin-top: 16px; padding-top: 16px; }
}
</style>
