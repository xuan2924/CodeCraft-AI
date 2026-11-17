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
  ...(ready.value ? [{ ...routes.feedback, label: props.identity?.role === 'ADMIN' ? '授权反馈处理' : '授权反馈', icon: ShieldCheck }] : []),
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
          <span class="app-brand-mark">
            <Terminal
              :size="17"
              aria-hidden="true"
            />
          </span>
          <span class="app-brand-text">CodeCraft</span>
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
            :size="16"
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
          <div class="app-session-avatar">
            <UserRound
              :size="16"
              aria-hidden="true"
            />
          </div>
          <div class="app-session-meta">
            <span
              class="app-username"
              :title="identity.username"
            >{{ identity.username }}</span>
            <small>{{ identity.passwordChangeRequired ? '需要修改临时密码' : identity.role === 'ADMIN' ? '管理员' : '受邀用户' }}</small>
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
.app-shell {
  min-height: 100dvh;
  display: grid;
  grid-template-columns: 216px minmax(0, 1fr);
}

.app-sidebar {
  position: sticky;
  top: 0;
  height: 100dvh;
  display: flex;
  flex-direction: column;
  padding: 16px 10px 12px;
  border-right: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
  min-width: 0;
}

.app-brand-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 18px;
  padding: 0 6px;
}

.app-brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--oc-fg);
  text-decoration: none;
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.025em;
  min-width: 0;
}

.app-brand-mark {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 6px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border-strong);
  color: var(--oc-accent);
  flex-shrink: 0;
}

.app-navigation {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.app-nav-link {
  position: relative;
  display: flex;
  gap: 10px;
  align-items: center;
  min-height: 34px;
  padding: 6px 10px;
  border-radius: 6px;
  border: 1px solid transparent;
  color: var(--oc-fg-muted);
  text-decoration: none;
  font-size: 13px;
  font-weight: 500;
  min-width: 0;
  transition:
    background-color 140ms var(--oc-ease),
    border-color 140ms var(--oc-ease),
    color 140ms var(--oc-ease);
}

.app-nav-link span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-nav-link:hover {
  color: var(--oc-fg);
  background: var(--oc-surface);
}

.app-nav-link.router-link-exact-active {
  color: var(--oc-fg);
  background: var(--oc-surface-2);
  border-color: var(--oc-border);
  font-weight: 550;
}

.app-nav-link.router-link-exact-active::before {
  content: '';
  position: absolute;
  left: -1px;
  top: 7px;
  bottom: 7px;
  width: 2px;
  border-radius: 0 2px 2px 0;
  background: var(--oc-accent);
}

.app-nav-link.router-link-exact-active svg {
  color: var(--oc-accent);
  opacity: 1;
}

.app-nav-link svg {
  flex-shrink: 0;
  opacity: 0.8;
}

.app-session {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 9px;
  margin-top: auto;
  padding: 10px;
  border: 1px solid var(--oc-border);
  border-radius: 8px;
  background: var(--oc-surface);
  font-size: 12px;
  min-width: 0;
}

.app-session-avatar {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 6px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border-strong);
  color: var(--oc-accent);
  flex-shrink: 0;
}

.app-session-meta {
  min-width: 0;
  flex: 1 1 0%;
}

.app-session small {
  display: block;
  color: var(--oc-fg-muted);
  margin-top: 1px;
  font-size: 11px;
  line-height: 1.35;
}

.app-session p {
  margin: 0;
  color: var(--oc-fg-muted);
  font-size: 12px;
}

.app-username {
  display: block;
  color: var(--oc-fg);
  font-weight: 550;
  font-size: 12.5px;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.app-content {
  min-width: 0;
  outline: none;
}

.skip-link {
  position: fixed;
  top: -80px;
  left: 16px;
  z-index: 200;
  padding: 9px 14px;
  background: var(--oc-cta-bg);
  color: var(--oc-cta-fg);
  font-weight: 600;
  border-radius: var(--radius-control);
  box-shadow: var(--oc-shadow-md);
}

.skip-link:focus {
  top: 12px;
}

@media (min-width: 901px) {
  .app-nav-toggle {
    display: none;
  }
}

@media (max-width: 900px) {
  .app-shell {
    display: block;
  }
  .app-sidebar {
    position: sticky;
    top: 0;
    z-index: 40;
    height: auto;
    padding: 10px 14px;
    border-right: 0;
    border-bottom: 1px solid var(--oc-border);
    background: var(--oc-surface-glass);
    backdrop-filter: blur(12px);
  }
  .app-brand-row {
    margin: 0;
    padding: 0;
  }
  .app-navigation,
  .app-session {
    display: none;
  }
  .navigation-expanded .app-navigation {
    display: grid;
    margin-top: 12px;
  }
  .navigation-expanded .app-session {
    display: flex;
    margin-top: 12px;
  }
}
</style>
