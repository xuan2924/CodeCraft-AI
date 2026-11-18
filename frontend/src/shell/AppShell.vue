<script setup lang="ts">
import { computed, nextTick, useTemplateRef, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { Activity, Braces, History, Library, Menu, Settings, Settings2, ShieldCheck, SlidersHorizontal, Terminal, UserRound } from '@lucide/vue';
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
const compactRail = computed(() => route.name === routes.workspace.name);
const links = computed(() => {
  if (ready.value && props.identity?.role === 'USER') {
    return [
      { ...routes.catalog, label: '题库', icon: Library, matches: [routes.catalog.name] },
      { ...routes.workspace, label: '演练工作区', icon: Braces, matches: [routes.workspace.name, routes.report.name] },
      { ...routes.history, label: '历史演练', icon: History, matches: [routes.history.name, routes.feedback.name] },
      { ...routes.models, label: 'AI 配置', icon: SlidersHorizontal, matches: [routes.models.name, routes.skills.name] },
      { ...routes.account, label: '设置', icon: Settings, matches: [routes.account.name, routes.status.name] },
    ];
  }
  if (ready.value && props.identity?.role === 'ADMIN') {
    return [
      { ...routes.platformAgents, label: 'Agent 策略', icon: Settings2, matches: [routes.platformAgents.name] },
      { ...routes.adminOperations, label: '题库与遥测', icon: Library, matches: [routes.adminOperations.name] },
      { ...routes.feedback, label: '授权反馈', icon: ShieldCheck, matches: [routes.feedback.name] },
      { ...routes.account, label: '账号管理', icon: ShieldCheck, matches: [routes.account.name, routes.status.name] },
    ];
  }
  return [
    { ...routes.account, label: props.identity ? '账号设置' : '受邀登录', icon: UserRound, matches: [routes.account.name] },
    { ...routes.status, label: '服务状态', icon: Activity, matches: [routes.status.name] },
  ];
});
function isActive(matches: readonly string[]): boolean {
  return typeof route.name === 'string' && matches.includes(route.name);
}
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
    :class="{ 'navigation-expanded': layout.navigationExpanded, 'is-rail': compactRail }"
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
          title="CodeCraft"
        >
          <span class="app-brand-mark">
            <Terminal
              :size="16"
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
          :class="{ 'is-group-active': isActive(link.matches) }"
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
          class="app-session-note"
        >
          确认身份…
        </p>
        <template v-else-if="failed">
          <p
            role="alert"
            class="app-session-note"
          >
            无法确认登录状态
          </p>
          <Button
            data-testid="retry-session"
            size="small"
            @click="$emit('retry')"
          >
            重试
          </Button>
        </template>
        <template v-else-if="identity">
          <div
            class="app-session-avatar"
            :title="identity.username"
          >
            <UserRound
              :size="15"
              aria-hidden="true"
            />
          </div>
          <div class="app-session-meta">
            <span
              class="app-username"
              :title="identity.username"
            >{{ identity.username }}</span>
            <small>{{ identity.passwordChangeRequired ? '需要改密' : identity.role === 'ADMIN' ? '管理员' : '受邀用户' }}</small>
          </div>
        </template>
        <p
          v-else
          class="app-session-note"
        >
          仅受邀访问
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
  grid-template-columns: 200px minmax(0, 1fr);
  background: var(--oc-bg);
}

.app-shell.is-rail {
  grid-template-columns: 48px minmax(0, 1fr);
}

.app-sidebar {
  position: sticky;
  top: 0;
  height: 100dvh;
  display: flex;
  flex-direction: column;
  padding: 14px 8px 12px;
  border-right: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
  min-width: 0;
}

.app-brand-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 16px;
  padding: 0 6px;
}

.app-brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--oc-fg);
  text-decoration: none;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.02em;
  min-width: 0;
}

.app-brand-mark {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  border-radius: 6px;
  background: var(--oc-surface-2);
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
  gap: 9px;
  align-items: center;
  min-height: 32px;
  padding: 6px 8px;
  border-radius: 6px;
  color: var(--oc-fg-muted);
  text-decoration: none;
  font-size: 13px;
  font-weight: 500;
  min-width: 0;
  transition: background-color 120ms var(--oc-ease), color 120ms var(--oc-ease);
}

.app-nav-link span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-nav-link:hover {
  color: var(--oc-fg);
  background: rgba(255, 255, 255, 0.04);
}

.app-nav-link.router-link-exact-active,
.app-nav-link.is-group-active {
  color: var(--oc-fg);
  background: var(--oc-surface-2);
  font-weight: 550;
}

.app-nav-link.router-link-exact-active::before,
.app-nav-link.is-group-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 7px;
  bottom: 7px;
  width: 2px;
  border-radius: 0 2px 2px 0;
  background: var(--oc-accent);
}

.app-nav-link.router-link-exact-active svg,
.app-nav-link.is-group-active svg {
  color: var(--oc-accent);
  opacity: 1;
}

.app-nav-link svg {
  flex-shrink: 0;
  opacity: 0.78;
}

.app-session {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: auto;
  padding: 12px 6px 2px;
  border-top: 1px solid var(--oc-border);
  font-size: 12px;
  min-width: 0;
}

.app-session-avatar {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  border-radius: 6px;
  background: var(--oc-surface-2);
  color: var(--oc-fg-muted);
  flex-shrink: 0;
}

.app-session-meta {
  min-width: 0;
  flex: 1 1 0%;
}

.app-session small {
  display: block;
  color: var(--oc-fg-dim);
  margin-top: 1px;
  font-size: 11px;
  line-height: 1.3;
}

.app-session-note {
  margin: 0;
  color: var(--oc-fg-muted);
  font-size: 12px;
}

.app-username {
  display: block;
  color: var(--oc-fg);
  font-weight: 500;
  font-size: 12.5px;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.app-content {
  min-width: 0;
  overflow-x: hidden;
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
  .is-rail .app-sidebar {
    padding: 12px 6px;
    align-items: center;
  }
  .is-rail .app-brand-row {
    justify-content: center;
    padding: 0;
    margin-bottom: 14px;
  }
  .is-rail .app-brand-text,
  .is-rail .app-session-meta,
  .is-rail .app-session-note {
    display: none;
  }
  .is-rail .app-navigation {
    width: 100%;
    justify-items: center;
    gap: 4px;
  }
  .is-rail .app-nav-link {
    width: 34px;
    height: 34px;
    justify-content: center;
    padding: 0;
  }
  .is-rail .app-nav-link span {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
  }
  .is-rail .app-session {
    width: 100%;
    justify-content: center;
    padding: 10px 0 0;
  }
}

@media (max-width: 900px) {
  .app-shell,
  .app-shell.is-rail {
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
